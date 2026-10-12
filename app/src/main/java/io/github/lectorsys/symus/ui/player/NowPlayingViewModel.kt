package io.github.lectorsys.symus.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.lectorsys.symus.data.LibraryRepository
import io.github.lectorsys.symus.data.TrackEntity
import io.github.lectorsys.symus.library.LyricsRepository
import io.github.lectorsys.symus.library.LyricsResult
import io.github.lectorsys.symus.media.Playback
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Lyrics are a network fetch, so the pane needs a state of its own. */
sealed interface LyricsState {
    data object Idle : LyricsState
    data object Loading : LyricsState
    data class Ready(val result: LyricsResult) : LyricsState
}

@UnstableApi
@HiltViewModel
class NowPlayingViewModel @Inject constructor(
    private val library: LibraryRepository,
    private val lyrics: LyricsRepository,
    playback: Playback
) : ViewModel() {

    /**
     * The full library row for whatever is playing.
     *
     * The player's own metadata is enough to draw a row, but not enough to act
     * on one: liking, downloading and tagging all need the stored track, and
     * that is what this resolves.
     */
    val track: StateFlow<TrackEntity?> = playback.state
        .map { it.current?.videoId }
        .distinctUntilChanged()
        .map { id -> id?.let { library.byId(it) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _lyricsState = MutableStateFlow<LyricsState>(LyricsState.Idle)
    val lyricsState: StateFlow<LyricsState> = _lyricsState.asStateFlow()

    private var lyricsFor: String? = null

    /** Fetched on demand: most listening never opens the lyrics pane. */
    fun loadLyrics(force: Boolean = false) {
        val current = track.value ?: return
        if (!force && lyricsFor == current.videoId) return
        lyricsFor = current.videoId
        _lyricsState.value = LyricsState.Loading
        viewModelScope.launch {
            val result = if (force) lyrics.refresh(current) else lyrics.forTrack(current)
            // A track change while the fetch was in flight must not overwrite the
            // pane with words for something else.
            if (lyricsFor == current.videoId) _lyricsState.value = LyricsState.Ready(result)
        }
    }

    private val _similar = MutableStateFlow<List<TrackEntity>?>(null)

    /** Genre-first picks for the current track; null while loading. */
    val similar: StateFlow<List<TrackEntity>?> = _similar.asStateFlow()

    private var similarFor: String? = null

    /** Same scorer autoplay uses, so the pane previews what autoplay would pick. */
    fun loadSimilar(exclude: Set<String>) {
        val current = track.value ?: return
        if (similarFor == current.videoId) return
        similarFor = current.videoId
        _similar.value = null
        viewModelScope.launch {
            val picks = runCatching { library.similarTo(current, exclude, limit = 15) }
                .getOrDefault(emptyList())
            if (similarFor == current.videoId) _similar.value = picks
        }
    }

    fun forgetLyrics() {
        lyricsFor = null
        _lyricsState.value = LyricsState.Idle
    }
}
