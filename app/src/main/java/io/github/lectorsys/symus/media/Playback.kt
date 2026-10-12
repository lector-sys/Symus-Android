package io.github.lectorsys.symus.media

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.lectorsys.symus.data.TrackEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the UI knows about playback.
 *
 * Built from the controller rather than from a store of its own: the player is
 * the only authority on what is playing, and a second copy of that state is a
 * second thing that can be wrong.
 */
data class PlaybackUiState(
    val current: QueueItem? = null,
    val queue: List<QueueItem> = emptyList(),
    val index: Int = -1,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val shuffle: Boolean = false,
    val repeat: Int = Player.REPEAT_MODE_OFF,
    val sleepAtMs: Long? = null,
    val sleepAtTrackEnd: Boolean = false,
    val connected: Boolean = false
) {
    val hasNext: Boolean get() = index in 0 until queue.lastIndex || repeat != Player.REPEAT_MODE_OFF
    val hasPrevious: Boolean get() = index > 0
}

/**
 * The app's handle on the playback service.
 *
 * Holds one controller for the whole process and never releases it from a
 * screen: the service owns the player, this owns the remote control, and
 * ViewModels own neither. A ViewModel calling release() here is the bug this
 * class exists to make impossible.
 */
@UnstableApi
@Singleton
class Playback @Inject constructor(
    @ApplicationContext private val context: Context,
    playbackEvents: PlaybackEvents
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val controllerFlow = MutableStateFlow<MediaController?>(null)

    private val _state = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

    /** Things worth telling the user once: a skipped track, a rate limit. */
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events: SharedFlow<String> = _events.asSharedFlow()

    private var sleepJob: Job? = null

    init {
        connect()
        scope.launch { tick() }
        // Anything the service had to say about a track it gave up on. It
        // cannot show a snackbar itself, and the session will not carry the
        // message because the service has already recovered from the error.
        scope.launch { playbackEvents.messages.collect { _events.emit(it) } }
    }

    private fun connect() {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token)
            .setListener(Reconnector())
            .buildAsync()
        future.addListener(
            {
                val controller = runCatching { future.get() }.getOrNull() ?: return@addListener
                controller.addListener(Listener())
                controllerFlow.value = controller
                publish(controller)
            },
            // The controller must be built and touched on the thread that owns it,
            // which for a Media3 controller is the main thread.
            MoreExecutors.directExecutor()
        )
    }

    private suspend fun awaitController(): MediaController = controllerFlow.filterNotNull().first()

    /**
     * Runs [block] against the controller, on the main thread, always.
     *
     * The fast path used to call straight through on whatever thread the caller
     * happened to be on. Every caller was a ViewModel or a composable, so that
     * thread was always the main one and it worked — right up until a
     * BroadcastReceiver handling a widget's skip button called next() from a
     * background dispatcher, and MediaController threw
     * "called from a wrong thread" and took the process down with it.
     *
     * `scope` is Main.immediate, so a caller already on the main thread still
     * runs inline with no dispatch: the fast path is kept, the foot-gun is not.
     */
    private fun command(block: (MediaController) -> Unit) {
        scope.launch { block(controllerFlow.value ?: awaitController()) }
    }

    /** Position is polled, because no callback fires while a track simply plays. */
    private suspend fun tick() {
        while (scope.isActive) {
            val controller = controllerFlow.value
            if (controller != null && controller.isPlaying) publish(controller)
            delay(POSITION_INTERVAL_MS)
        }
    }

    private fun publish(controller: MediaController) {
        val items = (0 until controller.mediaItemCount).map {
            controller.getMediaItemAt(it).toQueueItem()
        }
        _state.value = _state.value.copy(
            current = controller.currentMediaItem?.toQueueItem(),
            queue = items,
            index = controller.currentMediaItemIndex.takeIf { items.isNotEmpty() } ?: -1,
            playing = controller.isPlaying,
            buffering = controller.playbackState == Player.STATE_BUFFERING,
            positionMs = controller.currentPosition.coerceAtLeast(0),
            durationMs = controller.duration.takeIf { it > 0 }
                ?: ((controller.currentMediaItem?.toQueueItem()?.durationSec ?: 0L) * 1000),
            shuffle = controller.shuffleModeEnabled,
            repeat = controller.repeatMode,
            connected = true
        )
    }

    // -- commands ----------------------------------------------------------

    /**
     * Replaces the queue and starts playing.
     *
     * A track with somewhere to resume from starts there. Anything the user
     * explicitly picked starts from the beginning of that pick, which is why the
     * resume point is only honoured for the track being started, not for the
     * rest of the list.
     */
    fun playNow(tracks: List<TrackEntity>, startIndex: Int = 0) {
        if (tracks.isEmpty()) return
        val index = startIndex.coerceIn(0, tracks.lastIndex)
        val resumeFrom = tracks[index].resumeMs
        command { controller ->
            controller.setMediaItems(tracks.map { it.toMediaItem() }, index, resumeFrom)
            controller.prepare()
            controller.play()
        }
    }

    fun playNext(tracks: List<TrackEntity>) {
        if (tracks.isEmpty()) return
        command { controller ->
            if (controller.mediaItemCount == 0) {
                playNow(tracks)
            } else {
                controller.addMediaItems(
                    (controller.currentMediaItemIndex + 1).coerceAtMost(controller.mediaItemCount),
                    tracks.map { it.toMediaItem() }
                )
            }
        }
    }

    fun addToQueue(tracks: List<TrackEntity>) {
        if (tracks.isEmpty()) return
        command { controller ->
            if (controller.mediaItemCount == 0) playNow(tracks)
            else controller.addMediaItems(tracks.map { it.toMediaItem() })
        }
    }

    fun removeAt(index: Int) = command { controller ->
        if (index in 0 until controller.mediaItemCount) controller.removeMediaItem(index)
    }

    fun move(from: Int, to: Int) = command { controller ->
        val count = controller.mediaItemCount
        if (from in 0 until count && to in 0 until count && from != to) {
            controller.moveMediaItem(from, to)
        }
    }

    fun jumpTo(index: Int) = command { controller ->
        if (index in 0 until controller.mediaItemCount) {
            controller.seekTo(index, 0)
            controller.play()
        }
    }

    /** Clears everything but the track playing — "clear queue", not "stop". */
    fun clearUpcoming() = command { controller ->
        val current = controller.currentMediaItemIndex
        val last = controller.mediaItemCount
        if (last > current + 1) controller.removeMediaItems(current + 1, last)
    }

    fun stop() = command { controller ->
        controller.pause()
        controller.clearMediaItems()
        _state.value = PlaybackUiState(connected = true)
    }

    fun togglePlay() = command { controller ->
        if (controller.isPlaying) controller.pause()
        else {
            if (controller.playbackState == Player.STATE_IDLE) controller.prepare()
            controller.play()
        }
    }

    fun next() = command { controller ->
        // Repeat-one means "keep playing this", not "never let me move on".
        if (controller.hasNextMediaItem()) controller.seekToNextMediaItem()
        else if (controller.repeatMode == Player.REPEAT_MODE_ALL && controller.mediaItemCount > 0) {
            controller.seekTo(0, 0)
        }
    }

    /**
     * Back a track, or back to the start of this one.
     *
     * Media3's own seekToPrevious does exactly this, including the three-second
     * grace period people expect from every other player.
     */
    fun previous() = command { it.seekToPrevious() }

    fun seekTo(positionMs: Long) = command { it.seekTo(positionMs.coerceAtLeast(0)) }

    fun toggleShuffle() = command { it.shuffleModeEnabled = !it.shuffleModeEnabled }

    fun cycleRepeat() = command { controller ->
        controller.repeatMode = when (controller.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    // -- sleep timer -------------------------------------------------------

    /** Minutes from now, or null to cancel. Replaces any timer already set. */
    fun setSleepTimer(minutes: Int?) {
        sleepJob?.cancel()
        if (minutes == null) {
            _state.value = _state.value.copy(sleepAtMs = null, sleepAtTrackEnd = false)
            return
        }
        val deadline = System.currentTimeMillis() + minutes * 60_000L
        _state.value = _state.value.copy(sleepAtMs = deadline, sleepAtTrackEnd = false)
        sleepJob = scope.launch {
            delay(minutes * 60_000L)
            controllerFlow.value?.pause()
            _state.value = _state.value.copy(sleepAtMs = null)
            _events.tryEmit("Sleep timer reached — playback stopped")
        }
    }

    fun setSleepAtTrackEnd(on: Boolean) {
        sleepJob?.cancel()
        _state.value = _state.value.copy(sleepAtTrackEnd = on, sleepAtMs = null)
    }

    /**
     * Rebuilds the controller when the session goes away.
     *
     * The service stops itself when the app is swiped away with nothing playing,
     * and the system can kill it outright. Either leaves this holding a
     * controller that accepts commands and does nothing with them — so every
     * button in the app would be silently dead until the process restarted.
     * Reconnecting starts the service again on the next command, which is what
     * "leave the app and come back" has to do.
     *
     * Only ever one controller: the dead one is released before the new one is
     * asked for, so this cannot accumulate connections to the same session.
     */
    private inner class Reconnector : MediaController.Listener {
        override fun onDisconnected(controller: MediaController) {
            if (controllerFlow.value === controller) {
                controllerFlow.value = null
                _state.value = _state.value.copy(connected = false, playing = false)
            }
            controller.release()
            connect()
        }
    }

    private inner class Listener : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            controllerFlow.value?.let { publish(it) }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (_state.value.sleepAtTrackEnd &&
                reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
            ) {
                controllerFlow.value?.pause()
                _state.value = _state.value.copy(sleepAtTrackEnd = false)
                _events.tryEmit("Sleep timer reached — playback stopped")
            }
        }

        /**
         * Deliberately not reported here.
         *
         * The service handles every player error synchronously — one
         * re-resolution, then skip — so by the time a controller could observe
         * an error there is usually none left to observe, and this callback did
         * not fire at all for the common case of a removed video. The service
         * reports instead, through [PlaybackEvents]; reporting in both places
         * would say it twice on the errors that do reach here.
         */
        override fun onPlayerError(error: PlaybackException) = Unit
    }

    private companion object {
        const val POSITION_INTERVAL_MS = 500L
    }
}
