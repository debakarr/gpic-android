package com.gpic.android.ui.screens.editor

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import androidx.annotation.OptIn
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.gpic.android.data.auth.CredentialStore
import com.gpic.android.editor.AutoEdit
import com.gpic.android.editor.Beats
import com.gpic.android.editor.ClaudeEditPlanner
import com.gpic.android.editor.ClaudeException
import com.gpic.android.editor.ClipScores
import com.gpic.android.editor.CompositionBuilder
import com.gpic.android.editor.EditPlan
import com.gpic.android.editor.EditStyle
import com.gpic.android.editor.Encoders
import com.gpic.android.editor.Frame
import com.gpic.android.editor.LutInfo
import com.gpic.android.editor.LutStore
import com.gpic.android.editor.Motion
import com.gpic.android.editor.MusicTrack
import com.gpic.android.editor.OpenTrack
import com.gpic.android.editor.Openverse
import com.gpic.android.editor.ProjectStore
import com.gpic.android.editor.ProjectSummary
import com.gpic.android.editor.Segment
import com.gpic.android.editor.TextItem
import com.gpic.android.editor.Transition
import com.gpic.android.editor.Timeline
import com.gpic.android.editor.VideoExporter
import com.gpic.android.editor.VideoInfo
import com.gpic.android.editor.VideoProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** A suggested cover frame. */
data class CoverPick(val clip: Int, val timeMs: Long)

/** What's selected on the timeline. */
sealed interface Selection {
    data object None : Selection
    data class Part(val id: Long) : Selection
    data class Text(val id: Long) : Selection
    data object Music : Selection
}

/** The panel open above the toolbar. */
enum class Panel { SPEED, VOLUME, FILTERS, ADJUST, TRANSITION, MOTION, TEXT, MUSIC_LIBRARY, MUSIC, CANVAS, QUICK_EDIT, CLAUDE, EXPORT, CARDS }

data class EditorUiState(
    val projects: List<ProjectSummary> = emptyList(),
    val projectId: String? = null,
    val projectName: String = "",
    /** The clips (videos and photos) the parts come from, in the order they were added. */
    val clips: List<VideoInfo> = emptyList(),
    /** "Copying 2 of 3 from Google Photos…" etc. while clips are being added. */
    val loading: String? = null,
    /** "Making an edit copy of DJI_0012.MP4 · 40%" while proxies are made. */
    val preparing: String? = null,
    val plan: EditPlan = EditPlan(),
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val selection: Selection = Selection.None,
    val panel: Panel? = null,
    val luts: List<LutInfo> = emptyList(),
    /** DJI's LUT when imported, else GPic's approximation: what "Apply LUT" picks. */
    val defaultLutId: String = LutStore.VIVID_ID,
    val hasDjiLut: Boolean = false,
    // Claude
    val hasClaudeKey: Boolean = false,
    val request: String = "",
    val sendFrames: Boolean = false,
    val asking: Boolean = false,
    val claudeNote: String? = null,
    // Quick edit
    val style: EditStyle = EditStyle.TRAVEL,
    val targetSec: Int = 30,
    val autoProgress: String? = null,
    val autoNote: String? = null,
    val arrange: com.gpic.android.editor.Arrange = com.gpic.android.editor.Arrange.STORY,
    /** Smart edit adds intro/end title cards. */
    val addCards: Boolean = false,
    /** Suggested cover frames (clip, time), best first. */
    val covers: List<CoverPick> = emptyList(),
    // Music
    val musicQuery: String = "",
    val musicResults: List<OpenTrack> = emptyList(),
    val musicSearching: Boolean = false,
    val musicError: String? = null,
    val downloaded: List<OpenTrack> = emptyList(),
    /** Track id → download percent while downloading. */
    val downloading: Map<String, Int> = emptyMap(),
    val beatPace: Beats.Pace = Beats.Pace.NORMAL,
    val beatsBusy: Boolean = false,
    /** The music's level over the song (for the audio lane). */
    val waveform: FloatArray? = null,
    // Export
    val maxShortSide: Int = 1080,
    val exportProgress: Int? = null,
    val exported: Uri? = null,
    /** Credits to post with the exported video (Creative Commons music). */
    val exportedCredits: String? = null,
    val message: String? = null,
) {
    val video: VideoInfo? get() = clips.firstOrNull()
    val durations: List<Long> get() = clips.map { it.durationMs }
    val parts: List<Segment> get() = plan.parts(durations)
    val outputMs: Long get() = plan.outputMs(durations)
    val selectedPart: Segment? get() = (selection as? Selection.Part)?.let { s -> plan.segments.firstOrNull { it.id == s.id } }
    val selectedText: TextItem? get() = (selection as? Selection.Text)?.let { s -> plan.texts.firstOrNull { it.id == s.id } }
}

/** [context] must be the Application context. */
@OptIn(UnstableApi::class)
@kotlin.OptIn(FlowPreview::class)
class EditorViewModel(private val context: Context, private val store: CredentialStore) : ViewModel() {
    private val luts = LutStore(context)
    private val exporter = VideoExporter(context, luts)
    private val previewBuilder = CompositionBuilder(luts)
    private val projects = ProjectStore(context)
    private val openverse = Openverse(context)
    private val _state = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    /** The preview's composition: rebuilt (small, without text) whenever the picture or sound changes. */
    private val _preview = MutableStateFlow<CompositionBuilder.Output?>(null)
    val preview: StateFlow<CompositionBuilder.Output?> = _preview.asStateFlow()

    /** Timeline thumbnails: clip index → frames, one per [THUMB_STEP_MS] (filled in as they're read). */
    private val _thumbs = MutableStateFlow<Map<Int, List<Bitmap?>>>(emptyMap())
    val thumbs: StateFlow<Map<Int, List<Bitmap?>>> = _thumbs.asStateFlow()

    private val undo = ArrayDeque<EditPlan>()
    private val redo = ArrayDeque<EditPlan>()
    private var exportJob: Job? = null
    private var autoJob: Job? = null
    private var thumbJob: Job? = null
    private var saveJob: Job? = null

    init {
        refreshLuts()
        _state.update {
            it.copy(hasClaudeKey = store.getClaudeApiKey() != null, projects = projects.list(),
                downloaded = runCatching { openverse.downloaded().map { d -> d.first } }.getOrDefault(emptyList()))
        }
        viewModelScope.launch(Dispatchers.Default) { val m = Encoders.maxShortSide(); _state.update { it.copy(maxShortSide = m) } }
        viewModelScope.launch {
            _state.map { st -> Triple(st.clips, st.plan.copy(texts = emptyList(), maxHeight = 0, fps = 0), st.luts.size to (st.preparing != null)) }
                .distinctUntilChanged()
                .debounce(120)
                .collect { (clips, plan, extra) ->
                    // No preview while edit copies are made: old phones can't decode twice at once.
                    if (clips.isEmpty() || extra.second) { _preview.value = null; return@collect }
                    _preview.value = withContext(Dispatchers.Default) {
                        runCatching { previewBuilder.build(clips, plan, PREVIEW_SHORT_SIDE, withText = false, useProxies = true) }
                            .onFailure { Log.w(TAG, "preview build failed", it) }.getOrNull()
                    }
                }
        }
    }

    private fun refreshLuts() = _state.update {
        it.copy(luts = luts.list(), defaultLutId = luts.defaultId, hasDjiLut = luts.hasDjiLut)
    }

    fun messageShown() = _state.update { it.copy(message = null) }
    fun toast(msg: String) = _state.update { it.copy(message = msg) }

    // ---- projects -------------------------------------------------------------------------------------------

    fun refreshProjects() = _state.update { it.copy(projects = projects.list()) }

    /** Back to the project list (the current edit is already saved). */
    fun closeProject() {
        proxyJob?.cancel()
        saveNow()
        thumbJob?.cancel()
        undo.clear(); redo.clear()
        _thumbs.value = emptyMap()
        _state.update {
            EditorUiState(
                projects = projects.list(), luts = it.luts, defaultLutId = it.defaultLutId, hasDjiLut = it.hasDjiLut,
                hasClaudeKey = it.hasClaudeKey, downloaded = it.downloaded, maxShortSide = it.maxShortSide,
                style = it.style, targetSec = it.targetSec,
            )
        }
    }

    fun openProject(id: String) {
        val p = projects.load(id) ?: return toast("Couldn't open that project")
        _state.update { it.copy(loading = "Opening ${p.name}…") }
        viewModelScope.launch {
            val (clips, missing) = withContext(Dispatchers.IO) {
                val ok = mutableListOf<VideoInfo>()
                var bad = 0
                for (c in p.clips) {
                    runCatching {
                        VideoProbe.probe(context, c.uri).copy(name = c.name.ifEmpty { c.uri.lastPathSegment ?: "clip" }, proxy = c.proxy, proxyDurationMs = c.proxyMs)
                    }.onSuccess { ok += it }.onFailure { bad++ }
                }
                ok to bad
            }
            if (missing > 0 || clips.isEmpty()) {
                _state.update { it.copy(loading = null, message = "Some clips of this project are gone (deleted, or access ended). Start a new one.") }
                if (clips.isEmpty()) return@launch
            }
            undo.clear(); redo.clear()
            _state.update {
                it.copy(projectId = id, projectName = p.name, clips = clips, loading = null, selection = Selection.None, panel = null,
                    preparing = if (clips.any { c -> c.proxy == null && com.gpic.android.editor.Proxies.needed(c) }) "Preparing edit copies…" else null,
                    plan = p.plan.sanitized(clips.map { c -> c.durationMs }, it.luts.mapTo(HashSet()) { l -> l.id }),
                    canUndo = false, canRedo = false, exported = null, exportedCredits = null)
            }
            _state.value.plan.music?.let { loadWaveform(Uri.parse(it.uri)) }
            ensureProxies()
        }
    }

    private var proxyJob: Job? = null

    /**
     * Makes the missing edit copies, one clip at a time (old phones have few hardware decoders, so nothing else
     * decodes meanwhile: the workspace waits behind a progress screen that can be skipped). Thumbnails follow.
     */
    fun ensureProxies() {
        val id = _state.value.projectId ?: return
        if (proxyJob?.isActive == true) return
        proxyJob = viewModelScope.launch {
            val todo = _state.value.clips.withIndex().filter { (_, c) -> c.proxy == null && com.gpic.android.editor.Proxies.needed(c) }
            for ((n, ic) in todo.withIndex()) {
                val (i, c) = ic
                _state.update { it.copy(preparing = "Making an edit copy of ${c.name} (${n + 1} of ${todo.size})…") }
                val dir = File(projects.mediaDir(id), "proxies").apply { mkdirs() }
                val out = File(dir, "${System.nanoTime()}.mp4")
                val r = runCatching {
                    com.gpic.android.editor.Proxies.make(context, c, out) { p ->
                        _state.update { it.copy(preparing = "Making an edit copy of ${c.name} (${n + 1} of ${todo.size}) · $p%") }
                    }
                }
                if (r.isFailure) {
                    if (r.exceptionOrNull() is kotlinx.coroutines.CancellationException) break
                    Log.w(TAG, "edit copy failed for ${c.name}; editing the original", r.exceptionOrNull())
                    continue
                }
                val proxyUri = r.getOrThrow()
                val ms = withContext(Dispatchers.IO) { probeDuration(proxyUri) }
                _state.update { st -> st.copy(clips = st.clips.mapIndexed { k, v -> if (k == i && v.uri == c.uri) v.copy(proxy = proxyUri, proxyDurationMs = ms) else v }) }
                Log.i(TAG, "edit copy of ${c.name}: ${out.length() / 1024} KB, ${ms} ms (original ${c.durationMs} ms)")
            }
            _state.update { it.copy(preparing = null) }
            withContext(Dispatchers.IO) { saveNow() }
            _thumbs.value = emptyMap()
            loadThumbs()
        }
    }

    /** Skip the edit copies still to be made (the originals are edited directly). */
    fun skipProxies() {
        proxyJob?.cancel()
        _state.update { it.copy(preparing = null) }
        loadThumbs()
    }

    fun deleteProject(id: String) {
        // Give back read grants on picked clips no other project uses (the project's own copies go with it).
        val mine = projects.load(id)?.clips?.map { it.uri }.orEmpty()
        val others = projects.list().filter { it.id != id }.flatMap { p -> projects.load(p.id)?.clips?.map { it.uri }.orEmpty() }.toSet()
        mine.filter { it.scheme == "content" && it !in others }.forEach { u ->
            runCatching { context.contentResolver.releasePersistableUriPermission(u, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        projects.delete(id)
        refreshProjects()
    }

    fun renameProject(name: String) {
        val id = _state.value.projectId ?: return
        _state.update { it.copy(projectName = name.take(60)) }
        projects.rename(id, name.take(60))
        scheduleSave()
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            kotlinx.coroutines.delay(600)
            withContext(Dispatchers.IO) { saveNow() }
        }
    }

    private fun saveNow() {
        val st = _state.value
        val id = st.projectId ?: return
        if (st.clips.isEmpty()) return
        runCatching { projects.save(id, st.projectName, st.clips, st.plan, st.outputMs) }.onFailure { Log.w(TAG, "save failed", it) }
    }

    // ---- clips ----------------------------------------------------------------------------------------------

    /** Start a new project with these clips (videos and photos, from Google Photos or the phone). */
    fun openVideos(uris: List<Uri>) = addVideos(uris, replace = true)

    fun addVideos(uris: List<Uri>, replace: Boolean = false) {
        if (uris.isEmpty()) return
        if (replace) saveNow()
        val id = if (replace || _state.value.projectId == null) projects.newId() else _state.value.projectId!!
        _state.update { it.copy(loading = "Opening ${plural(uris.size, "clip")}…", exported = null, claudeNote = null) }
        viewModelScope.launch {
            val (added, failed) = withContext(Dispatchers.IO) {
                val ok = mutableListOf<VideoInfo>()
                var bad = 0
                for ((i, uri) in uris.withIndex()) {
                    runCatching {
                        val local = if (keepsAccess(uri)) uri else copyIn(id, uri, i + 1, uris.size)
                        VideoProbe.probe(context, local).let { v -> if (local != uri) v.copy(name = displayName(uri) ?: v.name) else v }
                            .also { v -> require(v.isImage || v.durationMs >= EditPlan.MIN_SEGMENT_MS * 2) { "${v.name} is too short to edit" } }
                    }.onSuccess { ok += it }.onFailure { Log.w(TAG, "open failed: ${it.message}"); bad++ }
                }
                ok to bad
            }
            if (added.isEmpty()) {
                _state.update { it.copy(loading = null, message = "Couldn't use ${plural(failed, "clip")} (unreadable, or shorter than a second)") }
                return@launch
            }
            _state.update { st ->
                val base = if (replace) emptyList() else st.clips
                val clips = base + added
                val firstNew = base.size
                // A flat-looking DJI clip is probably D-Log M: start with the LUT on. Anything else starts untouched.
                val dlog = replace && added.any { it.fromDji && it.looksFlat == true }
                val old = if (replace) EditPlan(look = com.gpic.android.editor.Look(lutId = if (dlog) luts.defaultId else null))
                else Timeline.materialize(st.plan, st.durations)
                val newParts = added.mapIndexed { k, v ->
                    if (v.isImage) Segment(0, VideoProbe.IMAGE_DEFAULT_MS, firstNew + k, motion = Motion.ZOOM_IN)
                    else Segment(0, v.durationMs, firstNew + k)
                }
                val plan = old.copy(segments = old.segments + newParts, keepOriginalAudio = clips.any { it.hasAudio })
                    .sanitized(clips.map { it.durationMs }, st.luts.mapTo(HashSet()) { it.id })
                if (!replace) { undo.addLast(st.plan); redo.clear() }
                st.copy(
                    projectId = id,
                    projectName = if (replace || st.projectName.isEmpty()) defaultName(clips) else st.projectName,
                    clips = clips, loading = null, plan = plan, selection = Selection.None, panel = null,
                    // Set together with the clips, so the preview doesn't start decoding first.
                    preparing = if (clips.any { it.proxy == null && com.gpic.android.editor.Proxies.needed(it) }) "Preparing edit copies…" else null,
                    canUndo = undo.isNotEmpty(), canRedo = false,
                    message = when {
                        failed > 0 -> "Couldn't use ${plural(failed, "clip")} (unreadable, or shorter than a second)"
                        dlog -> "Looks like D-Log M footage, so ${st.luts.firstOrNull { it.id == luts.defaultId }?.title ?: "the LUT"} is on. Turn it off under Filters if it isn't."
                        else -> null
                    },
                )
            }
            if (replace) { undo.clear(); redo.clear(); _state.update { it.copy(canUndo = false) } }
            withContext(Dispatchers.IO) {
                saveNow()
                VideoProbe.thumbnail(context, _state.value.clips.first(), 1000, 320)?.let { projects.saveCover(id, it) }
            }
            refreshProjects()
            if (_state.value.clips.any { it.proxy == null && com.gpic.android.editor.Proxies.needed(it) }) ensureProxies() else loadThumbs()
        }
    }

    private fun defaultName(clips: List<VideoInfo>): String =
        java.text.SimpleDateFormat("d MMM, HH:mm", java.util.Locale.getDefault()).format(java.util.Date()) +
            if (clips.size > 1) " · ${clips.size} clips" else ""

    /** Media from the phone (MediaStore, SAF on local storage) stays readable; anything else is copied in first. */
    private fun keepsAccess(uri: Uri): Boolean {
        if (uri.scheme == "file") return true
        if (uri.authority !in setOf(MediaStore.AUTHORITY, "com.android.externalstorage.documents", "com.android.providers.media.documents")) return false
        if (uri.authority == MediaStore.AUTHORITY) return true
        // Picked documents: keep the grant across restarts so the draft reopens.
        return runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION); true }.getOrDefault(false)
    }

    private fun copyIn(projectId: String, uri: Uri, n: Int, of: Int): Uri {
        val dir = projects.mediaDir(projectId)
        val name = displayName(uri) ?: "clip"
        val out = File(dir, "${System.nanoTime()}-${name.replace(Regex("[^A-Za-z0-9._-]"), "_")}")
        val size = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
        }.getOrNull() ?: 0L
        if (size > 0 && dir.usableSpace < size + 200L * 1024 * 1024) throw IllegalStateException("Not enough free space to copy $name")
        context.contentResolver.openInputStream(uri)?.use { input ->
            out.outputStream().use { o ->
                val buf = ByteArray(256 * 1024)
                var done = 0L
                var lastPct = -1
                while (true) {
                    val r = input.read(buf)
                    if (r == -1) break
                    o.write(buf, 0, r)
                    done += r
                    val pct = if (size > 0) (done * 100 / size).toInt() else -1
                    if (pct != lastPct) {
                        lastPct = pct
                        _state.update { it.copy(loading = "Copying clip $n of $of from Google Photos" + if (pct >= 0) " ($pct%)…" else "…") }
                    }
                }
            }
        } ?: throw IllegalStateException("Couldn't read $name")
        return Uri.fromFile(out)
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    private fun loadThumbs() {
        thumbJob?.cancel()
        val clips = _state.value.clips
        thumbJob = viewModelScope.launch(Dispatchers.IO) {
            val map = _thumbs.value.toMutableMap()
            for ((i, c) in clips.withIndex()) {
                if (map[i]?.size == thumbCount(c) && map[i]?.all { it != null } == true) continue
                val n = thumbCount(c)
                val list = MutableList<Bitmap?>(n) { null }
                if (c.isImage) {
                    val b = VideoProbe.thumbnail(context, c, 0, THUMB_PX)
                    for (k in 0 until n) list[k] = b
                    map[i] = list.toList(); _thumbs.value = map.toMap()
                    continue
                }
                val r = android.media.MediaMetadataRetriever()
                try {
                    r.setDataSource(context, c.editUri)
                    for (k in 0 until n) {
                        val t = (k * thumbStepMs(c) + thumbStepMs(c) / 2).coerceAtMost(c.durationMs - 1)
                        list[k] = runCatching {
                            val w = (c.width * THUMB_PX / maxOf(c.width, c.height, 1)).coerceAtLeast(1)
                            val h = (c.height * THUMB_PX / maxOf(c.width, c.height, 1)).coerceAtLeast(1)
                            if (android.os.Build.VERSION.SDK_INT >= 27) r.getScaledFrameAtTime(t * 1000, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, w, h)
                            else r.getFrameAtTime(t * 1000)?.let { Bitmap.createScaledBitmap(it, w, h, true) }
                        }.getOrNull()
                        // Publish every few frames so the strip fills in as it loads.
                        if (k % 4 == 3 || k == n - 1) { map[i] = list.toList(); _thumbs.value = map.toMap() }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "thumbnails failed for ${c.name}: ${e.message}")
                } finally {
                    r.release()
                }
            }
        }
    }

    private fun thumbCount(c: VideoInfo) = if (c.isImage) 1 else ((c.durationMs + thumbStepMs(c) - 1) / thumbStepMs(c)).toInt().coerceIn(1, MAX_THUMBS)
    fun thumbStepMs(c: VideoInfo) = if (c.isImage) c.durationMs else maxOf(THUMB_STEP_MS, (c.durationMs + MAX_THUMBS - 1) / MAX_THUMBS)

    // ---- editing --------------------------------------------------------------------------------------------

    /** Applies [change] as one undoable step. */
    fun commit(change: (EditPlan) -> EditPlan) {
        _state.update { st ->
            if (st.clips.isEmpty()) return@update st
            val next = change(st.plan).sanitized(st.durations, st.luts.mapTo(HashSet()) { it.id })
            if (next == st.plan) return@update st
            undo.addLast(st.plan); if (undo.size > MAX_UNDO) undo.removeFirst()
            redo.clear()
            st.copy(plan = next, canUndo = true, canRedo = false, exported = null)
        }
        scheduleSave()
    }

    fun undo() = _state.update { st ->
        val prev = undo.removeLastOrNull() ?: return@update st
        redo.addLast(st.plan)
        st.copy(plan = prev, canUndo = undo.isNotEmpty(), canRedo = true, exported = null).fixSelection()
    }.also { scheduleSave() }

    fun redo() = _state.update { st ->
        val next = redo.removeLastOrNull() ?: return@update st
        undo.addLast(st.plan)
        st.copy(plan = next, canUndo = true, canRedo = redo.isNotEmpty(), exported = null).fixSelection()
    }.also { scheduleSave() }

    private fun EditorUiState.fixSelection(): EditorUiState = when (val s = selection) {
        is Selection.Part -> if (plan.segments.any { it.id == s.id }) this else copy(selection = Selection.None, panel = null)
        is Selection.Text -> if (plan.texts.any { it.id == s.id }) this else copy(selection = Selection.None, panel = null)
        Selection.Music -> if (plan.music != null) this else copy(selection = Selection.None, panel = null)
        Selection.None -> this
    }

    fun select(s: Selection) = _state.update { it.copy(selection = s, panel = if (s is Selection.Text) it.panel?.takeIf { p -> p == Panel.TEXT } else null) }
    fun openPanel(p: Panel?) = _state.update { it.copy(panel = p) }

    fun splitAt(outMs: Long) {
        val st = _state.value
        val r = Timeline.split(st.plan, st.durations, outMs) ?: return toast("Move the playhead inside a clip, away from its ends")
        commit { r.first }
        _state.update { it.copy(selection = Selection.Part(r.second)) }
    }

    fun deletePart(id: Long) {
        if (_state.value.parts.size <= 1) return toast("A video needs at least one clip")
        commit { Timeline.delete(it, _state.value.durations, id) }
        _state.update { it.copy(selection = Selection.None, panel = null) }
    }

    fun duplicatePart(id: Long) {
        val r = Timeline.duplicate(_state.value.plan, _state.value.durations, id)
        commit { r.first }
        _state.update { it.copy(selection = Selection.Part(r.second)) }
    }

    fun movePart(id: Long, to: Int) = commit { Timeline.move(it, _state.value.durations, id, to) }
    fun trimPart(id: Long, startMs: Long?, endMs: Long?) = commit { Timeline.trim(it, _state.value.durations, id, startMs, endMs) }
    fun updatePart(id: Long, change: (Segment) -> Segment) = commit { p -> p.copy(segments = p.segments.map { if (it.id == id) change(it) else it }) }
    fun updateAllParts(change: (Segment) -> Segment) = commit { p -> Timeline.materialize(p, _state.value.durations).let { m -> m.copy(segments = m.segments.map(change)) } }

    fun addText(atMs: Long, sticker: String? = null) {
        val st = _state.value
        val end = minOf(st.outputMs, atMs + if (sticker != null) 2500 else 3000)
        val start = minOf(atMs, maxOf(0, end - 1000))
        val item = if (sticker != null) TextItem(sticker, start, end, x = 0.5f, y = 0.4f, size = 0.18f, style = com.gpic.android.editor.TextStyle.PLAIN,
            animIn = com.gpic.android.editor.TextAnim.POP, animOut = com.gpic.android.editor.TextAnim.FADE, sticker = true)
        else TextItem("Your text", start, end, y = 0.5f)
        commit { it.copy(texts = it.texts + item) }
        _state.update { it.copy(selection = Selection.Text(item.id), panel = if (sticker == null) Panel.TEXT else null) }
    }

    fun updateText(id: Long, change: (TextItem) -> TextItem) = commit { p -> p.copy(texts = p.texts.map { if (it.id == id) change(it) else it }) }

    fun deleteText(id: Long) {
        commit { p -> p.copy(texts = p.texts.filter { it.id != id }) }
        _state.update { it.copy(selection = Selection.None, panel = null) }
    }

    fun duplicateText(id: Long) {
        val t = _state.value.plan.texts.firstOrNull { it.id == id } ?: return
        val copy = t.copy(id = com.gpic.android.editor.newId(), y = (t.y + 0.08f).coerceAtMost(0.95f))
        commit { it.copy(texts = it.texts + copy) }
        _state.update { it.copy(selection = Selection.Text(copy.id)) }
    }

    // ---- music ----------------------------------------------------------------------------------------------

    fun setMusicQuery(q: String) = _state.update { it.copy(musicQuery = q) }

    fun searchMusic(query: String = _state.value.musicQuery) {
        if (query.isBlank() || _state.value.musicSearching) return
        _state.update { it.copy(musicQuery = query, musicSearching = true, musicError = null) }
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { openverse.search(query) } }
            _state.update { st ->
                r.fold(
                    { st.copy(musicSearching = false, musicResults = it, musicError = if (it.isEmpty()) "No music found for “$query”" else null) },
                    { e -> st.copy(musicSearching = false, musicError = if (e is Openverse.QuotaException) e.message else "Search failed: ${e.message ?: "no connection"}") },
                )
            }
        }
    }

    /** Downloads (once) and uses an Openverse track, then finds its beats. */
    fun useTrack(t: OpenTrack) {
        if (t.id in _state.value.downloading) return
        _state.update { it.copy(downloading = it.downloading + (t.id to 0)) }
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { openverse.download(t) { p -> _state.update { it.copy(downloading = it.downloading + (t.id to p)) } } }
            }
            _state.update { it.copy(downloading = it.downloading - t.id) }
            r.onSuccess { f ->
                _state.update { it.copy(downloaded = openverse.downloaded().map { d -> d.first }) }
                setMusic(Uri.fromFile(f), t.title + " · " + t.creator, if (t.needsCredit) t.attribution else null, t.durationMs)
            }.onFailure { e -> toast("Couldn't download the track: ${e.message}") }
        }
    }

    /** A song from the phone, or a clip's own sound ([fromVideo]): copied into the project so the draft keeps it. */
    fun useLocalAudio(uri: Uri, fromVideo: Boolean = false) {
        val id = _state.value.projectId ?: return
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val local = if (uri.scheme == "file") uri else copyAudio(id, uri)
                    val dur = runCatching {
                        android.media.MediaMetadataRetriever().let { mr ->
                            try { mr.setDataSource(context, local); mr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() } finally { mr.release() }
                        }
                    }.getOrNull() ?: 0L
                    local to dur
                }
            }
            r.onSuccess { (local, dur) ->
                setMusic(local, (displayName(uri) ?: "Music").substringBeforeLast('.') + if (fromVideo) " (sound)" else "", null, dur)
            }.onFailure { e -> toast("Couldn't use that audio: ${e.message}") }
        }
    }

    private fun copyAudio(projectId: String, uri: Uri): Uri {
        val name = displayName(uri) ?: "audio"
        val out = File(projects.mediaDir(projectId), "${System.nanoTime()}-${name.replace(Regex("[^A-Za-z0-9._-]"), "_")}")
        context.contentResolver.openInputStream(uri)?.use { i -> out.outputStream().use { i.copyTo(it) } } ?: throw IllegalStateException("Couldn't read $name")
        return Uri.fromFile(out)
    }

    private fun setMusic(uri: Uri, title: String, credit: String?, listedMs: Long) {
        // The file's own length (Openverse's listing can be missing or rounded); music without one can't be mixed.
        val durationMs = probeDuration(uri).takeIf { it > 0 } ?: listedMs
        if (durationMs <= 0) return toast("Couldn't read that audio file's length")
        commit { p ->
            val keep = p.music
            p.copy(music = MusicTrack(uri.toString(), title, credit, durationMs, volume = keep?.volume ?: 0.8f,
                fadeInMs = keep?.fadeInMs ?: 0, fadeOutMs = keep?.fadeOutMs ?: 1500))
        }
        _state.update { it.copy(selection = Selection.Music, panel = Panel.MUSIC, waveform = null) }
        detectBeats()
        loadWaveform(uri)
    }

    private fun probeDuration(uri: Uri): Long = runCatching {
        android.media.MediaMetadataRetriever().let { mr ->
            try { mr.setDataSource(context, uri); mr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() } finally { mr.release() }
        }
    }.getOrNull() ?: 0L

    fun removeMusic() {
        commit { it.copy(music = null) }
        _state.update { it.copy(selection = Selection.None, panel = null, waveform = null) }
    }

    fun updateMusic(change: (MusicTrack) -> MusicTrack) = commit { p -> p.copy(music = p.music?.let(change)) }

    private fun loadWaveform(uri: Uri) {
        viewModelScope.launch(Dispatchers.Default) {
            val pcm = Beats.decodeMono(context, uri) ?: return@launch
            val w = Beats.waveform(pcm, (pcm.size / (Beats.RATE / 10)).coerceIn(1, 6000)) // 10 per second
            _state.update { it.copy(waveform = w) }
        }
    }

    /** Finds the song's beats (on the phone) for markers, Quick edit and "cut on beats". */
    fun detectBeats() {
        val m = _state.value.plan.music ?: return
        if (_state.value.beatsBusy) return
        _state.update { it.copy(beatsBusy = true) }
        viewModelScope.launch {
            val info = withContext(Dispatchers.Default) {
                Beats.decodeMono(context, Uri.parse(m.uri))?.let { Beats.detect(it) }
            }
            _state.update { it.copy(beatsBusy = false) }
            if (info == null || info.beatsMs.size < 4) return@launch toast("Couldn't find a steady beat in this track")
            // Not an undo step: beats are facts about the song, not an edit.
            _state.update { st -> st.copy(plan = st.plan.copy(music = st.plan.music?.takeIf { it.uri == m.uri }?.copy(beatsMs = info.beatsMs, bpm = info.bpm))) }
            scheduleSave()
        }
    }

    fun setBeatPace(p: Beats.Pace) = _state.update { it.copy(beatPace = p) }

    /** Beat markers in output time at the chosen pace. */
    fun beatMarkers(st: EditorUiState = _state.value): List<Long> {
        val m = st.plan.music ?: return emptyList()
        return Beats.markers(m.beatsMs, st.beatPace, m.startMs, st.outputMs)
    }

    fun snapToBeats() {
        val marks = beatMarkers()
        if (marks.size < 2) return toast("Find the beats first (Audio → the track → Beats)")
        commit { Timeline.snapToBeats(it, _state.value.durations, marks) }
        toast("Cuts moved onto the beat")
    }

    // ---- looks / LUTs ---------------------------------------------------------------------------------------

    fun importLut(uri: Uri, asDji: Boolean) {
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { luts.import(uri, asDji) } }
            refreshLuts()
            r.onSuccess { info ->
                commit { it.copy(look = it.look.copy(lutId = info.id)) }
                toast("Added “${info.title}”" + if (asDji) ". It's now the default for D-Log M clips." else "")
            }.onFailure { e -> toast("Couldn't use that LUT: ${e.message}") }
        }
    }

    fun deleteLut(id: String) {
        luts.delete(id)
        refreshLuts()
        commit { p -> p.copy(look = if (p.look.lutId == id) p.look.copy(lutId = null) else p.look) }
    }

    /** A small frame of the selected part (or the first) for the filter thumbnails. */
    suspend fun sampleFrame(): Bitmap? = withContext(Dispatchers.IO) {
        val st = _state.value
        val part = st.selectedPart ?: st.parts.firstOrNull() ?: return@withContext null
        val clip = st.clips.getOrNull(part.clip) ?: return@withContext null
        VideoProbe.thumbnail(context, clip, (part.startMs + part.endMs) / 2, 96)
    }

    fun loadLut(id: String) = runCatching { luts.load(id) }.getOrNull()

    // ---- Quick edit -----------------------------------------------------------------------------------------

    fun setStyle(s: EditStyle) = _state.update { it.copy(style = s) }
    fun setTarget(sec: Int) = _state.update { it.copy(targetSec = sec) }

    /**
     * LightCut-style one tap: score every clip for motion and colour on the phone, keep the liveliest moments
     * for the chosen length and style (cut on the music's beat when it has one), and apply the style.
     */
    fun setArrange(a: com.gpic.android.editor.Arrange) = _state.update { it.copy(arrange = a) }
    fun setAddCards(on: Boolean) = _state.update { it.copy(addCards = on) }

    /** Generated intro/end cards are photo clips GPic made; they're not footage to pick shots from. */
    private fun isCard(v: VideoInfo) = v.name.startsWith(CARD_PREFIX)

    /** Measures every clip (once per session; from the edit copy when there is one), reporting progress. */
    private suspend fun analyzeAll(label: String): List<com.gpic.android.editor.ClipAnalysis> = withContext(Dispatchers.IO) {
        val clips = _state.value.clips
        clips.withIndex().filter { !isCard(it.value) }.map { (i, v) ->
            com.gpic.android.editor.VideoAnalyzer.analyze(context, i, v) { f ->
                val pct = ((i + f) * 100 / clips.size).toInt()
                _state.update { it.copy(autoProgress = "$label $pct%") }
            }
        }
    }

    /**
     * LightCut-style one tap, on the phone: split every clip into shots, score them (sharpness, exposure, colour,
     * action, faces; shaky/blurry/dark ones last), skip near-duplicates, and fill beat-length slots with the best
     * windows, slow motion for high-frame-rate shots and transitions that follow the camera's motion.
     */
    fun quickEdit() {
        val st = _state.value
        if (st.clips.isEmpty() || autoJob?.isActive == true) return
        autoJob = viewModelScope.launch {
            _state.update { it.copy(autoProgress = "Finding shots…", autoNote = null) }
            val analyses = runCatching { analyzeAll("Finding shots…") }.getOrElse { e ->
                Log.w(TAG, "analysis failed", e)
                _state.update { it.copy(autoProgress = null, autoNote = "Couldn't read the clips: ${e.message}") }
                return@launch
            }
            updateCovers(analyses)
            val music = st.plan.music
            val cut = AutoEdit.beatCutMs(st.style, music?.bpm ?: 0f)
            val result = com.gpic.android.editor.SmartEdit.pick(analyses, st.targetSec * 1000L, cut, st.arrange, st.style)
            if (result.parts.isEmpty()) {
                _state.update { it.copy(autoProgress = null, autoNote = "These clips are too short for a smart edit.") }
                return@launch
            }
            _state.update { it.copy(autoProgress = null) }
            commit { p ->
                var plan = AutoEdit.plan(p, result.parts, st.style)
                // Keep the shots' own transitions (they follow the camera) and slow motion; still shots get the style's move.
                plan = plan.copy(segments = result.parts.mapIndexed { i, s ->
                    plan.segments[i].copy(transition = s.transition, speed = s.speed, motion = if (s.transition in setOf(Transition.SLIDE_LEFT, Transition.SLIDE_RIGHT, Transition.SLIDE_UP, Transition.SLIDE_DOWN)) com.gpic.android.editor.Motion.NONE else st.style.motion)
                })
                val m = plan.music
                if (m != null && m.beatsMs.size > 4) {
                    plan = Timeline.snapToBeats(plan, st.durations, Beats.markers(m.beatsMs, Beats.Pace.FAST, m.startMs, plan.outputMs(st.durations) + 60_000))
                }
                plan
            }
            if (st.addCards) addCards(intro = true, outro = true, oneStep = false)
            val cur = _state.value
            val out = cur.outputMs / 1000
            val found = buildList {
                if (result.shaky > 0) add("${result.shaky} shaky")
                if (result.blurry > 0) add("${result.blurry} blurry")
                if (result.duplicates > 0) add("${result.duplicates} near-duplicate")
            }
            _state.update {
                it.copy(selection = Selection.None, autoNote = "${cur.style.label}: ${plural(result.shots, "shot")} found" +
                    (if (found.isNotEmpty()) " (${found.joinToString()})" else "") + ", used ${result.used}" +
                    (if (result.slowMo > 0) ", ${result.slowMo} in slow motion" else "") + ". ${out / 60}:${"%02d".format(out % 60)} long" + when {
                        music == null -> ". Add music under Audio, then run it again to cut on the beat."
                        music.beatsMs.isEmpty() -> "."
                        else -> ", cut on the beat (${music.bpm.toInt()} BPM)."
                    })
            }
        }
    }

    // ---- generated frames: covers and cards ------------------------------------------------------------------

    /** Best-looking frames (sharp, well exposed, colourful, faces), one per near-duplicate group. */
    private fun updateCovers(analyses: List<com.gpic.android.editor.ClipAnalysis>) {
        val picks = analyses.flatMap { a ->
            val ref = a.frames.map { it.sharpness }.sorted().let { if (it.isEmpty()) 1f else it[(it.size * 0.9).toInt().coerceAtMost(it.size - 1)] }.coerceAtLeast(1e-6f)
            a.frames.map { f ->
                val score = 0.4f * minOf(1f, f.sharpness / ref) + 0.25f * (1f - minOf(1f, kotlin.math.abs(f.luma - 0.5f) * 2.2f)) +
                    0.2f * f.colorful + (if (f.faces > 0) 0.25f + minOf(0.2f, f.faceSize) else 0f) - 0.3f * minOf(1f, kotlin.math.hypot(f.dx, f.dy) * 10)
                Triple(a.clip, f, score)
            }
        }.sortedByDescending { it.third }
        val chosen = mutableListOf<Triple<Int, com.gpic.android.editor.FrameStat, Float>>()
        for (p in picks) {
            if (chosen.size >= 6) break
            if (chosen.none { c -> com.gpic.android.editor.SmartEdit.cosine(c.second.embed, p.second.embed) > 0.97f }) chosen += p
        }
        _state.update { it.copy(covers = chosen.map { (c, f, _) -> CoverPick(c, f.tMs) }) }
    }

    /** Finds cover suggestions (runs the shot analysis if Smart edit hasn't yet). */
    fun findCovers() {
        if (autoJob?.isActive == true || _state.value.clips.isEmpty()) return
        autoJob = viewModelScope.launch {
            val analyses = runCatching { analyzeAll("Looking for the best frames…") }.getOrNull()
            _state.update { it.copy(autoProgress = null) }
            if (analyses != null) updateCovers(analyses)
        }
    }

    suspend fun coverThumb(p: CoverPick): Bitmap? = withContext(Dispatchers.IO) {
        _state.value.clips.getOrNull(p.clip)?.let { VideoProbe.thumbnail(context, it, p.timeMs, 240) }
    }

    /** Saves [p] at the original's full resolution to Pictures/GPic and makes it the project's cover. */
    fun saveCover(p: CoverPick) {
        val clip = _state.value.clips.getOrNull(p.clip) ?: return
        val id = _state.value.projectId ?: return
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val bmp = if (clip.isImage) VideoProbe.thumbnail(context, clip.copy(proxy = null), 0, 4096)
                    else android.media.MediaMetadataRetriever().let { mr ->
                        try { mr.setDataSource(context, clip.uri); mr.getFrameAtTime(p.timeMs * 1000, android.media.MediaMetadataRetriever.OPTION_CLOSEST) } finally { mr.release() }
                    }
                    bmp ?: throw IllegalStateException("couldn't read that frame")
                    projects.saveCover(id, Bitmap.createScaledBitmap(bmp, 480, 480 * bmp.height / bmp.width.coerceAtLeast(1), true))
                    savePhoto(bmp, clip.name.substringBeforeLast('.') + "_cover")
                }
            }
            r.onSuccess { toast("Saved to Pictures/GPic and set as the project's cover") }.onFailure { e -> toast("Couldn't save the cover: ${e.message}") }
        }
    }

    private fun savePhoto(bmp: Bitmap, base: String): Uri {
        val name = base + "_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date()) + ".jpg"
        val values = android.content.ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Images.Media.RELATIVE_PATH, android.os.Environment.DIRECTORY_PICTURES + "/GPic")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val collection = if (android.os.Build.VERSION.SDK_INT >= 29) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val uri = context.contentResolver.insert(collection, values) ?: throw IllegalStateException("couldn't create the photo")
        context.contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        if (android.os.Build.VERSION.SDK_INT >= 29) context.contentResolver.update(uri, android.content.ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        return uri
    }

    /**
     * Adds generated title cards: a 2.5 s still made from the best frame (blurred and darkened), with editable
     * title text on top. The intro shifts everything else (including texts) later.
     */
    fun addCards(intro: Boolean, outro: Boolean, oneStep: Boolean = true) {
        val id = _state.value.projectId ?: return
        viewModelScope.launch {
            val st = _state.value
            val parts = st.parts.ifEmpty { return@launch }
            val (w, h) = CompositionBuilder.outputFrame(st.plan, st.clips, parts, 1080)
            val cover = st.covers.firstOrNull() ?: CoverPick(parts.first().clip, (parts.first().startMs + parts.first().endMs) / 2)
            val made = withContext(Dispatchers.IO) {
                runCatching {
                    val src = st.clips.getOrNull(cover.clip)?.let { VideoProbe.thumbnail(context, it, cover.timeMs, 720) } ?: throw IllegalStateException("no frame")
                    val bg = com.gpic.android.editor.Cards.background(src, w, h)
                    val file = File(projects.mediaDir(id), "$CARD_PREFIX${System.nanoTime()}.jpg")
                    com.gpic.android.editor.Cards.saveJpeg(bg, file)
                    VideoProbe.probe(context, Uri.fromFile(file)).copy(name = file.name)
                }
            }
            val card = made.getOrElse { e -> return@launch toast("Couldn't make the card: ${e.message}") }
            val date = java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault()).format(java.util.Date(captureDate(st.clips)))
            val title = st.projectName.takeIf { n -> n.isNotBlank() && !n.first().isDigit() } ?: "Highlights"
            val change: (EditPlan) -> EditPlan = { p0 ->
                var p = Timeline.materialize(p0, st.durations)
                val cardIndex = st.clips.size // the card clip is appended below
                val len = CARD_MS
                if (intro) {
                    p = p.copy(
                        segments = listOf(Segment(0, len, cardIndex, motion = com.gpic.android.editor.Motion.ZOOM_IN, transition = Transition.FADE_BLACK)) + p.segments,
                        texts = p.texts.map { t -> t.copy(startMs = t.startMs + len, endMs = t.endMs + len) } + listOf(
                            TextItem(title, 150, len - 100, y = 0.44f, size = 0.11f, style = com.gpic.android.editor.TextStyle.SHADOW,
                                font = com.gpic.android.editor.TextFont.CONDENSED, animIn = com.gpic.android.editor.TextAnim.TYPEWRITER),
                            TextItem(date, 600, len - 100, y = 0.58f, size = 0.045f, style = com.gpic.android.editor.TextStyle.PLAIN,
                                font = com.gpic.android.editor.TextFont.SANS, animIn = com.gpic.android.editor.TextAnim.FADE),
                        ),
                    )
                }
                if (outro) {
                    val total = p.outputMs(st.durations + card.durationMs)
                    p = p.copy(
                        segments = p.segments.dropLast(1) + p.segments.last().copy(transition = Transition.FADE_BLACK) +
                            Segment(0, len, cardIndex, motion = com.gpic.android.editor.Motion.ZOOM_OUT),
                        texts = p.texts + TextItem("The End", total + 200, total + len - 100, y = 0.48f, size = 0.09f,
                            style = com.gpic.android.editor.TextStyle.SHADOW, font = com.gpic.android.editor.TextFont.SERIF,
                            animIn = com.gpic.android.editor.TextAnim.FADE),
                    )
                }
                p
            }
            _state.update { s ->
                val clips = s.clips + card
                val next = change(s.plan).sanitized(clips.map { it.durationMs }, s.luts.mapTo(HashSet()) { it.id })
                if (oneStep) { undo.addLast(s.plan); redo.clear() }
                s.copy(clips = clips, plan = next, canUndo = undo.isNotEmpty(), canRedo = false, exported = null)
            }
            scheduleSave()
            loadThumbs()
        }
    }

    /** Earliest capture date of the clips (their metadata), else today. */
    private fun captureDate(clips: List<VideoInfo>): Long = clips.filter { !it.isImage }.mapNotNull { v ->
        runCatching {
            android.media.MediaMetadataRetriever().let { mr ->
                try {
                    mr.setDataSource(context, v.uri)
                    mr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DATE)?.let { d ->
                        java.text.SimpleDateFormat("yyyyMMdd'T'HHmmss", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.parse(d.take(15))?.time
                    }
                } finally { mr.release() }
            }
        }.getOrNull()
    }.filter { it > 946684800000L }.minOrNull() ?: System.currentTimeMillis()

    // ---- Claude app (Pro plan: no API key) -------------------------------------------------------------------

    /** The prompt and (optionally) a storyboard image to share with the Claude app. */
    suspend fun prepareClaudeShare(withStoryboard: Boolean): Pair<String, Uri?> = withContext(Dispatchers.IO) {
        val st = _state.value
        val plan = Timeline.materialize(st.plan, st.durations)
        val image = if (!withStoryboard) null else runCatching {
            val per = ClaudeEditPlanner.framesPerClip(st.durations).map { minOf(it, 6) }
            val frames = st.clips.flatMapIndexed { i, v ->
                if (v.isImage) listOfNotNull(VideoProbe.thumbnail(context, v, 0, 256)?.let { "C$i photo" to it })
                else (0 until per[i]).mapNotNull { k ->
                    val t = v.durationMs * (2 * k + 1) / (2 * per[i])
                    VideoProbe.thumbnail(context, v, t, 256)?.let { b -> "C$i ${t / 60000}:${"%02d".format(t / 1000 % 60)}" to b }
                }
            }.take(24)
            val sheet = com.gpic.android.editor.Cards.storyboard(frames)
            val f = File(context.cacheDir, "share/gpic-storyboard.jpg")
            com.gpic.android.editor.Cards.saveJpeg(sheet, f, 85)
            androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".fileprovider", f)
        }.onFailure { Log.w(TAG, "storyboard failed", it) }.getOrNull()
        ClaudeEditPlanner.chatPrompt(st.request.ifBlank { "Make a lively short edit of these clips." }, st.clips, plan, st.luts, image != null) to image
    }

    /** Applies a reply pasted from the Claude app as one undoable step. */
    fun applyClaudeReply(text: String?) {
        val st = _state.value
        if (text.isNullOrBlank()) return toast("Copy Claude's reply first (the copy button under its answer)")
        val r = runCatching { ClaudeEditPlanner.parseReply(text, Timeline.materialize(st.plan, st.durations), st.durations, st.luts.mapTo(HashSet()) { it.id }) }
        r.onSuccess { planned ->
            commit { planned.plan }
            _state.update { it.copy(claudeNote = planned.explanation, selection = Selection.None) }
        }.onFailure { e -> toast(e.message ?: "Couldn't read Claude's reply") }
    }

    // ---- Claude ---------------------------------------------------------------------------------------------

    fun setClaudeKey(key: String?) {
        store.setClaudeApiKey(key)
        _state.update { it.copy(hasClaudeKey = store.getClaudeApiKey() != null) }
    }

    fun setRequest(text: String) = _state.update { it.copy(request = text) }
    fun setSendFrames(on: Boolean) = _state.update { it.copy(sendFrames = on) }

    /** Ask Claude for a new plan. Sends metadata, the current plan and (when ticked) a few small frames per clip. */
    fun askClaude() {
        val st = _state.value
        if (st.clips.isEmpty()) return
        val key = store.getClaudeApiKey() ?: return toast("Add your Claude API key first")
        if (st.request.isBlank() || st.asking) return
        _state.update { it.copy(asking = true, claudeNote = null) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val frames = if (!st.sendFrames) emptyList() else {
                        val per = ClaudeEditPlanner.framesPerClip(st.durations)
                        st.clips.flatMapIndexed { i, v ->
                            if (v.isImage) listOfNotNull(VideoProbe.thumbnail(context, v, 0, 512)?.let { b ->
                                Frame(0, java.io.ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray(), i)
                            })
                            else VideoProbe.frames(context, v.editUri, v.durationMs, per[i], clip = i)
                        }
                    }
                    ClaudeEditPlanner(key).plan(st.request, st.clips, Timeline.materialize(st.plan, st.durations), st.luts, frames)
                }
            }
            result.onSuccess { planned ->
                commit { planned.plan }
                _state.update { it.copy(asking = false, claudeNote = planned.explanation, selection = Selection.None) }
            }.onFailure { e ->
                if (e !is ClaudeException) Log.w(TAG, "Claude request failed", e)
                _state.update { it.copy(asking = false, message = e.message ?: "Claude request failed") }
            }
        }
    }

    // ---- export ---------------------------------------------------------------------------------------------

    fun export() {
        val st = _state.value
        if (st.clips.isEmpty() || exportJob?.isActive == true) return
        saveNow()
        _state.update { it.copy(exportProgress = 0, exported = null, exportedCredits = null, panel = null) }
        exportJob = viewModelScope.launch {
            val r = runCatching { exporter.export(st.clips, st.plan) { p -> _state.update { it.copy(exportProgress = p) } } }
            r.onSuccess { uri ->
                val credit = st.plan.music?.credit
                _state.update { it.copy(exportProgress = null, exported = uri, exportedCredits = credit?.let { c -> "Music: $c" }, message = "Saved to Movies/GPic") }
            }.onFailure { e ->
                if (e is kotlinx.coroutines.CancellationException) _state.update { it.copy(exportProgress = null, message = "Export cancelled") }
                else {
                    Log.w(TAG, "export failed", e)
                    _state.update { it.copy(exportProgress = null, message = e.message ?: "Export failed") }
                }
            }
        }
    }

    fun cancelExport() { exportJob?.cancel() }
    fun dismissExported() = _state.update { it.copy(exported = null, exportedCredits = null) }

    /** Output size for the export sheet at a given short side. */
    fun frameFor(short: Int): Pair<Int, Int> {
        val st = _state.value
        val parts = st.parts.ifEmpty { return 0 to 0 }
        return CompositionBuilder.outputFrame(st.plan.copy(maxHeight = short), st.clips, parts, st.maxShortSide)
    }

    override fun onCleared() { saveNow() }

    private fun plural(n: Int, word: String) = if (n == 1) "1 $word" else "$n ${word}s"

    companion object {
        private const val TAG = "GPicEditor"
        /** The preview renders at this short side: smooth on old phones, sharp enough on a phone screen. */
        const val PREVIEW_SHORT_SIDE = 480
        const val THUMB_STEP_MS = 1000L
        const val MAX_THUMBS = 120
        const val THUMB_PX = 96
        private const val MAX_UNDO = 60
        const val CARD_PREFIX = "gpic-card-"
        const val CARD_MS = 2_500L
    }
}
