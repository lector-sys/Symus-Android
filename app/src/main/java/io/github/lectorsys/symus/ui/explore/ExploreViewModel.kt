package io.github.lectorsys.symus.ui.explore

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.lectorsys.symus.data.LibraryRepository
import io.github.lectorsys.symus.data.TrackEntity
import io.github.lectorsys.symus.library.Charts
import io.github.lectorsys.symus.library.ExploreRepository
import io.github.lectorsys.symus.library.ExploreShelf
import io.github.lectorsys.symus.library.GenreTile
import io.github.lectorsys.symus.library.PodcastChannel
import io.github.lectorsys.symus.library.PodcastRepository
import io.github.lectorsys.symus.media.Extractor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One shelf, in whichever of its three states it is in. */
data class ShelfState(
    val shelf: ExploreShelf,
    val tracks: List<TrackEntity> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val retryable: Boolean = false
)

@UnstableApi
@HiltViewModel
class ExploreViewModel @Inject constructor(
    private val explore: ExploreRepository
) : ViewModel() {

    private val _shelves = MutableStateFlow<List<ShelfState>>(emptyList())
    val shelves: StateFlow<List<ShelfState>> = _shelves.asStateFlow()

    private var planned = false

    /**
     * Plans the shelves from the library, then fills them one at a time.
     *
     * The plan is two SQLite queries, so the screen can lay out its rows
     * immediately; each row then arrives when its search does. Loading them all
     * at once would be six simultaneous extractions and a rate limit.
     */
    fun load(force: Boolean = false) {
        if (planned && !force) return
        planned = true
        viewModelScope.launch {
            if (force) explore.clearCache()
            val plan = explore.planShelves()
            _shelves.value = plan.map { ShelfState(it) }
            for (shelf in plan) {
                val contents = runCatching { explore.shelf(shelf.query, shelf.hint) }
                _shelves.value = _shelves.value.map { state ->
                    if (state.shelf.id != shelf.id) state
                    else contents.fold(
                        onSuccess = { state.copy(tracks = it.tracks, loading = false) },
                        onFailure = { error ->
                            val failure = Extractor.classify(error)
                            state.copy(
                                loading = false,
                                error = failure.message,
                                retryable = failure.retryable
                            )
                        }
                    )
                }
            }
        }
    }

    fun retry(shelf: ExploreShelf) {
        _shelves.value = _shelves.value.map {
            if (it.shelf.id == shelf.id) it.copy(loading = true, error = null) else it
        }
        viewModelScope.launch {
            val contents = runCatching { explore.shelf(shelf.query, shelf.hint) }
            _shelves.value = _shelves.value.map { state ->
                if (state.shelf.id != shelf.id) state
                else contents.fold(
                    onSuccess = { state.copy(tracks = it.tracks, loading = false) },
                    onFailure = { error ->
                        val failure = Extractor.classify(error)
                        state.copy(
                            loading = false,
                            error = failure.message,
                            retryable = failure.retryable
                        )
                    }
                )
            }
        }
    }
}

@UnstableApi
@HiltViewModel
class MoodsViewModel @Inject constructor(
    private val explore: ExploreRepository
) : ViewModel() {

    private val _tiles = MutableStateFlow<List<GenreTile>>(emptyList())
    val tiles: StateFlow<List<GenreTile>> = _tiles.asStateFlow()

    private val _working = MutableStateFlow<String?>(null)

    /** Which tile is currently pulling tracks in, so only that one shows a spinner. */
    val working: StateFlow<String?> = _working.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun load() {
        viewModelScope.launch { _tiles.value = explore.genreTiles() }
    }

    /**
     * Searches YouTube for a genre and tags what it finds.
     *
     * This is how a genre with no inference rule ever gets a first track — see
     * ExploreRepository.findMoreInGenre.
     */
    fun findMore(genre: String) {
        if (_working.value != null) return
        _working.value = genre
        viewModelScope.launch {
            try {
                val total = explore.findMoreInGenre(genre)
                _tiles.value = explore.genreTiles()
                _message.value = "$genre now has $total tracks"
            } catch (err: Throwable) {
                if (err is kotlinx.coroutines.CancellationException) throw err
                _message.value = Extractor.classify(err).message
            } finally {
                _working.value = null
            }
        }
    }

    fun clearMessage() {
        _message.value = null
    }
}

@UnstableApi
@HiltViewModel
class ChartsViewModel @Inject constructor(
    private val explore: ExploreRepository,
    library: LibraryRepository
) : ViewModel() {

    private val _charts = MutableStateFlow<Charts?>(null)
    val charts: StateFlow<Charts?> = _charts.asStateFlow()

    /**
     * The numbers under the charts, counted from this device's own history.
     *
     * Combined from five queries rather than one: each is a cheap aggregate that
     * Room can keep live on its own, and a single hand-written join would have
     * to be re-run in full whenever any of them changed.
     */
    val stats: StateFlow<Stats> = combine(
        library.totalPlays(),
        library.distinctPlayed(),
        library.skippedPlays(),
        library.secondsListened(),
        library.genrePlays(1)
    ) { plays, distinct, skipped, seconds, genres ->
        Stats(plays, distinct, skipped, seconds, genres.firstOrNull()?.genre)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Stats())

    fun load() {
        viewModelScope.launch { _charts.value = explore.charts() }
    }
}

/** The numbers the Charts screen leads with. All counted locally. */
data class Stats(
    val plays: Int = 0,
    val distinct: Int = 0,
    val skipped: Int = 0,
    val secondsListened: Long = 0,
    val topGenre: String? = null
)

@UnstableApi
@HiltViewModel
class PodcastsViewModel @Inject constructor(
    private val podcasts: PodcastRepository
) : ViewModel() {

    data class State(
        val loading: Boolean = true,
        val query: String = "",
        val episodes: List<TrackEntity> = emptyList(),
        val channels: List<PodcastChannel> = emptyList(),
        val error: String? = null,
        val retryable: Boolean = false
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    val following = podcasts.following
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _channelShelves = MutableStateFlow<Map<String, List<TrackEntity>>>(emptyMap())
    val channelShelves: StateFlow<Map<String, List<TrackEntity>>> = _channelShelves.asStateFlow()

    private var loaded = false

    fun load(force: Boolean = false) {
        if (loaded && !force) return
        loaded = true
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                val shelf = podcasts.discover()
                _state.value = State(
                    loading = false,
                    query = shelf.query,
                    episodes = shelf.tracks,
                    channels = shelf.channels
                )
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

    fun loadChannel(channelId: String) {
        if (_channelShelves.value.containsKey(channelId)) return
        viewModelScope.launch {
            val tracks = runCatching { podcasts.channelShelf(channelId) }.getOrDefault(emptyList())
            _channelShelves.value = _channelShelves.value + (channelId to tracks)
        }
    }

    fun follow(channel: PodcastChannel) {
        viewModelScope.launch { podcasts.follow(channel.channelId, channel.name) }
    }

    fun unfollow(channelId: String) {
        viewModelScope.launch {
            podcasts.unfollow(channelId)
            _channelShelves.value = _channelShelves.value - channelId
        }
    }
}
