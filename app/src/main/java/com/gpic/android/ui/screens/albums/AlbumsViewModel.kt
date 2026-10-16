package com.gpic.android.ui.screens.albums

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gpic.android.data.albums.AlbumSuggester
import com.gpic.android.data.albums.MediaIndex
import com.gpic.android.data.albums.Suggestion
import com.gpic.android.data.api.GooglePhotosApi
import com.gpic.android.data.auth.CredentialStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AlbumsUiState(
    val suggestions: List<Suggestion> = emptyList(),
    /** Items GPic knows are in the library (the pool suggestions come from). */
    val indexed: Int = 0,
    val minSize: Int = 5,
    /** Names the user typed, by suggestion id (the rest use the date title). */
    val names: Map<String, String> = emptyMap(),
    /** Suggestions ticked for "Create N albums". */
    val selected: Set<String> = emptySet(),
    /** Suggestion being turned into an album right now. */
    val creatingId: String? = null,
    /** "2 of 5" while several albums are being created. */
    val batchProgress: String? = null,
    /** One-shot result for a snackbar. */
    val message: String? = null,
) {
    fun nameOf(s: Suggestion): String = names[s.id] ?: AlbumSuggester.title(s)
    val busy: Boolean get() = creatingId != null
}

/** [context] must be the Application context. */
@OptIn(FlowPreview::class)
class AlbumsViewModel(context: Context, private val store: CredentialStore) : ViewModel() {
    private val index = MediaIndex.get(context)
    private val prefs = context.getSharedPreferences("gpic_prefs", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(AlbumsUiState(minSize = prefs.getInt("album_min_size", 5)))
    val state: StateFlow<AlbumsUiState> = _state.asStateFlow()

    init {
        // Recompute whenever an upload adds to the index (or an album/dismissal changes it). An upload bumps
        // the version per file, so wait for a pause instead of regrouping the whole library each time.
        viewModelScope.launch { index.version.debounce(300).collect { recompute() } }
    }

    private suspend fun recompute() {
        val min = _state.value.minSize
        val (list, n) = withContext(Dispatchers.Default) {
            AlbumSuggester.suggest(index.all(), minSize = min, dismissed = index.dismissedIds()) to index.size()
        }
        val ids = list.mapTo(HashSet()) { it.id }
        _state.update { st -> st.copy(suggestions = list, indexed = n, selected = st.selected.filterTo(HashSet()) { it in ids }) }
    }

    fun setMinSize(n: Int) {
        prefs.edit().putInt("album_min_size", n).apply()
        _state.update { it.copy(minSize = n) }
        viewModelScope.launch { recompute() }
    }

    fun rename(s: Suggestion, name: String) = _state.update { it.copy(names = it.names + (s.id to name)) }

    fun toggleSelected(s: Suggestion) = _state.update {
        it.copy(selected = if (s.id in it.selected) it.selected - s.id else it.selected + s.id)
    }

    fun selectAll() = _state.update { st -> st.copy(selected = st.suggestions.mapTo(HashSet()) { it.id }) }

    fun clearSelection() = _state.update { it.copy(selected = emptySet()) }

    fun dismiss(s: Suggestion) {
        _state.update { it.copy(selected = it.selected - s.id) }
        viewModelScope.launch(Dispatchers.IO) { index.dismiss(s.id) }
    }

    fun messageShown() = _state.update { it.copy(message = null) }

    fun create(s: Suggestion) = createAll(listOf(s))

    fun createSelected() {
        val st = _state.value
        createAll(st.suggestions.filter { it.id in st.selected })
    }

    /**
     * Creates the albums in Google Photos one after another — the only step that sends anything (each album's
     * name and media keys). A failure doesn't stop the rest; failed ones stay ticked for another try.
     */
    private fun createAll(list: List<Suggestion>) {
        if (list.isEmpty() || _state.value.busy) return
        val cred = store.getActiveCredential() ?: return _state.update { it.copy(message = "Link Google Photos first") }
        val api = GooglePhotosApi(cred.authString, cred.language)
        viewModelScope.launch {
            val failed = mutableListOf<Pair<String, String>>()
            var made = 0
            var lastTitle = ""
            for ((i, s) in list.withIndex()) {
                val title = _state.value.nameOf(s).trim().ifEmpty { AlbumSuggester.title(s) }
                _state.update { it.copy(creatingId = s.id, batchProgress = if (list.size > 1) "${i + 1} of ${list.size}" else null) }
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val keys = s.items.map { it.mediaKey }
                        // Keep each request modest; the rest go in with "add to album".
                        val batches = keys.chunked(BATCH)
                        val albumKey = api.createAlbum(title, batches.first())
                        batches.drop(1).forEach { api.addMediaToAlbum(albumKey, it) }
                        index.markAlbum(keys, title)
                    }
                }
                result.onSuccess {
                    made++
                    lastTitle = title
                    _state.update { it.copy(selected = it.selected - s.id) }
                }.onFailure { e ->
                    Log.w("AlbumsViewModel", "create album failed", e)
                    failed += title to (e.message ?: e.javaClass.simpleName)
                }
            }
            val msg = when {
                failed.isEmpty() && made == 1 -> "Created “$lastTitle” with ${list.first().items.size} items"
                failed.isEmpty() -> "Created $made albums"
                made == 0 && failed.size == 1 -> "Couldn't create the album: ${failed.first().second}"
                else -> "Created $made of ${list.size} albums. Failed: ${failed.joinToString { "“${it.first}”" }} (${failed.first().second}). They're still ticked; try again."
            }
            _state.update { it.copy(creatingId = null, batchProgress = null, message = msg) }
        }
    }

    companion object {
        private const val BATCH = 250
    }
}
