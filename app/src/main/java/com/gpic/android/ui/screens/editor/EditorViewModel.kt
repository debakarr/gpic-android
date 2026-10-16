package com.gpic.android.ui.screens.editor

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import androidx.annotation.OptIn
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import com.gpic.android.data.auth.CredentialStore
import com.gpic.android.editor.AutoEdit
import com.gpic.android.editor.ClaudeEditPlanner
import com.gpic.android.editor.ClaudeException
import com.gpic.android.editor.EditEffects
import com.gpic.android.editor.EditPlan
import com.gpic.android.editor.EditStyle
import com.gpic.android.editor.LutInfo
import com.gpic.android.editor.LutStore
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

data class EditorUiState(
    /** The clips being edited, in the order they were added. */
    val clips: List<VideoInfo> = emptyList(),
    /** "Copying 2 of 3 from Google Photos…" etc. while clips are being added. */
    val loading: String? = null,
    val plan: EditPlan = EditPlan(),
    val luts: List<LutInfo> = emptyList(),
    /** DJI's LUT when imported, else GPic's approximation: what "Apply LUT" picks. */
    val defaultLutId: String = LutStore.VIVID_ID,
    val hasDjiLut: Boolean = false,
    val musicUri: Uri? = null,
    val musicName: String? = null,
    val hasClaudeKey: Boolean = false,
    val request: String = "",
    val sendFrames: Boolean = false,
    val asking: Boolean = false,
    /** Claude's note about its last plan, or Quick edit's summary. */
    val claudeNote: String? = null,
    val style: EditStyle = EditStyle.TRAVEL,
    /** Quick edit target length in seconds. */
    val targetSec: Int = 30,
    /** "Finding the best moments… 40%" while Quick edit scores the clips. */
    val autoProgress: String? = null,
    /** What the last Quick edit made. */
    val autoNote: String? = null,
    val exportProgress: Int? = null,
    val exported: Uri? = null,
    /** Picture effects for the live preview (built off the main thread). */
    val previewEffects: List<Effect> = emptyList(),
    val message: String? = null,
) {
    /** First clip (the single-clip controls use it). */
    val video: VideoInfo? get() = clips.firstOrNull()
    val durations: List<Long> get() = clips.map { it.durationMs }
}

/** [context] must be the Application context. */
@OptIn(UnstableApi::class)
@kotlin.OptIn(FlowPreview::class)
class EditorViewModel(private val context: Context, private val store: CredentialStore) : ViewModel() {
    private val luts = LutStore(context)
    private val exporter = VideoExporter(context, luts)
    private val _state = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = _state.asStateFlow()
    private var exportJob: Job? = null
    private var autoJob: Job? = null
    /** Private copies of clips that came from Google Photos (its read grant doesn't outlive the picker). */
    private val copies = File(context.cacheDir, "editor-clips")

    init {
        refreshLuts()
        _state.update { it.copy(hasClaudeKey = store.getClaudeApiKey() != null) }
        // Rebuild the preview's effects when the picture part of the plan changes (a LUT is parsed once, off the UI thread).
        viewModelScope.launch {
            _state.map { st -> st.video?.let { v -> Triple(st.plan.copy(segments = emptyList(), speed = 1f, fades = false), v, st.luts.size) } }
                .distinctUntilChanged()
                .debounce(150)
                .collect { key ->
                    val effects = key?.let { (plan, v, _) ->
                        withContext(Dispatchers.Default) {
                            runCatching { EditEffects.video(plan, luts, minOf(v.width, v.height)) }
                                .onFailure { Log.w(TAG, "preview effects", it) }.getOrDefault(emptyList())
                        }
                    } ?: emptyList()
                    _state.update { it.copy(previewEffects = effects) }
                }
        }
    }

    private fun refreshLuts() = _state.update {
        it.copy(luts = luts.list(), defaultLutId = luts.defaultId, hasDjiLut = luts.hasDjiLut)
    }

    fun messageShown() = _state.update { it.copy(message = null) }

    /** Start over with these clips (from Google Photos or the phone). */
    fun openVideos(uris: List<Uri>) = addVideos(uris, replace = true)

    fun addVideos(uris: List<Uri>, replace: Boolean = false) {
        if (uris.isEmpty()) return
        _state.update { it.copy(loading = "Opening ${plural(uris.size, "video")}…", exported = null, claudeNote = null) }
        viewModelScope.launch {
            val (added, failed) = withContext(Dispatchers.IO) {
                if (replace) copies.deleteRecursively()
                val ok = mutableListOf<VideoInfo>()
                var bad = 0
                for ((i, uri) in uris.withIndex()) {
                    runCatching {
                        val local = if (isLocal(uri)) uri else copyIn(uri, i + 1, uris.size)
                        VideoProbe.probe(context, local).let { v -> if (local != uri) v.copy(name = displayName(uri) ?: v.name) else v }
                            .also { v -> require(v.durationMs >= EditPlan.MIN_SEGMENT_MS * 2) { "${v.name} is too short to edit" } }
                    }.onSuccess { ok += it }.onFailure { Log.w(TAG, "open failed: ${it.message}"); bad++ }
                }
                ok to bad
            }
            _state.update { st ->
                val clips = (if (replace) emptyList() else st.clips) + added
                // A flat-looking DJI clip is probably D-Log M: start with the LUT on. Anything else starts untouched.
                val lut = if (replace && added.any { it.fromDji && it.looksFlat == true }) luts.defaultId else if (replace) null else st.plan.lutId
                val plan = if (replace) EditPlan(lutId = lut, keepOriginalAudio = clips.any { it.hasAudio }) else st.plan
                st.copy(
                    clips = clips, loading = null,
                    plan = plan.sanitized(clips.map { it.durationMs }, st.luts.mapTo(HashSet()) { it.id }),
                    message = when {
                        failed > 0 -> "Couldn't use ${plural(failed, "video")} (unreadable, or shorter than a second)"
                        replace && lut != null -> "Looks like D-Log M footage, so ${st.luts.firstOrNull { it.id == lut }?.title ?: "the LUT"} is on. Turn it off under Colour if it isn't."
                        else -> null
                    },
                )
            }
        }
    }

    fun removeClip(index: Int) = _state.update { st ->
        if (index !in st.clips.indices) return@update st
        val clips = st.clips.toMutableList().also { it.removeAt(index) }
        // Parts of later clips move down one index; parts of the removed clip go.
        val segs = st.plan.segments.filter { it.clip != index }.map { if (it.clip > index) it.copy(clip = it.clip - 1) else it }
        st.copy(clips = clips, exported = null, plan = st.plan.copy(segments = segs).sanitized(clips.map { it.durationMs }, st.luts.mapTo(HashSet()) { it.id }))
    }

    /** Media from the phone (MediaStore, SAF on local storage) stays readable; anything else is copied in first. */
    private fun isLocal(uri: Uri) = uri.scheme == "file" || uri.authority in setOf(
        MediaStore.AUTHORITY, "com.android.externalstorage.documents", "com.android.providers.media.documents",
    )

    private fun copyIn(uri: Uri, n: Int, of: Int): Uri {
        copies.mkdirs()
        val name = displayName(uri) ?: "clip.mp4"
        val out = File(copies, "${System.nanoTime()}-${name.replace(Regex("[^A-Za-z0-9._-]"), "_")}")
        val size = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
        }.getOrNull() ?: 0L
        if (size > 0 && context.cacheDir.usableSpace < size + 200L * 1024 * 1024) {
            throw IllegalStateException("Not enough free space to copy $name")
        }
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
                        _state.update { it.copy(loading = "Copying video $n of $of from Google Photos" + if (pct >= 0) " ($pct%)…" else "…") }
                    }
                }
            }
        } ?: throw IllegalStateException("Couldn't read $name")
        return Uri.fromFile(out)
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    fun update(change: (EditPlan) -> EditPlan) = _state.update { st ->
        if (st.clips.isEmpty()) return@update st
        st.copy(plan = change(st.plan).sanitized(st.durations, st.luts.mapTo(HashSet()) { it.id }), exported = null)
    }

    fun setStyle(s: EditStyle) = _state.update { it.copy(style = s) }
    fun setTarget(sec: Int) = _state.update { it.copy(targetSec = sec) }

    /**
     * LightCut-style one tap: score every clip for motion and colour on the phone, keep the liveliest moments
     * for the chosen length and style, and apply the style's look, crop, fades and audio mix.
     */
    fun quickEdit() {
        val st = _state.value
        if (st.clips.isEmpty() || autoJob?.isActive == true) return
        autoJob = viewModelScope.launch {
            _state.update { it.copy(autoProgress = "Finding the best moments…", autoNote = null) }
            val scores = withContext(Dispatchers.IO) {
                st.clips.mapIndexed { i, v ->
                    runCatching {
                        VideoProbe.motionScores(context, v.uri, v.durationMs) { done, total ->
                            val pct = ((i + done.toFloat() / total) * 100 / st.clips.size).toInt()
                            _state.update { it.copy(autoProgress = "Finding the best moments… $pct%") }
                        }
                    }.getOrElse { com.gpic.android.editor.ClipScores(v.durationMs, maxOf(1000L, v.durationMs), FloatArray(1) { 0.5f }) }
                }
            }
            val parts = AutoEdit.pick(scores, st.style, st.targetSec * 1000L)
            _state.update { cur ->
                val plan = AutoEdit.plan(cur.plan, parts, cur.style, cur.musicUri != null)
                    .sanitized(cur.durations, cur.luts.mapTo(HashSet()) { it.id })
                val out = plan.outputMs(cur.durations) / 1000
                cur.copy(
                    autoProgress = null, plan = plan, exported = null,
                    autoNote = "${cur.style.label} edit: ${plural(plan.parts(cur.durations).size, "moment")} from ${plural(cur.clips.size, "clip")}, " +
                        "${out / 60}:${"%02d".format(out % 60)} long." + if (cur.musicUri == null) " Add music under Audio for the full effect." else "",
                )
            }
        }
    }

    fun setMusic(uri: Uri?) {
        _state.update { it.copy(musicUri = uri, musicName = uri?.let { u -> displayName(u) ?: "Music" }, exported = null) }
    }

    fun importLut(uri: Uri, asDji: Boolean) {
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { luts.import(uri, asDji) } }
            refreshLuts()
            r.onSuccess { info ->
                update { it.copy(lutId = info.id) }
                _state.update { it.copy(message = "Added “${info.title}”" + if (asDji) ". It's now the default for D-Log M clips." else "") }
            }.onFailure { e -> _state.update { it.copy(message = "Couldn't use that LUT: ${e.message}") } }
        }
    }

    fun deleteLut(id: String) {
        luts.delete(id)
        refreshLuts()
        update { if (it.lutId == id) it.copy(lutId = null) else it }
    }

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
        val key = store.getClaudeApiKey() ?: return _state.update { it.copy(message = "Add your Claude API key first") }
        if (st.request.isBlank() || st.asking) return
        _state.update { it.copy(asking = true, claudeNote = null) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val frames = if (!st.sendFrames) emptyList() else {
                        val per = ClaudeEditPlanner.framesPerClip(st.durations)
                        st.clips.flatMapIndexed { i, v -> VideoProbe.frames(context, v.uri, v.durationMs, per[i], clip = i) }
                    }
                    ClaudeEditPlanner(key).plan(st.request, st.clips, st.plan, st.luts, st.musicName, frames)
                }
            }
            result.onSuccess { planned ->
                _state.update { it.copy(asking = false, plan = planned.plan, claudeNote = planned.explanation, exported = null) }
            }.onFailure { e ->
                if (e !is ClaudeException) Log.w(TAG, "Claude request failed", e)
                _state.update { it.copy(asking = false, message = e.message ?: "Claude request failed") }
            }
        }
    }

    fun export() {
        val st = _state.value
        if (st.clips.isEmpty() || exportJob?.isActive == true) return
        _state.update { it.copy(exportProgress = 0, exported = null) }
        exportJob = viewModelScope.launch {
            val r = runCatching {
                exporter.export(st.clips, st.plan, st.musicUri) { p -> _state.update { it.copy(exportProgress = p) } }
            }
            r.onSuccess { uri -> _state.update { it.copy(exportProgress = null, exported = uri, message = "Saved to Movies/GPic") } }
                .onFailure { e ->
                    if (e is kotlinx.coroutines.CancellationException) _state.update { it.copy(exportProgress = null, message = "Export cancelled") }
                    else {
                        Log.w(TAG, "export failed", e)
                        _state.update { it.copy(exportProgress = null, message = e.message ?: "Export failed") }
                    }
                }
        }
    }

    fun cancelExport() {
        exportJob?.cancel()
    }

    override fun onCleared() {
        copies.deleteRecursively()
    }

    private fun plural(n: Int, word: String) = if (n == 1) "1 $word" else "$n ${word}s"

    companion object {
        private const val TAG = "GPicEditor"
    }
}
