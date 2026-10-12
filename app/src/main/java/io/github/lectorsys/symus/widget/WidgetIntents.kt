package io.github.lectorsys.symus.widget

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import androidx.glance.action.Action
import androidx.glance.appwidget.action.actionStartActivity
import io.github.lectorsys.symus.MainActivity

/**
 * Where a widget tap goes.
 *
 * All three open the app rather than poking the service directly, and that is
 * deliberate for the playlist ones: starting playback means resolving a stream,
 * which means a foreground service, which a background app is not allowed to
 * start on its own. An Activity launch from a widget tap always is — and once
 * the app is up it starts playback the same way a tap inside it would, through
 * the one session that already exists.
 *
 * Every intent carries a distinct `data` URI. Two PendingIntents that differ
 * only in their extras are the *same* PendingIntent as far as the system is
 * concerned, so without this a second playlist widget would silently inherit
 * the first one's playlist — a bug that only ever shows up on a home screen
 * with two of them.
 */
object WidgetIntents {

    const val EXTRA_DESTINATION = "symus.widget.destination"
    const val EXTRA_PLAYLIST_ID = "symus.widget.playlistId"

    const val DESTINATION_NOW_PLAYING = "nowplaying"
    const val DESTINATION_PLAYLIST = "playlist"
    const val DESTINATION_PLAY_PLAYLIST = "playPlaylist"

    fun openNowPlaying(context: Context): Action =
        actionStartActivity(intent(context, DESTINATION_NOW_PLAYING, null))

    fun openPlaylist(context: Context, playlistId: Long, play: Boolean): Action =
        actionStartActivity(
            intent(
                context,
                if (play) DESTINATION_PLAY_PLAYLIST else DESTINATION_PLAYLIST,
                playlistId
            )
        )

    private fun intent(context: Context, destination: String, playlistId: Long?): Intent =
        Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = "symus://widget/$destination/${playlistId ?: 0}".toUri()
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(EXTRA_DESTINATION, destination)
            if (playlistId != null) putExtra(EXTRA_PLAYLIST_ID, playlistId)
        }
}

/** Shorthand used by the now-playing widget. */
internal fun openNowPlaying(context: Context): Action = WidgetIntents.openNowPlaying(context)
