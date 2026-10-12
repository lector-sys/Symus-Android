package io.github.lectorsys.symus.library

import io.github.lectorsys.symus.data.ArtistListening
import io.github.lectorsys.symus.data.GenreCount
import io.github.lectorsys.symus.data.HistoryDao
import io.github.lectorsys.symus.data.TrackWithPlays
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A window of time, named, with the exact bounds it was computed for.
 *
 * The bounds travel with the answer on purpose. "You listened for six hours"
 * is not a fact until it says six hours *of what period*, and a window derived
 * inside a query cannot be quoted back or tested.
 */
data class Window(val label: String, val from: Long, val to: Long) {
    companion object {
        /** Everything, from the first play ever recorded. */
        fun allTime(now: Long) = Window("all time", 0L, now + 1)

        fun last7Days(now: Long) = Window("the last 7 days", now - 7 * DAY, now + 1)

        fun last30Days(now: Long) = Window("the last 30 days", now - 30 * DAY, now + 1)

        fun last365Days(now: Long) = Window("the last year", now - 365 * DAY, now + 1)

        /** Calendar week starting Monday, in the device's own time zone. */
        fun thisWeek(now: Long): Window {
            val calendar = Calendar.getInstance().apply {
                timeInMillis = now
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                firstDayOfWeek = Calendar.MONDAY
                set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            }
            return Window("this week", calendar.timeInMillis, now + 1)
        }

        const val DAY = 24L * 60 * 60 * 1000
    }
}

/**
 * What the numbers actually are, plus what is missing from them.
 *
 * [unmeasuredPlays] is not a footnote that can be dropped. A play recorded
 * before listening time was measured, or one cut short when the process died,
 * contributes nothing to [listenedMs]. Reporting the total without saying how
 * many plays are uncounted would quietly understate the figure and present a
 * guess as a measurement, which is the one thing these screens must not do.
 */
data class ListeningSummary(
    val window: Window,
    val listenedMs: Long,
    val plays: Int,
    val unmeasuredPlays: Int,
    val topArtists: List<ArtistListening>,
    val topTracks: List<TrackWithPlays>,
    val topGenres: List<GenreCount>
) {
    val isEmpty: Boolean get() = plays == 0

    /** True when enough of the window is unmeasured that the total misleads. */
    val measurementIncomplete: Boolean
        get() = unmeasuredPlays > 0 && unmeasuredPlays >= plays / 4
}

/**
 * Listening statistics, computed from the history table and nothing else.
 *
 * Every figure here is a count or a sum over rows the app wrote when audio was
 * actually coming out of the device — see [io.github.lectorsys.symus.media.ListenClock]
 * for why the durations are wall-clock and cannot be inflated by seeking.
 * Nothing is modelled, estimated, or filled in.
 */
@Singleton
class Listening @Inject constructor(
    private val history: HistoryDao
) {

    suspend fun summary(window: Window, limit: Int = 8): ListeningSummary =
        withContext(Dispatchers.IO) {
            ListeningSummary(
                window = window,
                listenedMs = history.listenedMsBetween(window.from, window.to),
                plays = history.playsBetween(window.from, window.to),
                unmeasuredPlays = history.unmeasuredPlaysBetween(window.from, window.to),
                topArtists = history.topArtistsBetween(window.from, window.to, limit),
                topTracks = history.topTracksBetween(window.from, window.to, limit),
                topGenres = history.topGenresBetween(window.from, window.to, limit)
            )
        }

    /** When the first play was recorded, or null if nothing has been played. */
    suspend fun since(): Long? = withContext(Dispatchers.IO) { history.earliestPlay() }
}

/** "4 hours 12 minutes", or "under a minute" — never a bare millisecond count. */
fun formatListening(ms: Long): String {
    if (ms < 60_000) return "under a minute"
    val minutes = ms / 60_000
    val hours = minutes / 60
    val rest = minutes % 60
    return when {
        hours == 0L -> "$minutes ${plural(minutes, "minute")}"
        rest == 0L -> "$hours ${plural(hours, "hour")}"
        else -> "$hours ${plural(hours, "hour")} $rest ${plural(rest, "minute")}"
    }
}

private fun plural(n: Long, word: String) = if (n == 1L) word else "${word}s"
