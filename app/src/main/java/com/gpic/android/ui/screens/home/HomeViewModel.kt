package com.gpic.android.ui.screens.home

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.StatFs
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gpic.android.camera.CameraHttpSource
import com.gpic.android.camera.CameraLink
import com.gpic.android.camera.CameraStager
import com.gpic.android.camera.CameraState
import com.gpic.android.camera.osmosis.core.CameraFile
import com.gpic.android.data.auth.AppConfig
import com.gpic.android.data.auth.Credential
import com.gpic.android.data.auth.CredentialStore
import com.gpic.android.data.dji.DjiFile
import com.gpic.android.data.dji.DjiScanner
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

data class HomeUiState(
    val djiUri: Uri? = null,
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
    val totalFiles: Int = 0,
    val totalBytes: Long = 0L,
    val systemStats: LiveStats? = null,
    /** Where files come from: phone storage / USB camera, or a DJI camera over WiFi. */
    val source: Source = Source.DEVICE,
    val camera: CameraState = CameraState.Idle,
    /** Wireless transfers need Android 10+ (WifiNetworkSpecifier). */
    val cameraSupported: Boolean = false,
    /** One-line status from the camera transfer (downloading, waiting for internet, …). */
    val cameraNotice: String? = null,
)

enum class Source { DEVICE, CAMERA }

/** [context] must be the Application context: this ViewModel outlives Activity recreation. */
class HomeViewModel(
    private val context: Context,
    private val credentialStore: CredentialStore,
    private val scanner: DjiScanner,
) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private var uploadManager: UploadManager? = null
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
                            progressMap = emptyMap(), completed = 0, failed = 0, skipped = 0,
                        ) else st.copy(camera = cs)
                    }
                }
            }
        }
        viewModelScope.launch {
            credentialStore.configFlow.collect { c -> _state.update { it.copy(config = c) } }
        }
        refreshUsb()
        // restore last DJI selection if saved
        val prefs = context.getSharedPreferences("gpic_prefs", Context.MODE_PRIVATE)
        val saved = prefs.getString("last_dji_uri", null)
        val savedFiles = prefs.getStringSet("last_dji_files", null)?.toList()
        if (!savedFiles.isNullOrEmpty()) {
            try {
                val uris = savedFiles.map { Uri.parse(it) }
                // Re-resolve without blocking init; user can reselect if permissions expired.
                viewModelScope.launch { setDjiFileUris(uris, persist = false) }
            } catch (_: Exception) {}
        } else {
            saved?.let {
                try { _state.value = _state.value.copy(djiUri = Uri.parse(it)) } catch (e: Exception) {}
            }
        }
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

    fun setDjiUri(uri: Uri) {
        scanner.takePersistablePermission(uri)
        context.getSharedPreferences("gpic_prefs", Context.MODE_PRIVATE).edit()
            .putString("last_dji_uri", uri.toString())
            .remove("last_dji_files")
            .apply()
        _state.value = _state.value.copy(djiUri = uri, djiSource = "folder")
        scanDji(uri)
    }

    fun setDjiFileUris(picked: List<Uri>, persist: Boolean = true) {
        // Uris are the per-file progress/list key, so they must be unique.
        val uris = picked.distinct()
        if (uris.isEmpty()) return
        uris.forEach { scanner.takePersistableFilePermission(it) }
        if (persist) {
            context.getSharedPreferences("gpic_prefs", Context.MODE_PRIVATE).edit()
                .putStringSet("last_dji_files", uris.map { it.toString() }.toSet())
                .remove("last_dji_uri")
                .apply()
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(isScanning = true, scanError = null, djiSource = "files", djiUri = null)
            try {
                // filesFromUris already returns smallest-first sorted
                val files = scanner.filesFromUris(uris)
                _state.value = _state.value.copy(
                    djiFiles = files,
                    totalFiles = files.size,
                    totalBytes = files.sumOf { it.sizeBytes },
                    isScanning = false,
                    scanError = if (files.isEmpty()) "No supported photos/videos in selected files" else null
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(isScanning = false, scanError = e.message)
            }
        }
    }

    fun clearSelection() {
        context.getSharedPreferences("gpic_prefs", Context.MODE_PRIVATE).edit()
            .remove("last_dji_uri").remove("last_dji_files").apply()
        _state.value = _state.value.copy(djiUri = null, djiSource = "none", djiFiles = emptyList(), totalFiles = 0, totalBytes = 0, progressMap = emptyMap())
    }

    fun scanDji(uri: Uri) {
        viewModelScope.launch {
            _state.value = _state.value.copy(isScanning = true, scanError = null)
            try {
                val files = scanner.scanViaSaf(uri).sortedBy { it.sizeBytes }
                _state.value = _state.value.copy(
                    djiFiles = files,
                    totalFiles = files.size,
                    totalBytes = files.sumOf { it.sizeBytes },
                    isScanning = false
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(isScanning = false, scanError = e.message)
            }
        }
    }

    fun rescan() {
        if (_state.value.djiSource == "folder") _state.value.djiUri?.let { scanDji(it) }
    }

    fun selectSource(source: Source) {
        if (_state.value.uploadRunning || source == _state.value.source) return
        if (source == Source.DEVICE) cameraLink?.disconnect()
        _state.update {
            it.copy(source = source, djiFiles = emptyList(), totalFiles = 0, totalBytes = 0, progressMap = emptyMap(),
                completed = 0, failed = 0, skipped = 0, cameraNotice = null, djiSource = "none")
        }
    }

    fun startUpload() {
        if (_state.value.uploadRunning) return
        val files = _state.value.djiFiles.sortedBy { it.sizeBytes }
        if (files.isEmpty()) return
        val cred = credentialStore.getActiveCredential() ?: return
        val cfg = credentialStore.loadConfig()
        val fromCamera = files.any { it.cameraPath != null }
        viewModelScope.launch {
            batchCancelled = false
            _state.value = _state.value.copy(uploadRunning = true, cancelling = false, wasCancelled = false, progressMap = emptyMap(),
                completed = 0, failed = 0, skipped = 0, cameraNotice = null)
            UploadService.start(context)
            try {
                if (fromCamera && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) uploadFromCamera(files, cfg, cred)
                else runUploads(files, cfg, cred)
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
        val done = staged.filter { result[it.key]?.status in setOf(UploadStatus.COMPLETED, UploadStatus.SKIPPED) }
        stager(link).clean(done)
        val left = _state.value.djiFiles.filter { f -> done.none { it.key == f.key } }
        _state.update { it.copy(djiFiles = left, totalFiles = left.size, totalBytes = left.sumOf { f -> f.sizeBytes }) }
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

    private fun notice(msg: String?) = _state.update { it.copy(cameraNotice = msg) }

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
    }
}
