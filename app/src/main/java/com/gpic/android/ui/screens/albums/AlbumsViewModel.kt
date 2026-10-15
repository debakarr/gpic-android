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
    /** Suggestion being turned into an album right now. */
    val creatingId: String? = null,
    /** One-shot result for a snackbar. */
    val message: String? = null,
)

/** [context] must be the Application context. */
class AlbumsViewModel(context: Context, private val store: CredentialStore) : ViewModel() {
    private val index = MediaIndex.get(context)
    private val prefs = context.getSharedPreferences("gpic_prefs", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(AlbumsUiState(minSize = prefs.getInt("album_min_size", 5)))
    val state: StateFlow<AlbumsUiState> = _state.asStateFlow()

    init {
        // Recompute whenever an upload adds to the index (or an album/dismissal changes it).
        viewModelScope.launch { index.version.collect { recompute() } }
    }

    private suspend fun recompute() {
        val min = _state.value.minSize
        val (list, n) = withContext(Dispatchers.Default) {
            AlbumSuggester.suggest(index.all(), minSize = min, dismissed = index.dismissedIds()) to index.size()
        }
        _state.update { it.copy(suggestions = list, indexed = n) }
    }

    fun setMinSize(n: Int) {
        prefs.edit().putInt("album_min_size", n).apply()
        _state.update { it.copy(minSize = n) }
        viewModelScope.launch { recompute() }
    }

    fun dismiss(s: Suggestion) {
        viewModelScope.launch(Dispatchers.IO) { index.dismiss(s.id) }
    }

    fun messageShown() = _state.update { it.copy(message = null) }

    /** Creates the album in Google Photos — the only step that sends anything (the media keys + name). */
    fun create(s: Suggestion, name: String) {
        if (_state.value.creatingId != null) return
        val cred = store.getActiveCredential() ?: return _state.update { it.copy(message = "Link Google Photos first") }
        val title = name.trim().ifEmpty { AlbumSuggester.title(s) }
        _state.update { it.copy(creatingId = s.id) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val api = GooglePhotosApi(cred.authString, cred.language)
                    val keys = s.items.map { it.mediaKey }
                    // Keep each request modest; the rest go in with "add to album".
                    val batches = keys.chunked(BATCH)
                    val albumKey = api.createAlbum(title, batches.first())
                    batches.drop(1).forEach { api.addMediaToAlbum(albumKey, it) }
                    index.markAlbum(keys, title)
                }
            }
            _state.update {
                it.copy(creatingId = null, message = result.fold(
                    { "Created “$title” with ${s.items.size} items" },
                    { e -> Log.w("AlbumsViewModel", "create album failed", e); "Couldn't create the album: ${e.message}" },
                ))
            }
        }
    }

    companion object {
        private const val BATCH = 250
    }
}
