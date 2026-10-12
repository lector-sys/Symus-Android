package io.github.lectorsys.symus.media

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What playback needs to tell the user, from the half of the app that has no UI.
 *
 * The service is where a dead track is discovered and abandoned, and it deals
 * with the failure synchronously — it re-resolves once, then skips on. That is
 * the right behaviour and it has an awkward consequence: the session never
 * publishes an error state, so a controller's `onPlayerError` never fires and
 * the UI had no idea anything had gone wrong. The track simply vanished and the
 * next one started, which looks like a bug in the app rather than a removed
 * video.
 *
 * Service and UI share a process, so one small shared flow is enough to carry
 * the message across. Deliberately not the MediaSession's own error channel:
 * that is for controllers outside the app, and it would report a failure the
 * app has already recovered from.
 */
@Singleton
class PlaybackEvents @Inject constructor() {

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** One line at a time, for whoever is showing the snackbar. */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    fun say(message: String) {
        _messages.tryEmit(message)
    }
}
