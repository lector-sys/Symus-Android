package io.github.lectorsys.symus.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.lectorsys.symus.data.LibraryRepository
import io.github.lectorsys.symus.data.TrackEntity
import io.github.lectorsys.symus.media.AlbumHit
import io.github.lectorsys.symus.media.ExtractionFailed
import io.github.lectorsys.symus.media.Extractor
import io.github.lectorsys.symus.media.VoiceSearch
import io.github.lectorsys.symus.media.SearchKind
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.Page
import javax.inject.Inject

data class SearchState(
    val query: String = "",
    val kind: SearchKind = SearchKind.SONGS,
    val submitted: Boolean = false,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val tracks: List<TrackEntity> = emptyList(),
    val albums: List<AlbumHit> = emptyList(),
    val local: List<TrackEntity> = emptyList(),
    val error: String? = null,
    val retryable: Boolean = false,
    val hasMore: Boolean = false
)

@OptIn(FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val extractor: Extractor,
    private val library: LibraryRepository,
    private val voice: VoiceSearch
) : ViewModel() {

    private val _state = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = _state.asStateFlow()

    // -- voice ---------------------------------------------------------------

    val voiceState = voice.state

    /** Whether the microphone is worth offering at all on this device. */
    val voiceAvailable: Boolean get() = voice.isAvailable

    /** Whether recognition happens locally. Shown to the user, not assumed. */
    val voiceIsOnDevice: Boolean get() = voice.onDevice

    fun startListening() = voice.start()

    fun stopListening() = voice.stop()

    fun cancelListening() = voice.cancel()

    fun consumeVoice() = voice.consume()

    /**
     * The user said no to the microphone.
     *
     * Reported through the same channel as a recogniser failure so the sheet
     * has one place to read from, and marked as not retryable: asking again
     * from here would just be refused by the system.
     */
    fun voicePermissionDenied() = voice.denied()

    val recentQueries = library.recentSearches(12)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val typed = MutableStateFlow("")

    /**
     * YouTube's own autocomplete, a beat behind the keyboard.
     *
     * Debounced because this runs a network call per keystroke otherwise, and
     * `mapLatest` because the answer to a prefix the user has already typed past
     * is worthless — which is also what stops a slow response overwriting the
     * list with suggestions for three letters ago.
     */
    val suggestions: StateFlow<List<String>> = typed
        .debounce(SUGGEST_DEBOUNCE_MS)
        .distinctUntilChanged()
        .mapLatest { query ->
            if (query.trim().length < 2) emptyList() else extractor.suggestions(query.trim())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var searchJob: Job? = null
    private var nextPage: Page? = null

    fun onQueryChange(query: String) {
        _state.value = _state.value.copy(query = query, submitted = false, error = null)
        typed.value = query
        // Local matches are instant and offline, so they are shown while the
        // network search is still being typed towards.
        viewModelScope.launch {
            val matches = library.localSearch(query, 6)
            if (_state.value.query == query) _state.value = _state.value.copy(local = matches)
        }
    }

    fun setKind(kind: SearchKind) {
        if (_state.value.kind == kind) return
        _state.value = _state.value.copy(kind = kind)
        if (_state.value.submitted) submit(_state.value.query)
    }

    /**
     * Runs a search, cancelling whatever was running.
     *
     * The cancel is the important half: without it a slow first search can land
     * after a fast second one and replace correct results with stale ones.
     */
    fun submit(raw: String = _state.value.query) {
        val query = raw.trim()
        if (query.isEmpty()) return
        searchJob?.cancel()
        nextPage = null
        _state.value = _state.value.copy(
            query = query,
            submitted = true,
            loading = true,
            error = null,
            retryable = false,
            tracks = emptyList(),
            albums = emptyList(),
            hasMore = false
        )
        searchJob = viewModelScope.launch {
            library.recordSearch(query)
            try {
                if (_state.value.kind == SearchKind.ALBUMS) {
                    val albums = extractor.searchAlbums(query, 20)
                    _state.value = _state.value.copy(loading = false, albums = albums)
                } else if (looksLikeLink(query)) {
                    // A pasted link is not a search: resolve exactly what it
                    // points at, which for a playlist means the whole list.
                    val resolved = extractor.resolveLink(query)
                    val tracks = library.remember(resolved.hits, resolved.name ?: query)
                    _state.value = _state.value.copy(loading = false, tracks = tracks)
                } else {
                    val page = extractor.search(query, _state.value.kind, PAGE_SIZE)
                    nextPage = page.nextPage
                    val tracks = library.remember(page.hits, query)
                    _state.value = _state.value.copy(
                        loading = false,
                        tracks = tracks,
                        hasMore = page.hasMore
                    )
                }
            } catch (err: Throwable) {
                if (err is kotlinx.coroutines.CancellationException) throw err
                val failure = Extractor.classify(err)
                _state.value = _state.value.copy(
                    loading = false,
                    error = failure.message,
                    retryable = failure.retryable
                )
            }
        }
    }

    /** The next page, appended. A no-op while one is already loading. */
    fun loadMore() {
        val page = nextPage ?: return
        val current = _state.value
        if (current.loading || current.loadingMore || current.kind == SearchKind.ALBUMS) return
        _state.value = current.copy(loadingMore = true)
        viewModelScope.launch {
            try {
                val more = extractor.searchMore(current.query, current.kind, page, PAGE_SIZE)
                nextPage = more.nextPage
                val tracks = library.remember(more.hits, current.query)
                val existing = _state.value.tracks.map { it.videoId }.toSet()
                _state.value = _state.value.copy(
                    loadingMore = false,
                    tracks = _state.value.tracks + tracks.filter { it.videoId !in existing },
                    hasMore = more.hasMore
                )
            } catch (err: Throwable) {
                if (err is kotlinx.coroutines.CancellationException) throw err
                _state.value = _state.value.copy(
                    loadingMore = false,
                    hasMore = false,
                    error = (err as? ExtractionFailed ?: Extractor.classify(err)).message
                )
            }
        }
    }

    /**
     * Loads an album's tracks and hands them back.
     *
     * This is the only place album names come from: YouTube exposes one on a
     * YouTube Music release page and nowhere else, so the name is written onto
     * each track here — which is what makes the Albums section of the library
     * possible at all.
     */
    fun openAlbum(album: AlbumHit, onLoaded: (List<TrackEntity>) -> Unit) {
        viewModelScope.launch {
            try {
                val resolved = extractor.playlistTracks(album.url)
                val tracks = library.remember(resolved.hits, album.title)
                val name = resolved.name ?: album.title
                tracks.forEach { library.tagAlbum(it.videoId, name) }
                onLoaded(library.byIds(tracks.map { it.videoId }))
            } catch (err: Throwable) {
                if (err is kotlinx.coroutines.CancellationException) throw err
                _state.value = _state.value.copy(error = Extractor.classify(err).message)
            }
        }
    }

    fun clearQuery() {
        searchJob?.cancel()
        nextPage = null
        _state.value = SearchState()
        typed.value = ""
    }

    private fun looksLikeLink(query: String): Boolean =
        query.startsWith("http://") || query.startsWith("https://") ||
            query.contains("youtube.com/") || query.contains("youtu.be/")

    private companion object {
        const val PAGE_SIZE = 20
        const val SUGGEST_DEBOUNCE_MS = 250L
    }
}
