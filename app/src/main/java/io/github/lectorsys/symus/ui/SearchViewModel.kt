package io.github.lectorsys.symus.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.lectorsys.symus.data.HistoryDao
import io.github.lectorsys.symus.data.HistoryEntryEntity
import io.github.lectorsys.symus.data.TrackDao
import io.github.lectorsys.symus.data.TrackEntity
import io.github.lectorsys.symus.library.genreFor
import io.github.lectorsys.symus.media.Extractor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SearchUiState(
    val query: String = "",
    val searching: Boolean = false,
    val results: List<TrackEntity> = emptyList(),
    val nowPlaying: TrackEntity? = null,
    val error: String? = null
)

/**
 * The proof of concept: search YouTube on device, save what comes back, play one.
 *
 * Playback here is a bare player owned by the ViewModel, which is deliberately
 * temporary — it dies with the screen, so nothing survives backgrounding. The
 * MediaSessionService in the next step is what makes playback outlive the UI.
 */
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val extractor: Extractor,
    private val tracks: TrackDao,
    private val history: HistoryDao,
    private val player: PocPlayer
) : ViewModel() {

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    fun onQueryChange(query: String) {
        _state.value = _state.value.copy(query = query)
    }

    fun search() {
        val query = _state.value.query.trim()
        if (query.isEmpty()) return

        _state.value = _state.value.copy(searching = true, error = null)
        viewModelScope.launch {
            runCatching { extractor.search(query, limit = 15) }
                .onSuccess { hits ->
                    // Saved on arrival, tagged the way the desktop app tags: the
                    // track's own text first, the search only as a fallback.
                    val found = hits.map { hit ->
                        hit.track.copy(genre = genreFor(hit.track.title, hit.track.artist, query))
                    }
                    tracks.upsert(found)
                    found.forEach { track ->
                        track.genre?.let { tracks.tagIfUntagged(track.videoId, it) }
                    }
                    _state.value = _state.value.copy(searching = false, results = found)
                }
                .onFailure { error ->
                    _state.value = _state.value.copy(
                        searching = false,
                        error = error.message ?: "Search failed"
                    )
                }
        }
    }

    fun play(track: TrackEntity) {
        _state.value = _state.value.copy(error = null)
        viewModelScope.launch {
            runCatching { extractor.audioUrl(track.videoId) }
                .onSuccess { url ->
                    player.play(url)
                    history.record(HistoryEntryEntity(videoId = track.videoId))
                    _state.value = _state.value.copy(nowPlaying = track)
                }
                .onFailure { error ->
                    // Resolution failing is the common case in this app's life —
                    // age gates, removals, rate limits — so it reads as a message
                    // about that track rather than a crash.
                    extractor.forget(track.videoId)
                    _state.value = _state.value.copy(error = error.message ?: "Could not play that")
                }
        }
    }

    fun togglePause() = player.togglePause()

    override fun onCleared() {
        player.release()
        super.onCleared()
    }
}
