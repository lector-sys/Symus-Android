package io.github.lectorsys.symus.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.lectorsys.symus.data.AlbumSummary
import io.github.lectorsys.symus.data.LibraryRepository
import io.github.lectorsys.symus.data.PlaylistEntity
import io.github.lectorsys.symus.data.TrackEntity
import io.github.lectorsys.symus.media.Downloads
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * One ViewModel for every track list.
 *
 * Each of these screens is "some rows, from somewhere", and the somewhere is the
 * only difference. A class per screen would be six copies of the same two
 * fields — this holds those two fields and lets the screen say where to fill
 * them from.
 *
 * Navigation gives each screen its own instance, so two open lists never share
 * state.
 */
@UnstableApi
@HiltViewModel
class CollectionViewModel @Inject constructor(
    private val library: LibraryRepository,
    private val downloads: Downloads
) : ViewModel() {

    private val _tracks = MutableStateFlow<List<TrackEntity>>(emptyList())
    val tracks: StateFlow<List<TrackEntity>> = _tracks.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _playlist = MutableStateFlow<PlaylistEntity?>(null)
    val playlist: StateFlow<PlaylistEntity?> = _playlist.asStateFlow()

    private val _albums = MutableStateFlow<List<AlbumSummary>>(emptyList())
    val albums: StateFlow<List<AlbumSummary>> = _albums.asStateFlow()

    private val _titles = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Titles for ids the screen only knows as ids — the download queue's rows. */
    val titles: StateFlow<Map<String, String>> = _titles.asStateFlow()

    private var watching: Job? = null
    private var watchingPlaylist: Job? = null

    fun liked() = watch(library.likedTracks())

    fun saved() = watch(library.savedTracks())

    fun offline() = watch(downloads.offlineTracks)

    /** Most recently played first, distinct — a raw play log repeats the same song. */
    fun history() = watch(library.recentlyPlayedFlow(200))

    fun playlist(id: Long) {
        watch(library.playlistTracks(id))
        watchingPlaylist?.cancel()
        watchingPlaylist = viewModelScope.launch {
            library.playlist(id).collect { _playlist.value = it }
        }
    }

    fun genre(name: String) = load { library.tracksByGenre(name, 500) }

    fun artist(name: String) {
        load { library.tracksByArtist(name, 500) }
        viewModelScope.launch { _albums.value = library.albumsOf(name) }
    }

    fun album(name: String, artist: String?) = load { library.albumTracks(name, artist) }

    fun resolveTitles(videoIds: List<String>) {
        if (videoIds.isEmpty()) {
            _titles.value = emptyMap()
            return
        }
        viewModelScope.launch {
            _titles.value = library.byIds(videoIds).associate { it.videoId to it.title }
        }
    }

    /** A live list: likes and playlists change while their screen is open. */
    private fun watch(flow: Flow<List<TrackEntity>>) {
        watching?.cancel()
        _loading.value = true
        watching = viewModelScope.launch {
            flow.collect {
                _tracks.value = it
                _loading.value = false
            }
        }
    }

    /** A snapshot: a genre or an album does not change under the user's finger. */
    private fun load(block: suspend () -> List<TrackEntity>) {
        watching?.cancel()
        _loading.value = true
        watching = viewModelScope.launch {
            _tracks.value = block()
            _loading.value = false
        }
    }
}
