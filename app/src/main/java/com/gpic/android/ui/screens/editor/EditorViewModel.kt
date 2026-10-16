package com.gpic.android.ui.screens.editor

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.annotation.OptIn
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import com.gpic.android.data.auth.CredentialStore
import com.gpic.android.editor.ClaudeEditPlanner
import com.gpic.android.editor.ClaudeException
import com.gpic.android.editor.EditEffects
import com.gpic.android.editor.EditPlan
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

data class EditorUiState(
    val video: VideoInfo? = null,
    val loading: Boolean = false,
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
    /** Claude's note about its last plan. */
    val claudeNote: String? = null,
    val exportProgress: Int? = null,
    val exported: Uri? = null,
    /** Picture effects for the live preview (built off the main thread). */
    val previewEffects: List<Effect> = emptyList(),
    val message: String? = null,
)

/** [context] must be the Application context. */
@OptIn(UnstableApi::class)
@kotlin.OptIn(FlowPreview::class)
class EditorViewModel(private val context: Context, private val store: CredentialStore) : ViewModel() {
    private val luts = LutStore(context)
    private val exporter = VideoExporter(context, luts)
    private val _state = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = _state.asStateFlow()
    private var exportJob: Job? = null

    init {
        refreshLuts()
        _state.update { it.copy(hasClaudeKey = store.getClaudeApiKey() != null) }
        // Rebuild the preview's effects when the picture part of the plan changes (a LUT is parsed once, off the UI thread).
        viewModelScope.launch {
            _state.map { st -> st.video?.let { v -> Triple(st.plan.copy(segments = emptyList(), speed = 1f), v, st.luts.size) } }
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

    fun openVideo(uri: Uri) {
        _state.update { it.copy(loading = true, exported = null, claudeNote = null) }
        viewModelScope.launch {
            val info = withContext(Dispatchers.IO) { runCatching { VideoProbe.probe(context, uri) } }
            info.onSuccess { v ->
                // A flat-looking DJI clip is probably D-Log M: start with the LUT on. Anything else starts untouched.
                val lut = if (v.fromDji && v.looksFlat == true) luts.defaultId else null
                _state.update {
                    it.copy(
                        video = v, loading = false, plan = EditPlan(lutId = lut, keepOriginalAudio = v.hasAudio),
                        message = if (lut != null) "Looks like D-Log M footage, so ${it.luts.first { l -> l.id == lut }.title} is on. Turn it off under Colour if it isn't." else null,
                    )
                }
            }.onFailure { e ->
                Log.w(TAG, "probe failed", e)
                _state.update { it.copy(loading = false, message = "Couldn't open that video: ${e.message}") }
            }
        }
    }

    fun update(change: (EditPlan) -> EditPlan) = _state.update { st ->
        val v = st.video ?: return@update st
        st.copy(plan = change(st.plan).sanitized(v.durationMs, st.luts.mapTo(HashSet()) { it.id }), exported = null)
    }

    fun setMusic(uri: Uri?) {
        val name = uri?.let { u ->
            runCatching {
                context.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            }.getOrNull() ?: "Music"
        }
        _state.update { it.copy(musicUri = uri, musicName = name, exported = null) }
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

    /** Ask Claude for a new plan. Sends metadata, the current plan and (when ticked) a few small frames. */
    fun askClaude() {
        val st = _state.value
        val video = st.video ?: return
        val key = store.getClaudeApiKey() ?: return _state.update { it.copy(message = "Add your Claude API key first") }
        if (st.request.isBlank() || st.asking) return
        _state.update { it.copy(asking = true, claudeNote = null) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val frames = if (st.sendFrames) VideoProbe.frames(context, video.uri, video.durationMs, ClaudeEditPlanner.FRAME_COUNT) else emptyList()
                    ClaudeEditPlanner(key).plan(st.request, video, st.plan, st.luts, st.musicName, frames)
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
        val video = st.video ?: return
        if (exportJob?.isActive == true) return
        _state.update { it.copy(exportProgress = 0, exported = null) }
        exportJob = viewModelScope.launch {
            val r = runCatching {
                exporter.export(video, st.plan, st.musicUri) { p -> _state.update { it.copy(exportProgress = p) } }
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

    companion object {
        private const val TAG = "GPicEditor"
    }
}
