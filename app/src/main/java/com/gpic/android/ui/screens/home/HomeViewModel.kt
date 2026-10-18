package com.gpic.android.ui.screens.home

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.StatFs
import android.provider.DocumentsContract
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gpic.android.camera.CameraHttpSource
import com.gpic.android.camera.CameraLink
import com.gpic.android.camera.CameraStager
import com.gpic.android.camera.CameraState
import com.gpic.android.camera.osmosis.camera.PathAddressing
import com.gpic.android.camera.osmosis.core.CameraFile
import com.gpic.android.data.auth.AppConfig
import com.gpic.android.data.auth.Credential
import com.gpic.android.data.cleanup.AgeRule
import com.gpic.android.data.cleanup.AgeUnit
import com.gpic.android.data.cleanup.isOlderThan
import com.gpic.android.data.cleanup.needsExif
import com.gpic.android.data.cleanup.takenMs
import com.gpic.android.data.auth.CredentialStore
import com.gpic.android.data.dji.DjiFile
import com.gpic.android.data.dji.DjiScanner
import com.gpic.android.data.dji.MediaKind
import com.gpic.android.data.progress.FileProgress
import com.gpic.android.data.progress.ProgressTracker
import com.gpic.android.data.progress.UploadStatus
import com.gpic.android.data.stats.LiveStats
import com.gpic.android.data.stats.SystemStatsCollector
import com.gpic.android.data.upload.MediaSource
import com.gpic.android.data.upload.UploadManager
import com.gpic.android.data.upload.defaultSource
import com.gpic.android.upload.UploadService
import com.gpic.android.util.formatSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** A folder picked on the Phone / USB or Free up space tab. */
data class PickedFolder(val uri: Uri, val label: String)

data class HomeUiState(
    /** Folders scanned together on the Phone / USB tab (djiSource == "folder"). */
    val djiFolders: List<PickedFolder> = emptyList(),
    val djiSource: String = "none", // none|folder|files
    val djiFiles: List<DjiFile> = emptyList(),
    val isScanning: Boolean = false,
    val scanError: String? = null,
    val usbStatus: String = "Plug DJI Action 4 via USB-C",
    val usbConnected: Boolean = false,
    val authEmail: String? = null,
    val hasAuth: Boolean = false,
    // Saved credential that can't upload (e.g. the userinfo.profile line); shown on the account card.
    val authProblem: String? = null,
    val uploadRunning: Boolean = false,
    // Cancel requested; uploadRunning stays true until in-flight requests actually stop.
    val cancelling: Boolean = false,
    // Last batch ended because the user cancelled it.
    val wasCancelled: Boolean = false,
    val config: AppConfig = AppConfig(),
    val progressMap: Map<String, FileProgress> = emptyMap(),
    val completed: Int = 0,
    val failed: Int = 0,
    val skipped: Int = 0,
    /** Refused by Google Photos (stickers and the like). */
    val rejected: Int = 0,
    /** Subfolders the last folder scan left out on purpose (sticker folders). */
    val scanNote: String? = null,
    val totalFiles: Int = 0,
    val totalBytes: Long = 0L,
    val systemStats: LiveStats? = null,
    /** Where files come from: phone storage / USB camera, or a DJI camera over WiFi. */
    val source: Source = Source.DEVICE,
    val camera: CameraState = CameraState.Idle,
    /** Wireless transfers need Android 10+ (WifiNetworkSpecifier). */
    val cameraSupported: Boolean = false,
    /** One-line status for the batch (camera download, waiting for internet, space freed, …). */
    val notice: String? = null,
    /** Photos re-uploaded because the library only had a copy without location. */
    val relocated: Int = 0,
    /** Originals deleted from the phone/camera after upload. */
    val deleted: Int = 0,
    val cleanup: CleanupState = CleanupState(),
    /** File types to back up; applies to every source. Never empty. */
    val typeFilter: Set<MediaKind> = MediaKind.entries.toSet(),
) {
    /** [djiFiles] after the type filter: what the list shows and what Upload sends. */
    val files: List<DjiFile> get() =
        if (typeFilter.size == MediaKind.entries.size) djiFiles else djiFiles.filter { it.kind in typeFilter }
    val filesBytes: Long get() = files.sumOf { it.sizeBytes }
}

/** "Free up space": back up a folder's media older than [rule], then delete it from the phone. */
data class CleanupState(
    val folders: List<PickedFolder> = emptyList(),
    val rule: AgeRule = AgeRule(),
    val recursive: Boolean = true,
    /** Supported files in the folder, before the age filter. */
    val inFolder: Int = 0,
    val cutoffMs: Long = 0,
    /** Non-null while listing the folder or reading photo dates. */
    val progress: String? = null,
    val error: String? = null,
    /** What the last folder change did ("DCIM/Camera is already inside DCIM"), or folders GPic can't read. */
    val note: String? = null,
) {
    val folderNames: String get() = folders.joinToString { it.label }
}

enum class Source(val label: String) { DEVICE("Phone / USB"), CLEANUP("Free up space"), CAMERA("Camera WiFi") }

/** [context] must be the Application context: this ViewModel outlives Activity recreation. */
class HomeViewModel(
    private val context: Context,
    private val credentialStore: CredentialStore,
    private val scanner: DjiScanner,
) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private var uploadManager: UploadManager? = null
    private val prefs = context.getSharedPreferences("gpic_prefs", Context.MODE_PRIVATE)
    /** Free-up-space folder contents (unfiltered) and photo EXIF dates read so far (-1 = none). */
    private var cleanupAll: List<DjiFile> = emptyList()
    private val exifCache = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private var cleanupJob: kotlinx.coroutines.Job? = null
    private var statsJob: kotlinx.coroutines.Job? = null
    @Volatile private var batchCancelled = false

    val cameraLink: CameraLink? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) CameraLink(context) else null

    init {
        refreshAuth()
        _state.update { it.copy(cameraSupported = cameraLink != null) }
        cameraLink?.let { link ->
            viewModelScope.launch {
                link.state.collect { cs ->
                    _state.update { st ->
                        val listed = (cs as? CameraState.Ready)?.files?.map(::toDjiFile)
                        if (listed != null && !st.uploadRunning && st.source == Source.CAMERA) st.copy(
                            camera = cs, djiFiles = listed, djiSource = "camera",
                            totalFiles = listed.size, totalBytes = listed.sumOf { it.sizeBytes },
                            progressMap = emptyMap(), completed = 0, failed = 0, skipped = 0, rejected = 0,
                        ) else st.copy(camera = cs)
                    }
                }
            }
        }
        viewModelScope.launch {
            credentialStore.configFlow.collect { c -> _state.update { it.copy(config = c) } }
        }
        refreshUsb()
        _state.update { it.copy(cleanup = loadCleanupPrefs(), typeFilter = loadTypeFilter()) }
        restoreDeviceSelection()
    }

    /** Last folder / files picked on the Phone / USB tab (re-resolved; permissions may have lapsed). */
    private fun restoreDeviceSelection() {
        val savedFiles = prefs.getStringSet("last_dji_files", null)?.toList()
        if (!savedFiles.isNullOrEmpty()) {
            try {
                val uris = savedFiles.map { Uri.parse(it) }
                // Re-resolve without blocking init; user can reselect if permissions expired.
                viewModelScope.launch { setDjiFileUris(uris, persist = false) }
            } catch (_: Exception) {}
        } else {
            val folders = loadFolders(DJI_FOLDERS, legacy = "last_dji_uri")
            if (folders.isEmpty()) return
            _state.update { st -> st.copy(djiFolders = folders, djiSource = "folder") }
            // Phone storage is always there; a USB camera may not be plugged in, so it waits for Rescan.
            if (folders.none { it.uri.authority == com.gpic.android.data.upload.UriSource.MTP_AUTHORITY }) scanDjiFolders()
        }
    }

    /**
     * Saved folder list: one "uri<TAB>label" per line, so launching never asks a camera's provider for names on the
     * main thread. [legacy] is the single-folder key older versions wrote (its label is worked out without IPC).
     */
    private fun loadFolders(key: String, legacy: String): List<PickedFolder> {
        val saved = prefs.getString(key, null)?.lines()?.filter { it.isNotBlank() }
            ?: listOfNotNull(prefs.getString(legacy, null))
        return saved.mapNotNull { line ->
            val uri = runCatching { Uri.parse(line.substringBefore('\t')) }.getOrNull() ?: return@mapNotNull null
            PickedFolder(uri, line.substringAfter('\t', "").ifEmpty { scanner.folderLabel(uri, query = false) })
        }.distinctBy { it.uri }
    }

    private fun saveFolders(key: String, legacy: String, folders: List<PickedFolder>) {
        prefs.edit().apply { if (folders.isEmpty()) remove(key) else putString(key, folders.joinToString("\n") { "${it.uri}\t${it.label}" }) }
            .remove(legacy).apply()
    }

    /** Drops a folder's permission once neither tab lists it. */
    private fun releaseUnused(uris: Collection<Uri>) {
        val inUse = (_state.value.djiFolders + _state.value.cleanup.folders).mapTo(HashSet()) { it.uri }
        uris.filter { it !in inUse }.forEach(scanner::releasePermission)
    }

    /** Adds a folder to [current] (subfolders count as covered when [recursive]); returns the list and a note. */
    private fun addFolder(current: List<PickedFolder>, uri: Uri, recursive: Boolean, label: String): Pair<List<PickedFolder>, String?> {
        val picked = PickedFolder(uri, label)
        val r = com.gpic.android.data.dji.FolderSet.add(current, picked, recursive) { scanner.folderId(it.uri) }
        val note = when {
            r.coveredBy != null && r.coveredBy.uri == uri -> "${picked.label} is already in the list."
            r.coveredBy != null -> "${picked.label} is inside ${r.coveredBy.label}, which is already in the list."
            r.replaced.isNotEmpty() -> "Added ${picked.label}. It includes ${r.replaced.joinToString { it.label }}, so ${if (r.replaced.size == 1) "that was" else "those were"} taken off the list."
            else -> null
        }
        return r.folders to note
    }

    fun refreshAuth() {
        val cred = credentialStore.getActiveCredential()
        _state.value = _state.value.copy(
            authEmail = cred?.email,
            hasAuth = cred != null,
            authProblem = cred?.let { credentialStore.serviceProblem(it.authString) },
        )
    }

    fun refreshUsb() {
        val connected = scanner.checkUsbConnected()
        val summary = scanner.usbDeviceSummary()
        _state.value = _state.value.copy(usbConnected = connected, usbStatus = summary)
    }

    /** Adds a folder to the Phone / USB list (switching from picked files to folders) and rescans them all. */
    fun addDjiFolder(uri: Uri) = viewModelScope.launch {
        scanner.takePersistablePermission(uri)
        // A camera folder's name is an IPC to its provider: not on the main thread.
        val label = withContext(Dispatchers.IO) { scanner.folderLabel(uri) }
        val current = if (_state.value.djiSource == "folder") _state.value.djiFolders else emptyList()
        val (folders, note) = addFolder(current, uri, recursive = true, label = label)
        prefs.edit().remove("last_dji_files").apply()
        saveFolders(DJI_FOLDERS, "last_dji_uri", folders)
        // Folders taken off the list, or the new one when it was already covered, give their permission back.
        val dropped = (_state.value.djiFolders.map { it.uri } + uri) - folders.mapTo(HashSet()) { it.uri }
        _state.update { it.copy(djiFolders = folders, djiSource = "folder") }
        releaseUnused(dropped)
        scanDjiFolders(note)
    }

    fun removeDjiFolder(uri: Uri) {
        if (_state.value.uploadRunning) return
        val folders = _state.value.djiFolders.filter { it.uri != uri }
        saveFolders(DJI_FOLDERS, "last_dji_uri", folders)
        _state.update { it.copy(djiFolders = folders) }
        releaseUnused(listOf(uri))
        if (folders.isEmpty()) clearSelection() else scanDjiFolders()
    }

    fun setDjiFileUris(picked: List<Uri>, persist: Boolean = true) {
        // Uris are the per-file progress/list key, so they must be unique.
        val uris = picked.distinct()
        if (uris.isEmpty()) return
        uris.forEach { scanner.takePersistableFilePermission(it) }
        if (persist) {
            prefs.edit().putStringSet("last_dji_files", uris.map { it.toString() }.toSet()).apply()
            // Picked files replace the folder list.
            val old = _state.value.djiFolders.map { it.uri }
            saveFolders(DJI_FOLDERS, "last_dji_uri", emptyList())
            _state.update { it.copy(djiFolders = emptyList()) }
            releaseUnused(old)
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(isScanning = true, scanError = null, djiSource = "files", scanNote = null)
            try {
                // filesFromUris already returns smallest-first sorted
                val files = com.gpic.android.util.Perf.log("pick ${uris.size} files") { scanner.filesFromUris(uris) }
                _state.value = _state.value.copy(
                    djiFiles = files,
                    progressMap = emptyMap(), completed = 0, failed = 0, skipped = 0, rejected = 0, relocated = 0, deleted = 0, notice = null,
                    totalFiles = files.size,
                    totalBytes = files.sumOf { it.sizeBytes },
                    isScanning = false,
                    scanError = if (files.isEmpty()) "The picked files are gone or aren't photos/videos — choose again" else null
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(isScanning = false, scanError = e.message)
            }
        }
    }

    fun clearSelection() {
        prefs.edit().remove("last_dji_files").apply()
        saveFolders(DJI_FOLDERS, "last_dji_uri", emptyList())
        val old = _state.value.djiFolders.map { it.uri }
        // Also the last run's counters, or the progress card keeps showing "601 / 0".
        _state.value = _state.value.copy(djiFolders = emptyList(), djiSource = "none", djiFiles = emptyList(), totalFiles = 0, totalBytes = 0, progressMap = emptyMap(), scanNote = null,
            completed = 0, failed = 0, skipped = 0, rejected = 0, relocated = 0, deleted = 0, notice = null)
        releaseUnused(old)
    }

    /** Scans every Phone / USB folder; [note] is what the last folder change did. */
    private fun scanDjiFolders(note: String? = null) {
        val folders = _state.value.djiFolders.map { it.uri }
        if (folders.isEmpty()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(isScanning = true, scanError = null)
            try {
                val left = mutableListOf<String>()
                val unreadable = mutableListOf<Uri>()
                val files = com.gpic.android.util.Perf.log("folder scan") { scanner.listTrees(folders, recursive = true, left, unreadable).sortedBy { it.sizeBytes } }
                val lost = _state.value.djiFolders.filter { it.uri in unreadable }
                _state.value = _state.value.copy(
                    djiFiles = files,
                    // A fresh list starts with no statuses (and the Upload button rather than Retry).
                    progressMap = emptyMap(), completed = 0, failed = 0, skipped = 0, rejected = 0, relocated = 0, deleted = 0, notice = null,
                    totalFiles = files.size,
                    totalBytes = files.sumOf { it.sizeBytes },
                    isScanning = false,
                    scanNote = listOfNotNull(
                        note,
                        lost.takeIf { it.isNotEmpty() }?.let { l -> "Can't read ${l.joinToString { it.label }} any more (removed, or access was revoked). Remove it and add it again." },
                        left.takeIf { it.isNotEmpty() }?.let { names ->
                            "Left out ${names.distinct().joinToString { "“$it”" }}: Google Photos doesn't accept stickers."
                        },
                    ).joinToString("\n").ifEmpty { null },
                    scanError = when {
                        lost.size == folders.size -> "Couldn't read the chosen folders"
                        files.isEmpty() -> "No photos or videos in ${if (folders.size == 1) "this folder" else "these folders"}"
                        else -> null
                    },
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(isScanning = false, scanError = e.message)
            }
        }
    }

    fun rescan() {
        if (_state.value.djiSource == "folder") scanDjiFolders()
    }

    fun selectSource(source: Source) {
        if (_state.value.uploadRunning || source == _state.value.source) return
        if (source != Source.CAMERA) cameraLink?.disconnect()
        cleanupJob?.cancel()
        _state.update {
            it.copy(source = source, djiFiles = emptyList(), totalFiles = 0, totalBytes = 0, progressMap = emptyMap(),
                completed = 0, failed = 0, skipped = 0, rejected = 0, relocated = 0, deleted = 0, notice = null, djiSource = "none",
                isScanning = false, scanError = null, cleanup = it.cleanup.copy(progress = null, error = null))
        }
        when (source) {
            Source.DEVICE -> restoreDeviceSelection()
            Source.CLEANUP -> scanCleanup()
            Source.CAMERA -> {}
        }
    }

    private fun loadTypeFilter(): Set<MediaKind> =
        prefs.getStringSet("type_filter", null)
            ?.mapNotNull { runCatching { MediaKind.valueOf(it) }.getOrNull() }?.toSet()
            ?.takeIf { it.isNotEmpty() } ?: MediaKind.entries.toSet()

    /** Turn one file type on or off; the last one left stays on (an empty filter would upload nothing). */
    fun toggleType(kind: MediaKind) {
        if (_state.value.uploadRunning) return
        val now = _state.value.typeFilter.let { if (kind in it) it - kind else it + kind }
        if (now.isEmpty()) return
        prefs.edit().putStringSet("type_filter", now.mapTo(HashSet()) { it.name }).apply()
        _state.update { it.copy(typeFilter = now, progressMap = emptyMap(), completed = 0, failed = 0, skipped = 0, rejected = 0, relocated = 0, deleted = 0, notice = null) }
    }

    // ---- free up space ---------------------------------------------------------------------------

    private fun loadCleanupPrefs(): CleanupState {
        val unit = runCatching { AgeUnit.valueOf(prefs.getString("cleanup_unit", null) ?: "") }.getOrDefault(AgeUnit.MONTHS)
        return CleanupState(
            folders = loadFolders(CLEANUP_FOLDERS, legacy = "cleanup_uri"),
            rule = AgeRule(prefs.getInt("cleanup_amount", 6).coerceIn(1, AgeRule.MAX_AMOUNT), unit),
            recursive = prefs.getBoolean("cleanup_recursive", true),
        )
    }

    fun addCleanupFolder(uri: Uri) = viewModelScope.launch {
        if (_state.value.uploadRunning) return@launch
        scanner.takePersistablePermission(uri)
        val label = withContext(Dispatchers.IO) { scanner.folderLabel(uri) }
        val (folders, note) = addFolder(_state.value.cleanup.folders, uri, _state.value.cleanup.recursive, label)
        val dropped = (_state.value.cleanup.folders.map { it.uri } + uri) - folders.mapTo(HashSet()) { it.uri }
        saveFolders(CLEANUP_FOLDERS, "cleanup_uri", folders)
        cleanupAll = emptyList()
        setCleanup { it.copy(folders = folders) }
        releaseUnused(dropped)
        scanCleanup(note)
    }

    fun removeCleanupFolder(uri: Uri) {
        if (_state.value.uploadRunning) return
        val folders = _state.value.cleanup.folders.filter { it.uri != uri }
        saveFolders(CLEANUP_FOLDERS, "cleanup_uri", folders)
        cleanupAll = emptyList()
        setCleanup { it.copy(folders = folders, note = null, error = null, inFolder = 0) }
        releaseUnused(listOf(uri))
        if (folders.isEmpty()) { cleanupJob?.cancel(); setCleanup { it.copy(progress = null) }; setList(emptyList()) } else scanCleanup()
    }

    fun setCleanupRule(rule: AgeRule) {
        // At least 1: "older than 0 days" would select (and delete) everything.
        val r = rule.copy(amount = rule.amount.coerceIn(1, AgeRule.MAX_AMOUNT))
        prefs.edit().putInt("cleanup_amount", r.amount).putString("cleanup_unit", r.unit.name).apply()
        _state.update { it.copy(cleanup = it.cleanup.copy(rule = r)) }
        filterCleanup()
    }

    fun setCleanupRecursive(on: Boolean) {
        prefs.edit().putBoolean("cleanup_recursive", on).apply()
        _state.update { it.copy(cleanup = it.cleanup.copy(recursive = on)) }
        scanCleanup()
    }

    /** List the folders, then apply the age filter. [note] is what the last folder change did. */
    fun scanCleanup(note: String? = null) {
        if (_state.value.uploadRunning) return
        val picked = _state.value.cleanup.folders
        if (picked.isEmpty()) return
        val recursive = _state.value.cleanup.recursive
        cleanupJob?.cancel()
        cleanupJob = viewModelScope.launch {
            setCleanup { it.copy(progress = "Listing ${it.folderNames}…", error = null, note = note) }
            try {
                val unreadable = mutableListOf<Uri>()
                cleanupAll = com.gpic.android.util.Perf.log("cleanup scan") { scanner.listTrees(picked.map { it.uri }, recursive, unreadable = unreadable) }
                val lost = picked.filter { it.uri in unreadable }
                if (lost.size == picked.size) throw SecurityException("no folder readable")
                setCleanup { it.copy(inFolder = cleanupAll.size, note = listOfNotNull(note, lost.takeIf { l -> l.isNotEmpty() }?.let { l ->
                    "Can't read ${l.joinToString { f -> f.label }} any more (removed, or access was revoked). Remove it and add it again."
                }).joinToString("\n").ifEmpty { null }) }
                applyAgeFilter()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("HomeViewModel", "cleanup scan failed", e)
                cleanupAll = emptyList()
                setCleanup { it.copy(progress = null, inFolder = 0, error = if (e is SecurityException) "Lost access to ${if (picked.size == 1) "the folder" else "the folders"}. Remove and add ${if (picked.size == 1) "it" else "them"} again." else "Couldn't read the folder: ${e.message}") }
                setList(emptyList())
            }
        }
    }

    private fun filterCleanup() {
        if (_state.value.uploadRunning || _state.value.cleanup.folders.isEmpty()) return
        cleanupJob?.cancel()
        cleanupJob = viewModelScope.launch { applyAgeFilter() }
    }

    private suspend fun applyAgeFilter() {
        val cutoff = _state.value.cleanup.rule.cutoffMs()
        val all = cleanupAll
        val toRead = all.filter { it.isPhoto && needsExif(it.lastModified, cutoff) && exifCache[it.key] == null }
        if (toRead.isNotEmpty()) com.gpic.android.util.Perf.log("cleanup EXIF dates for ${toRead.size}") { withContext(Dispatchers.IO) {
            // A few readers at once: each read is mostly waiting on storage/IPC.
            val done = java.util.concurrent.atomic.AtomicInteger()
            coroutineScope {
                toRead.chunked((toRead.size + EXIF_READERS - 1) / EXIF_READERS).forEach { part ->
                    launch {
                        for (f in part) {
                            val n = done.incrementAndGet()
                            if (n % 25 == 1) setCleanup { it.copy(progress = "Reading photo dates $n of ${toRead.size}…") }
                            kotlinx.coroutines.yield() // cancellable when the age changes mid-read
                            exifCache[f.key] = scanner.exifTakenMs(f) ?: -1L
                        }
                    }
                }
            }
        } }
        val old = all.mapNotNull { f ->
            val taken = takenMs(f.lastModified, exifCache[f.key]?.takeIf { it > 0 })
            if (isOlderThan(taken, cutoff)) f.copy(takenMs = taken) else null
        }.sortedBy { it.sizeBytes }
        setCleanup { it.copy(progress = null, cutoffMs = cutoff) }
        setList(old)
    }

    private fun plural(n: Int, word: String) = if (n == 1) "1 $word" else "$n ${word}s"

    /** Google Photos keeps the old GPS-less upload; there's no API call here to edit or remove it. */
    private fun staleCopiesNote(n: Int) =
        "${plural(n, "photo")} re-uploaded with location. The older ${if (n == 1) "copy" else "copies"} without it " +
            "(same date, no location) ${if (n == 1) "is" else "are"} still in Google Photos — delete ${if (n == 1) "it" else "them"} there."

    private fun setCleanup(f: (CleanupState) -> CleanupState) = _state.update { it.copy(cleanup = f(it.cleanup)) }

    private fun setList(files: List<DjiFile>) = _state.update {
        it.copy(djiFiles = files, totalFiles = files.size, totalBytes = files.sumOf { f -> f.sizeBytes }, djiSource = "cleanup",
            progressMap = emptyMap(), completed = 0, failed = 0, skipped = 0, rejected = 0, relocated = 0, deleted = 0, notice = null)
    }

    /** After a free-up-space run: drop deleted files so the list (and a retry) only has what's left. */
    private fun finishCleanup(result: Map<String, FileProgress>) {
        val gone = result.values.filter { it.deleted }
        val goneKeys = gone.mapTo(HashSet()) { it.filePath }
        cleanupAll = cleanupAll.filter { it.key !in goneKeys }
        // totalFiles stays the batch size: the progress header reads "done / total" for the finished run.
        _state.update { st -> st.copy(djiFiles = st.djiFiles.filter { it.key !in goneKeys }) }
        val left = _state.value.files
        setCleanup { it.copy(inFolder = cleanupAll.size) }
        val freed = gone.sumOf { it.totalBytes }
        val relocated = result.values.count { it.staleKey != null }
        notice(listOfNotNull(
            when {
                gone.isEmpty() -> "Nothing was deleted from the phone — see each file below for why."
                left.isEmpty() -> "Freed ${formatSize(freed)}: deleted ${plural(gone.size, "backed-up file")} from the phone."
                else -> "Freed ${formatSize(freed)}: deleted ${plural(gone.size, "file")}. ${left.size} kept — see each file for why."
            },
            relocated.takeIf { it > 0 }?.let { staleCopiesNote(it) },
        ).joinToString("\n"))
    }

    /** Upload what failed or was cancelled in the last run (same source, same delete rules). */
    fun retryFailed() = startUpload(_state.value.files.filter {
        _state.value.progressMap[it.key]?.status in setOf(UploadStatus.ERROR, UploadStatus.CANCELLED)
    })

    fun startUpload(only: List<DjiFile>? = null) {
        if (_state.value.uploadRunning) return
        val files = (only ?: _state.value.files).sortedBy { it.sizeBytes }
        if (files.isEmpty()) return
        val cred = credentialStore.getActiveCredential() ?: return
        val cfg = credentialStore.loadConfig()
        val fromCamera = files.any { it.cameraPath != null }
        viewModelScope.launch {
            batchCancelled = false
            _state.value = _state.value.copy(uploadRunning = true, cancelling = false, wasCancelled = false, progressMap = emptyMap(),
                totalFiles = files.size, totalBytes = files.sumOf { it.sizeBytes },
                completed = 0, failed = 0, skipped = 0, rejected = 0, relocated = 0, deleted = 0, notice = null)
            UploadService.start(context)
            try {
                when {
                    fromCamera && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> uploadFromCamera(files, cfg, cred)
                    // Free up space always deletes what made it (never on a name-only match; see UploadManager).
                    _state.value.source == Source.CLEANUP -> finishCleanup(runUploads(files, cfg, cred, deleteAfter = true))
                    else -> runUploads(files, cfg, cred).values.count { it.staleKey != null }
                        .takeIf { it > 0 }?.let { notice(staleCopiesNote(it)) }
                }
            } finally {
                statsJob?.cancel()
                UploadService.stop(context)
                _state.value = _state.value.copy(uploadRunning = false, cancelling = false, wasCancelled = _state.value.cancelling)
            }
        }
    }

    /**
     * Upload [files] through UploadManager, mirroring its progress into UI state + notification.
     * @param sourceFor null = files are local (phone, USB, or staged camera copies).
     * @return final per-file progress.
     */
    private suspend fun runUploads(
        files: List<DjiFile>,
        cfg: AppConfig,
        cred: Credential,
        sourceFor: ((DjiFile) -> MediaSource)? = null,
        deleteAfter: Boolean = cfg.deleteAfterUpload,
    ): Map<String, FileProgress> = coroutineScope {
        val mgr = UploadManager(
            context, cred,
            threads = cfg.uploadThreads,
            force = cfg.forceUpload,
            saver = cfg.saverMode,
            useQuota = cfg.useQuota,
            deleteAfter = deleteAfter,
            sourceFor = sourceFor ?: { it.defaultSource(context) },
        )
        uploadManager = mgr
        if (batchCancelled) mgr.cancel()
        val job = launch {
            var lastNotifyMs = 0L
            var lastNotifiedDone = -1
            mgr.progressFlow.collect { map ->
                val tracker = mgr.progress
                _state.value = _state.value.copy(
                    progressMap = map,
                    completed = tracker.completed,
                    failed = tracker.failed,
                    skipped = tracker.skipped,
                    rejected = tracker.rejected,
                    relocated = tracker.relocated,
                    deleted = tracker.deleted,
                )
                // Throttle notification updates: progress emits several times per second.
                val now = System.currentTimeMillis()
                if (tracker.doneCount != lastNotifiedDone || now - lastNotifyMs > 2000) {
                    lastNotifyMs = now
                    lastNotifiedDone = tracker.doneCount
                    val sent = map.values.sumOf { it.settledBytes }
                    UploadService.update(
                        context,
                        "${formatSize(sent)} of ${formatSize(tracker.totalBytes)} sent",
                        tracker.doneCount, tracker.totalFiles,
                    )
                }
            }
        }
        // Morphe-style live CPU/RAM/network while uploading
        statsJob?.cancel()
        statsJob = launch {
            val collector = SystemStatsCollector(
                context,
                uploadedBytesProvider = {
                    try { mgr.progress.files.values.sumOf { it.bytesUploaded } } catch (_: Exception) { 0L }
                }
            )
            collector.statsFlow().collect { s ->
                _state.value = _state.value.copy(systemStats = s)
            }
        }
        try {
            mgr.start(files)
        } finally {
            job.cancel()
            statsJob?.cancel()
        }
        mgr.progress.snapshot()
    }

    // ---- camera over WiFi ------------------------------------------------------------------------

    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun uploadFromCamera(files: List<DjiFile>, cfg: AppConfig, cred: Credential) {
        val link = cameraLink!!
        // Local copies from an earlier attempt: just upload them.
        if (files.all { it.file != null }) return finishStagedUpload(link, files, cfg, cred)
        val files = checkCameraSizes(link, files)
        val canStream = link.state.value is CameraState.Ready && link.hasInternet()
        if (cfg.cameraStream && canStream) {
            notice("Streaming from the camera to Google Photos")
            // Deleting on the camera over WiFi isn't implemented: never claim to have done it.
            runUploads(files, cfg, cred, sourceFor = { CameraHttpSource(link, it) { batchCancelled } }, deleteAfter = false)
            return
        }
        if (link.state.value !is CameraState.Ready) return notice("Camera disconnected — reconnect to download")
        if (cfg.cameraStream) notice("No internet while on the camera's WiFi — downloading to the phone first instead")
        val staged = stageFromCamera(link, files) ?: return
        if (batchCancelled || staged.isEmpty()) return
        finishStagedUpload(link, staged, cfg, cred)
    }

    /**
     * The manifest's u32 size of a video over 4 GB can wrap (8 GB lists as 4 GB less), which would download
     * a truncated clip: confirm each video's size with the camera's HTTP server before transferring.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun checkCameraSizes(link: CameraLink, files: List<DjiFile>): List<DjiFile> = withContext(Dispatchers.IO) {
        val videos = files.count { it.file == null && it.cameraPath != null && it.isVideo }
        if (videos == 0) return@withContext files
        notice("Checking video sizes on the camera…")
        val out = files.map { f ->
            if (f.file != null || f.cameraPath == null || !f.isVideo || batchCancelled) f
            else f.copy(sizeBytes = link.fileSize(PathAddressing.byPath(f.cameraStorage, f.cameraPath), f.sizeBytes))
        }
        notice(null)
        val byKey = out.associateBy { it.key }
        _state.update { st -> st.copy(djiFiles = st.djiFiles.map { byKey[it.key] ?: it }) }
        out
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun finishStagedUpload(link: CameraLink, staged: List<DjiFile>, cfg: AppConfig, cred: Credential) {
        // Leave the camera's WiFi so the phone can get back online (most phones can't do both).
        link.disconnect()
        notice("Downloaded ${staged.size} files. Waiting for internet…")
        if (!awaitInternet()) {
            notice("No internet after leaving the camera's WiFi. Downloaded files are kept — tap Upload when online.")
            return
        }
        notice(null)
        val result = runUploads(staged, cfg, cred, deleteAfter = false)
        // Free phone storage for everything that made it; keep failures for a retry.
        // Refused files would only be refused again, so their downloads go too.
        val done = staged.filter { result[it.key]?.status in setOf(UploadStatus.COMPLETED, UploadStatus.SKIPPED, UploadStatus.REJECTED) }
        stager(link).clean(done)
        val left = _state.value.djiFiles.filter { f -> done.none { it.key == f.key } }
        _state.update { it.copy(djiFiles = left, totalBytes = left.sumOf { f -> f.sizeBytes }) }
        if (left.isNotEmpty()) notice("${left.size} files not uploaded yet; downloaded copies are kept for a retry.")
    }

    private fun stagingDir() = File(context.getExternalFilesDir(null) ?: context.filesDir, "camera-staging")

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun stager(link: CameraLink) = CameraStager(link, stagingDir())

    /** Download every file; returns local copies of the ones that completed, or null if it couldn't start. */
    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun stageFromCamera(link: CameraLink, files: List<DjiFile>): List<DjiFile>? = withContext(Dispatchers.IO) {
        val stager = stager(link)
        val dir = stagingDir().apply { mkdirs() }
        val need = stager.bytesNeeded(files)
        val free = StatFs(dir.path).availableBytes
        if (need > free - FREE_SPACE_MARGIN) {
            notice("Not enough space on the phone: need ${formatSize(need)}, ${formatSize(free)} free. Pick fewer files or use stream mode.")
            return@withContext null
        }
        val tracker = ProgressTracker()
        files.forEach { tracker.addFile(it.key, it.sizeBytes, it.displayName) }
        fun emit() = _state.update { it.copy(progressMap = tracker.snapshot()) }
        emit()
        val staged = mutableListOf<DjiFile>()
        for ((i, f) in files.withIndex()) {
            if (batchCancelled) break
            val fp = tracker.get(f.key)
            fp.status = UploadStatus.DOWNLOADING
            notice("Downloading ${i + 1} of ${files.size} from the camera")
            UploadService.update(context, "Downloading ${f.displayName} from the camera", i, files.size)
            try {
                val local = stager.download(f, { batchCancelled }) { got ->
                    if (fp.trackUpload(got, f.sizeBytes)) emit()
                }
                fp.status = UploadStatus.QUEUED
                fp.message = "Downloaded"
                staged += f.copy(file = local)
            } catch (e: Exception) {
                fp.status = if (batchCancelled) UploadStatus.CANCELLED else UploadStatus.ERROR
                fp.message = "Download failed: ${e.message}"
                fp.error = e.message
                Log.w(CameraLink.TAG, "download failed ${f.displayName}: ${e.message}")
            }
            emit()
        }
        // Remember the local copies so a failed or cancelled upload can be retried without the camera.
        val stagedKeys = staged.associateBy { it.key }
        _state.update { st -> st.copy(djiFiles = st.djiFiles.map { stagedKeys[it.key] ?: it }) }
        staged
    }

    /** Wait (up to ~90 s) for a validated internet connection, e.g. home WiFi coming back. */
    private suspend fun awaitInternet(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        repeat(90) {
            if (batchCancelled) return false
            val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            if (caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return true
            delay(1000)
        }
        return false
    }

    private fun notice(msg: String?) = _state.update { it.copy(notice = msg) }

    /** Camera manifest record → list item. DJI names carry a local-time capture stamp. */
    private fun toDjiFile(f: CameraFile): DjiFile {
        val ms = runCatching {
            java.text.SimpleDateFormat("yyyyMMddHHmmss", java.util.Locale.US).parse(f.timestamp)?.time
        }.getOrNull() ?: 0L
        return DjiFile(displayName = f.name, sizeBytes = f.sizeBytes, lastModified = ms,
            cameraPath = f.path, cameraStorage = f.storage)
    }

    /** Requests cancellation; [HomeUiState.uploadRunning] clears once the workers have stopped. */
    fun cancelUpload() {
        if (!_state.value.uploadRunning) return
        batchCancelled = true
        uploadManager?.cancel()
        _state.value = _state.value.copy(cancelling = true)
    }

    override fun onCleared() {
        batchCancelled = true
        uploadManager?.cancel()
        cameraLink?.disconnect()
        UploadService.stop(context)
    }

    companion object {
        private const val FREE_SPACE_MARGIN = 200L * 1024 * 1024
        private const val DJI_FOLDERS = "dji_folders"
        private const val CLEANUP_FOLDERS = "cleanup_folders"
    }
}

private const val EXIF_READERS = 4
