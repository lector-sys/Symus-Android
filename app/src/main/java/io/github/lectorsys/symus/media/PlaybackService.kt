package io.github.lectorsys.symus.media

import android.app.PendingIntent
import android.content.Intent
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dagger.hilt.android.AndroidEntryPoint
import io.github.lectorsys.symus.MainActivity
import io.github.lectorsys.symus.data.LibraryRepository
import io.github.lectorsys.symus.data.SettingsRepository
import io.github.lectorsys.symus.data.TrackDao
import io.github.lectorsys.symus.widget.WidgetSnapshot
import io.github.lectorsys.symus.widget.WidgetUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import javax.inject.Inject

/**
 * The player, and the only thing that owns one.
 *
 * Playback outlives every screen: the service holds the ExoPlayer, the UI holds
 * a controller onto this session, and nothing in the UI layer may release it.
 * That is the whole reason this class exists rather than a player in a
 * ViewModel — a ViewModel dies with its screen, and a music player that stops
 * when you leave the app is not one anybody would use.
 *
 * It also owns everything that has to keep happening with no UI attached:
 * recording plays, accounting for skips, remembering where a track got to,
 * filling an exhausted queue, and saving the session so a cold start can pick
 * up where the last one left off.
 */
@UnstableApi
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    @Inject lateinit var extractor: Extractor
    @Inject lateinit var library: LibraryRepository
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var tracks: TrackDao
    @Inject lateinit var client: OkHttpClient
    @Inject lateinit var events: PlaybackEvents
    @Inject lateinit var widgets: WidgetUpdater

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var session: MediaSession? = null
    private lateinit var player: ExoPlayer
    private lateinit var resolver: SymusResolver

    /** Which track a play has not yet been recorded for, pending playback starting. */
    private var pendingPlay: String? = null

    /** Measured listening time. See [ListenClock] for why it ignores position. */
    private val listen = ListenClock()

    /** The in-flight recordPlay, joined before crediting time to its row. */
    private var playJob: Job? = null

    /** Attempts spent recovering one video, so a dead URL cannot loop. */
    private val retries = HashMap<String, Int>()

    /** Set while the service itself is skipping a broken track: not a user skip. */
    private var skippingAfterError = false

    /** Guards the autoplay top-up so it cannot run twice over the same tail. */
    private var filling = false

    /** Everything played this session — autoplay must not serve the same track twice. */
    private val playedThisSession = LinkedHashSet<String>()

    /**
     * Whether [restoreSession] has finished, successfully or not.
     *
     * Guards the one ordering that loses data. A cold start triggered by a
     * media button — a widget's play button, a headset click — creates the
     * service with an empty player and immediately produces player events. If
     * one of those reaches persist() before the restore coroutine has read the
     * saved queue, persist() sees zero items, concludes the queue is empty, and
     * clears the very session it was about to restore. The queue is gone, and
     * the only symptom is that play did nothing.
     */
    private var restoreAttempted = false

    override fun onCreate() {
        super.onCreate()

        resolver = SymusResolver(extractor, tracks, settings)
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(SymusResolver.factory(this, client, resolver))
            )
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus = */ true
            )
            // Pause when the headphones come out, rather than serenading the room.
            .setHandleAudioBecomingNoisy(true)
            // Keeps the CPU and the wifi radio alive between buffers with the
            // screen off, which is the normal way this app is used.
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        player.addListener(Listener())

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        session = MediaSession.Builder(this, player)
            .setSessionActivity(openApp)
            .build()

        scope.launch {
            settings.settings
                .map { it.skipSilence }
                .distinctUntilChanged()
                .collect { player.skipSilenceEnabled = it }
        }
        scope.launch { restoreSession() }
        scope.launch { keepState() }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /**
     * The user swiped the app away.
     *
     * Silence means they are done: the service stops so the notification goes
     * with the task. Mid-song it stays, because a swipe is not a pause.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        // Blocking, and deliberately not on `scope`: a launch here would be
        // cancelled by the scope.cancel() below before the write reached the
        // database, which is how the last few seconds of a session used to be
        // lost every time the service went away. Bounded so a wedged write
        // cannot hold up teardown.
        runCatching { runBlocking { withTimeout(PERSIST_TIMEOUT_MS) { persist() } } }
        // Same reasoning, separately bounded: a home screen still showing a
        // pause button for a player that no longer exists is a control that
        // does nothing, and the widget has no other way to find out.
        runCatching { runBlocking { withTimeout(PERSIST_TIMEOUT_MS) { widgets.markStopped() } } }
        session?.release()
        session = null
        player.release()
        scope.cancel()
        super.onDestroy()
    }

    // -- session persistence ------------------------------------------------

    /**
     * Rebuilds the last queue, paused and at the position it stopped.
     *
     * Only into an empty player: a cold start from a tapped notification or a
     * launcher icon has nothing queued, but a start caused by the UI asking to
     * play something must not have that snatched away a moment later.
     */
    private suspend fun restoreSession() {
        try {
            restore()
        } finally {
            // Set even when the restore bailed out: the point is that the
            // attempt has happened, so an empty player is now genuinely empty.
            restoreAttempted = true
        }
    }

    private suspend fun restore() {
        if (!settings.settings.value.restoreQueue) return
        val saved = settings.readSession()?.let(PlaybackSession::parse) ?: return
        if (player.mediaItemCount > 0) return
        val rows = library.byIds(saved.videoIds)
        if (rows.isEmpty()) return
        // Checked again after the read: byIds() suspends, and a tap on Play
        // during it would otherwise have its queue replaced by this one a
        // moment later.
        if (player.mediaItemCount > 0) return
        val index = saved.index.coerceIn(0, rows.lastIndex)
        player.setMediaItems(rows.map { it.toMediaItem() }, index, saved.positionMs)
        player.shuffleModeEnabled = saved.shuffle
        player.repeatMode = saved.repeat
        player.prepare()
    }

    /**
     * Writes the session and the current track's resume point every few seconds.
     *
     * Periodic rather than only on transitions: the process can be killed at any
     * moment, and a queue saved only when a song changes would lose a whole
     * track's worth of progress every time that happens.
     */
    private suspend fun keepState() {
        while (scope.isActive) {
            delay(STATE_INTERVAL_MS)
            if (player.isPlaying) {
                persist()
                rememberPosition(
                    player.currentMediaItem?.videoId(),
                    player.currentPosition,
                    player.duration
                )
                flushListening(restart = true)
                // Also on the tick, not only on events: a widget added in the
                // middle of a song has no state to render and no event coming
                // until the track changes. WidgetUpdater drops the repeats.
                pushWidget()
            }
        }
    }

    private suspend fun persist() {
        val ids = (0 until player.mediaItemCount).mapNotNull { player.getMediaItemAt(it).videoId() }
        if (ids.isEmpty()) {
            // An empty player before the restore has run is a player that has
            // not been filled in yet, not a queue the user emptied. See
            // [restoreAttempted].
            if (!restoreAttempted) return
            settings.clearSession()
            return
        }
        settings.saveSession(
            PlaybackSession(
                videoIds = ids,
                index = player.currentMediaItemIndex,
                positionMs = player.currentPosition.coerceAtLeast(0),
                shuffle = player.shuffleModeEnabled,
                repeat = player.repeatMode
            ).serialize()
        )
    }

    private suspend fun rememberPosition(videoId: String?, positionMs: Long, durationMs: Long) {
        if (videoId == null) return
        library.setResume(videoId, positionMs, durationMs)
    }

    /**
     * Banks the time since the clock was last started, against the track it was
     * started for, and restarts it if audio is still playing.
     *
     * Called on every state tick as well as on pause and on track change, so a
     * process killed mid-song loses at most one tick of credit rather than the
     * whole listen.
     *
     * The join matters: recordPlay inserts the history row this update targets,
     * and a pause arriving immediately after play could otherwise credit the
     * time to the previous row.
     */
    private suspend fun flushListening(restart: Boolean) {
        val credit = listen.sample(SystemClock.elapsedRealtime(), restart) ?: return
        if (!settings.settings.value.historyEnabled) return
        playJob?.join()
        library.addListened(credit.videoId, credit.ms)
    }

    // -- autoplay -----------------------------------------------------------

    /**
     * Tops up a queue that is about to run out.
     *
     * Repeat wins outright: with repeat on, the last track is not the end of the
     * queue, it is about to loop. Only a genuinely finishing queue is filled, and
     * only when the user has left autoplay on.
     */
    private fun fillIfEnding() {
        if (filling) return
        if (!settings.settings.value.autoplay) return
        if (player.repeatMode != Player.REPEAT_MODE_OFF) return
        if (player.mediaItemCount == 0) return
        if (player.currentMediaItemIndex < player.mediaItemCount - 1) return

        val seedId = player.currentMediaItem?.videoId() ?: return
        filling = true
        scope.launch {
            try {
                val seed = library.byId(seedId) ?: return@launch
                val more = library.similarTo(seed, playedThisSession + seedId, limit = 10)
                if (more.isNotEmpty()) player.addMediaItems(more.map { it.toMediaItem() })
            } catch (_: Throwable) {
                // Offline, rate limited, nothing similar. Autoplay degrading to
                // silence is acceptable; an error mid-playback is not.
            } finally {
                filling = false
            }
        }
    }

    // -- the home screen ----------------------------------------------------

    /**
     * Hands the widget what the player currently is.
     *
     * Read here, on the player's own thread, and posted rather than awaited:
     * the widget update writes to disk and may decode a bitmap, neither of
     * which playback is going to wait for.
     */
    private fun pushWidget() {
        val item = player.currentMediaItem?.toQueueItem()
        val snapshot = WidgetSnapshot(
            trackId = item?.videoId,
            title = item?.title,
            artist = item?.artist,
            playing = player.isPlaying,
            hasNext = player.hasNextMediaItem(),
            hasPrevious = player.hasPreviousMediaItem(),
            updatedAt = System.currentTimeMillis()
        )
        scope.launch { widgets.push(snapshot, item?.artworkUrl) }
    }

    // -- the listener -------------------------------------------------------

    private inner class Listener : Player.Listener {

        /**
         * One hook for the widget rather than a call in every callback below.
         *
         * onEvents is the callback that fires once after a batch of changes has
         * settled, which is exactly the granularity a home screen wants: a skip
         * is one update, not a transition plus a metadata change plus a playing
         * change three frames apart.
         */
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(
                    Player.EVENT_MEDIA_ITEM_TRANSITION,
                    Player.EVENT_IS_PLAYING_CHANGED,
                    Player.EVENT_MEDIA_METADATA_CHANGED,
                    Player.EVENT_TIMELINE_CHANGED
                )
            ) {
                pushWidget()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val incoming = mediaItem?.videoId()
            pendingPlay = incoming
            incoming?.let { playedThisSession.add(it) }
            fillIfEnding()
            scope.launch {
                // Bank what the outgoing track earned before the clock is
                // handed to the new one. flushListening reads listenTrack, which
                // is still the old id at this point.
                flushListening(restart = false)
                if (player.isPlaying) listen.start(incoming, SystemClock.elapsedRealtime())
                persist()
            }
        }

        /**
         * Where a skip is counted and where a resume point is written.
         *
         * Both need to know what the outgoing track was and how far it had got,
         * which is exactly what a discontinuity reports and what a transition
         * callback does not.
         */
        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            if (oldPosition.mediaItemIndex == newPosition.mediaItemIndex &&
                reason != Player.DISCONTINUITY_REASON_AUTO_TRANSITION
            ) {
                return
            }
            val outgoing = oldPosition.mediaItem?.videoId() ?: return
            val durationMs = (oldPosition.mediaItem?.toQueueItem()?.durationSec ?: 0L) * 1000
            val position = oldPosition.positionMs

            // A track the service abandoned because it would not load was not
            // skipped by anybody, and must not count against its genre.
            val userSkipped = !skippingAfterError &&
                (reason == Player.DISCONTINUITY_REASON_SEEK ||
                    reason == Player.DISCONTINUITY_REASON_SKIP) &&
                (durationMs <= 0 || position < durationMs * SKIP_THRESHOLD)
            skippingAfterError = false

            scope.launch {
                if (userSkipped && settings.settings.value.historyEnabled) {
                    library.markSkipped(outgoing)
                }
                rememberPosition(outgoing, position, durationMs)
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                val started = player.currentMediaItem?.videoId()
                val videoId = pendingPlay
                pendingPlay = null
                if (!settings.settings.value.historyEnabled) return
                if (videoId != null) playJob = scope.launch { library.recordPlay(videoId) }
                // The clock starts on audible audio, whether this is a fresh
                // track or a resume after a pause.
                listen.start(started, SystemClock.elapsedRealtime())
            } else {
                scope.launch {
                    flushListening(restart = false)
                    persist()
                    rememberPosition(
                        player.currentMediaItem?.videoId(),
                        player.currentPosition,
                        player.duration
                    )
                }
            }
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_READY) {
                player.currentMediaItem?.videoId()?.let { retries.remove(it) }
            }
            if (state == Player.STATE_ENDED) {
                fillIfEnding()
                scope.launch { persist() }
            }
        }

        /**
         * A stream that failed, recovered once and then given up on.
         *
         * The common case is a signed URL that expired between queueing and
         * playing, which one re-resolution fixes. Anything that fails twice is
         * skipped rather than retried further — an endless retry on a removed
         * video is how a player ends up stuck and silent.
         */
        override fun onPlayerError(error: PlaybackException) {
            val videoId = player.currentMediaItem?.videoId()
            val spent = videoId?.let { retries[it] } ?: MAX_RECOVERIES

            if (videoId != null && spent < MAX_RECOVERIES && recoverable(error)) {
                retries[videoId] = spent + 1
                resolver.invalidate(videoId)
                player.prepare()
                return
            }

            videoId?.let { retries.remove(it) }
            val moving = player.hasNextMediaItem()
            // Said before the seek, while the item that failed is still the
            // current one and can still be named.
            report(error, skipped = moving)
            if (moving) {
                skippingAfterError = true
                player.seekToNextMediaItem()
                player.prepare()
            } else {
                player.pause()
            }
        }

        /**
         * Tells the user which track was given up on and why.
         *
         * Reported from here rather than from the controller's own
         * onPlayerError: recovering synchronously means the session never
         * publishes the error, so the UI-side callback never runs.
         */
        private fun report(error: PlaybackException, skipped: Boolean) {
            val title = player.currentMediaItem?.mediaMetadata?.title?.toString()
            val extraction = generateSequence(error.cause) { it.cause }
                .filterIsInstance<ExtractionFailed>()
                .firstOrNull()
            val reason = extraction?.message
                ?: error.cause?.message
                ?: "it could not be played"
            events.say(
                when {
                    title == null -> reason
                    skipped -> "Skipped \"$title\" — $reason"
                    else -> "Cannot play \"$title\" — $reason"
                }
            )
        }

        private fun recoverable(error: PlaybackException): Boolean {
            val extraction = generateSequence(error.cause) { it.cause }
                .filterIsInstance<ExtractionFailed>()
                .firstOrNull()
            if (extraction != null) return extraction.retryable
            return error.errorCode in RECOVERABLE_CODES
        }
    }

    private companion object {
        const val STATE_INTERVAL_MS = 8_000L


        /** Long enough for one settings write, short enough not to stall teardown. */
        const val PERSIST_TIMEOUT_MS = 1_500L

        /** Past this much of a track, moving on is finishing it, not skipping it. */
        const val SKIP_THRESHOLD = 0.9

        const val MAX_RECOVERIES = 1

        val RECOVERABLE_CODES = setOf(
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE
        )
    }
}
