package io.github.lectorsys.symus.widget

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import dagger.hilt.android.AndroidEntryPoint
import io.github.lectorsys.symus.media.Playback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * Skip controls from a widget, routed through the app's own controller.
 *
 * Media3's `MediaButtonReceiver` refuses to forward anything except a play
 * command while the service is not already in the foreground — it logs
 * "Ignore key event that is not a `play` command on API 26 or above" and drops
 * it, to avoid promising a foreground service it cannot deliver. That is the
 * right call for a headset button and completely wrong for a widget: with
 * playback paused, previous and next silently did nothing, which reads as a
 * dead widget rather than a platform restriction.
 *
 * So the skip buttons come here instead. This binds the existing session
 * through [Playback] — a bind, not a foreground start, which the background
 * restrictions permit — and issues the command on the one controller the app
 * already owns. Play stays on the media button path, because starting playback
 * from nothing is exactly the case Media3's receiver is built to handle.
 */
@UnstableApi
@AndroidEntryPoint
class WidgetControlReceiver : BroadcastReceiver() {

    @Inject lateinit var playback: Playback
    @Inject lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        // The broadcast is finished only once the command has been issued.
        // Returning immediately would let the system tear the process down
        // mid-connect, which is a skip that works only when the app happens to
        // already be running.
        val pending = goAsync()
        scope.launch {
            try {
                // The controller connects lazily; commands issued before it is
                // ready are queued by Playback, but waiting here keeps the
                // receiver alive long enough for that to actually happen.
                withTimeoutOrNull(CONNECT_TIMEOUT_MS) { playback.state.first { it.connected } }
                when (action) {
                    ACTION_NEXT -> playback.next()
                    ACTION_PREVIOUS -> playback.previous()
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_NEXT = "io.github.lectorsys.symus.widget.NEXT"
        const val ACTION_PREVIOUS = "io.github.lectorsys.symus.widget.PREVIOUS"

        private const val CONNECT_TIMEOUT_MS = 5_000L

        /**
         * A skip intent aimed at this receiver.
         *
         * The distinct data URI is not cosmetic: two PendingIntents that differ
         * only in their action are still distinct, but adding it here keeps the
         * rule consistent with [WidgetIntents] and survives a future extra.
         */
        fun intent(context: Context, action: String): Intent =
            Intent(action).apply {
                component = ComponentName(context, WidgetControlReceiver::class.java)
                data = "symus://widget/control/$action".toUri()
            }
    }
}
