package io.github.lectorsys.symus.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.lectorsys.symus.data.CacheSummary
import io.github.lectorsys.symus.data.DownloadEntity
import io.github.lectorsys.symus.data.LibraryRepository
import io.github.lectorsys.symus.data.PlaylistSummary
import io.github.lectorsys.symus.data.Backup
import io.github.lectorsys.symus.data.SettingsRepository
import io.github.lectorsys.symus.data.ThemeRepository
import io.github.lectorsys.symus.data.ThemeChoice
import io.github.lectorsys.symus.data.TrackEntity
import io.github.lectorsys.symus.media.AudioQuality
import io.github.lectorsys.symus.media.Downloads
import io.github.lectorsys.symus.widget.WidgetIntents
import io.github.lectorsys.symus.widget.WidgetUpdater
import io.github.lectorsys.symus.media.Playback
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The state and the actions every screen shares.
 *
 * Liking, saving, downloading and queueing are reachable from a dozen places,
 * and a copy of each in a dozen ViewModels is a dozen chances for them to
 * disagree. Screen-specific loading stays in screen-specific ViewModels; this
 * holds only what more than one screen needs.
 */
@UnstableApi
@HiltViewModel
class SymusViewModel @Inject constructor(
    private val library: LibraryRepository,
    private val settingsRepository: SettingsRepository,
    private val downloads: Downloads,
    private val themes: ThemeRepository,
    private val backup: Backup,
    private val widgets: WidgetUpdater,
    val playback: Playback
) : ViewModel() {

    val settings = settingsRepository.settings

    /**
     * The photo theme in use, or the default.
     *
     * Read here rather than in each screen so that the accent, the wallpaper
     * and what gets mirrored to the widgets are all the same value — four
     * places deriving it independently is four places for it to disagree.
     */
    val activeTheme = themes.active
    val playbackState = playback.state
    val downloadProgress = downloads.progress

    val cacheSummary = downloads.summary
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CacheSummary(0, 0))

    val likedIds = library.likedIds()
        .map { it.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val dislikedIds = library.dislikedIds()
        .map { it.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val savedIds = library.savedIds()
        .map { it.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val playlists = library.playlistSummaries()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList<PlaylistSummary>())

    val downloadQueue = downloads.queue
        .map { rows -> rows.associateBy { it.videoId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap<String, DownloadEntity>())

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** One place for anything worth a line at the bottom of the screen. */
    val messages = _messages.asSharedFlow()

    private val _widgetRoute = MutableStateFlow<String?>(null)

    /**
     * Where a widget tap wants the app to go, consumed once on arrival.
     *
     * A StateFlow rather than an event: the Activity can be recreated between
     * the intent arriving and the navigation host existing — a rotation during
     * cold start does exactly that — and an event fired into no collector is a
     * tap that silently did nothing.
     */
    val widgetRoute: StateFlow<String?> = _widgetRoute.asStateFlow()

    fun widgetRouteHandled() {
        _widgetRoute.value = null
    }

    /**
     * Tells the widgets what the app currently looks like.
     *
     * Called from the composition, because the composition is the only place
     * that knows: the accent is derived from the artwork on screen and the
     * surface comes out of the resolved colour scheme. A widget set to "Match
     * app" reads these two and nothing else.
     */
    fun mirrorTheme(accent: androidx.compose.ui.graphics.Color, surface: androidx.compose.ui.graphics.Color) {
        viewModelScope.launch { runCatching { widgets.setAppTheme(accent, surface) } }
    }

    /**
     * Acts on a tap that came from the home screen.
     *
     * Playing is resolved here rather than in the widget because this is where
     * the database is. A playlist that was deleted since the widget was
     * configured, or emptied since it was last synced, is reported rather than
     * silently doing nothing — from the home screen there is no other way to
     * find out what happened.
     */
    fun openFromWidget(destination: String?, playlistId: Long) {
        when (destination) {
            WidgetIntents.DESTINATION_NOW_PLAYING -> _widgetRoute.value = "nowplaying"
            WidgetIntents.DESTINATION_PLAYLIST -> _widgetRoute.value = "playlist/$playlistId"
            WidgetIntents.DESTINATION_PLAY_PLAYLIST -> viewModelScope.launch {
                val playlist = library.playlist(playlistId).first()
                if (playlist == null) {
                    say("That playlist was deleted")
                    _widgetRoute.value = "library"
                    return@launch
                }
                val tracks = library.playlistTracksNow(playlistId)
                if (tracks.isEmpty()) {
                    say("${playlist.title} is empty")
                    _widgetRoute.value = "playlist/$playlistId"
                    return@launch
                }
                play(tracks)
                _widgetRoute.value = "nowplaying"
            }
        }
    }

    init {
        // The player reports its own failures; they belong in the same place as
        // everything else the user is told.
        viewModelScope.launch { playback.events.collect { _messages.emit(it) } }
    }

    // -- backup --------------------------------------------------------------

    /**
     * Writes a backup to the document the user picked.
     *
     * The result is reported through the same snackbar as everything else,
     * including the failure: a backup that silently did not happen is the
     * worst possible outcome for a backup feature.
     */
    fun exportBackup(write: suspend (String) -> Boolean) {
        viewModelScope.launch {
            val json = runCatching { backup.export() }.getOrNull()
            if (json == null) {
                _messages.emit("Could not read your library to back it up.")
                return@launch
            }
            val ok = runCatching { write(json) }.getOrDefault(false)
            _messages.emit(
                if (ok) "Backup saved. It holds your library, playlists and history — " +
                    "not the downloaded audio."
                else "Could not write the backup file."
            )
        }
    }

    /** Merges a backup file in, and says exactly what it added. */
    fun restoreBackup(read: suspend () -> String?) {
        viewModelScope.launch {
            val json = runCatching { read() }.getOrNull()
            if (json.isNullOrBlank()) {
                _messages.emit("Could not read that file.")
                return@launch
            }
            val result = backup.restore(json)
            result.fold(
                onSuccess = { summary ->
                    _messages.emit(
                        "Restored ${summary.tracks} tracks, ${summary.playlists} playlists " +
                            "and ${summary.history} plays. Nothing already here was removed."
                    )
                },
                onFailure = { failure ->
                    _messages.emit(failure.message ?: "That file could not be restored.")
                }
            )
        }
    }

    fun say(message: String) {
        viewModelScope.launch { _messages.emit(message) }
    }

    // -- playback ----------------------------------------------------------

    fun play(tracks: List<TrackEntity>, index: Int = 0) = playback.playNow(tracks, index)

    fun playNext(track: TrackEntity) {
        playback.playNext(listOf(track))
        say("Playing next: ${track.title}")
    }

    fun addToQueue(tracks: List<TrackEntity>) {
        playback.addToQueue(tracks)
        say(if (tracks.size == 1) "Added to queue" else "Added ${tracks.size} tracks to queue")
    }

    fun shufflePlay(tracks: List<TrackEntity>) {
        if (tracks.isEmpty()) return
        playback.playNow(tracks.shuffled())
    }

    /**
     * An endless session seeded from one track.
     *
     * The seed plays first — "radio from this track" should start with it — and
     * the rest comes from the same scorer autoplay uses.
     */
    fun startRadio(seed: TrackEntity) {
        viewModelScope.launch {
            val rest = library.similarTo(seed, setOf(seed.videoId), limit = 20)
            if (rest.isEmpty()) {
                _messages.emit("Not enough to build a radio from \"${seed.title}\" yet")
                return@launch
            }
            playback.playNow(listOf(seed) + rest)
        }
    }

    // -- library -----------------------------------------------------------

    fun toggleLike(track: TrackEntity) {
        val liked = track.videoId in likedIds.value
        viewModelScope.launch {
            library.setLiked(track.videoId, !liked)
            if (!liked) _messages.emit("Added to liked songs")
        }
    }

    fun toggleDislike(track: TrackEntity) {
        val disliked = track.videoId in dislikedIds.value
        viewModelScope.launch {
            library.setDisliked(track.videoId, !disliked)
            if (!disliked) {
                _messages.emit("Not interested — less like \"${track.title}\" from now on")
            }
        }
    }

    fun toggleSaved(track: TrackEntity) {
        val saved = track.videoId in savedIds.value
        viewModelScope.launch {
            if (saved) {
                library.unsave(track.videoId)
                _messages.emit("Removed from your library")
            } else {
                library.save(track.videoId)
                _messages.emit("Saved to your library")
            }
        }
    }

    fun saveAll(tracks: List<TrackEntity>) {
        viewModelScope.launch {
            library.saveAll(tracks.map { it.videoId })
            _messages.emit("Saved ${tracks.size} tracks to your library")
        }
    }

    fun setGenre(track: TrackEntity, genre: String?) {
        viewModelScope.launch {
            val alsoTagged = library.setGenre(track.videoId, genre)
            _messages.emit(
                when {
                    genre == null -> "Genre cleared"
                    alsoTagged > 0 -> "Tagged $genre, and $alsoTagged more by ${track.artist}"
                    else -> "Tagged $genre"
                }
            )
        }
    }

    // -- playlists ---------------------------------------------------------

    fun addToPlaylist(playlistId: Long, tracks: List<TrackEntity>) {
        viewModelScope.launch {
            val added = library.addToPlaylist(playlistId, tracks.map { it.videoId })
            _messages.emit(
                when {
                    added == 0 -> "Already in that playlist"
                    added == 1 -> "Added to playlist"
                    else -> "Added $added tracks to playlist"
                }
            )
        }
    }

    fun createPlaylist(title: String, tracks: List<TrackEntity> = emptyList()) {
        viewModelScope.launch {
            library.createPlaylist(title, tracks.map { it.videoId })
            _messages.emit("Created \"$title\"")
        }
    }

    /**
     * Removes one track from a playlist, and offers it back.
     *
     * An undo rather than a confirmation: taking a track out of a playlist is
     * one tap away from being a mistake and one tap away from being fixed, so a
     * dialog in front of it would only slow down the nine times out of ten it
     * was meant.
     */
    fun removeFromPlaylist(playlistId: Long, track: TrackEntity) {
        viewModelScope.launch {
            val position = library.playlistTracksNow(playlistId)
                .indexOfFirst { it.videoId == track.videoId }
            library.removeFromPlaylist(playlistId, track.videoId)
            lastRemoval = Removal(playlistId, track, position)
            _messages.emit("Removed \"${track.title}\" from the playlist")
        }
    }

    private data class Removal(val playlistId: Long, val track: TrackEntity, val position: Int)

    private var lastRemoval: Removal? = null

    /** Puts the most recently removed track back where it was. */
    fun undoRemoval() {
        val removal = lastRemoval ?: return
        lastRemoval = null
        viewModelScope.launch {
            library.addToPlaylist(removal.playlistId, listOf(removal.track.videoId))
            if (removal.position >= 0) {
                val order = library.playlistTracksNow(removal.playlistId)
                    .map { it.videoId }
                    .toMutableList()
                order.remove(removal.track.videoId)
                order.add(removal.position.coerceAtMost(order.size), removal.track.videoId)
                library.reorderPlaylist(removal.playlistId, order)
            }
            _messages.emit("Put it back")
        }
    }

    fun renamePlaylist(id: Long, title: String) {
        viewModelScope.launch {
            library.renamePlaylist(id, title)
            _messages.emit("Renamed to \"$title\"")
        }
    }

    fun deletePlaylist(id: Long, title: String) {
        viewModelScope.launch {
            library.deletePlaylist(id)
            _messages.emit("Deleted \"$title\"")
        }
    }

    /** Writes a whole new running order, as dragged or nudged in the UI. */
    fun reorderPlaylist(id: Long, videoIdsInOrder: List<String>) {
        viewModelScope.launch { library.reorderPlaylist(id, videoIdsInOrder) }
    }

    fun clearHistory() {
        viewModelScope.launch {
            library.clearHistory()
            _messages.emit("Listening history cleared")
        }
    }

    fun clearSearchHistory() {
        viewModelScope.launch {
            library.clearSearches()
            _messages.emit("Search history cleared")
        }
    }

    fun removeSearch(query: String) {
        viewModelScope.launch { library.removeSearch(query) }
    }

    fun removeAllDownloads() {
        viewModelScope.launch {
            val count = downloads.removeAll()
            _messages.emit(if (count == 0) "Nothing was downloaded" else "Removed $count downloads")
        }
    }

    // -- downloads ---------------------------------------------------------

    fun download(track: TrackEntity) {
        viewModelScope.launch {
            downloads.enqueue(track.videoId)
            _messages.emit("Downloading \"${track.title}\"")
        }
    }

    fun downloadAll(tracks: List<TrackEntity>) {
        viewModelScope.launch {
            val pending = tracks.filter { it.cachedPath == null }
            downloads.enqueueAll(pending.map { it.videoId })
            _messages.emit(
                if (pending.isEmpty()) "Already available offline"
                else "Downloading ${pending.size} tracks"
            )
        }
    }

    fun cancelDownload(videoId: String) {
        viewModelScope.launch { downloads.cancel(videoId) }
    }

    fun retryDownload(videoId: String) {
        viewModelScope.launch { downloads.retry(videoId) }
    }

    fun removeDownload(track: TrackEntity) {
        viewModelScope.launch {
            downloads.remove(track.videoId)
            _messages.emit("Removed the offline copy of \"${track.title}\"")
        }
    }

    // -- settings ----------------------------------------------------------

    fun setTheme(choice: ThemeChoice) = viewModelScope.launch { settingsRepository.setTheme(choice) }

    fun setArtworkColors(on: Boolean) =
        viewModelScope.launch { settingsRepository.setArtworkColors(on) }

    fun setAutoplay(on: Boolean) = viewModelScope.launch { settingsRepository.setAutoplay(on) }

    fun setQuality(quality: AudioQuality) =
        viewModelScope.launch { settingsRepository.setQuality(quality) }

    fun setSkipSilence(on: Boolean) = viewModelScope.launch { settingsRepository.setSkipSilence(on) }

    fun setRestoreQueue(on: Boolean) =
        viewModelScope.launch { settingsRepository.setRestoreQueue(on) }

    fun setDownloadOnWifiOnly(on: Boolean) =
        viewModelScope.launch { settingsRepository.setDownloadOnWifiOnly(on) }

    fun setHistoryEnabled(on: Boolean) =
        viewModelScope.launch { settingsRepository.setHistoryEnabled(on) }
}
