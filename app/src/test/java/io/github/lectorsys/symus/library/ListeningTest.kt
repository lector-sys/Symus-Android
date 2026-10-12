package io.github.lectorsys.symus.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * The arithmetic behind every figure the Listening screen and the assistant
 * put on screen.
 *
 * These are the parts that can be wrong quietly. A window computed a day out,
 * or a duration rounded the wrong way, produces a number that looks completely
 * reasonable and is not true — and the whole point of counting rather than
 * estimating is that the result can be checked.
 */
class ListeningTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `windows end after now so the current moment is inside them`() {
        // A half-open window ending exactly at `now` excludes a play recorded
        // this millisecond, which is the one most likely to be checked.
        listOf(
            Window.allTime(now),
            Window.last7Days(now),
            Window.last30Days(now),
            Window.last365Days(now),
            Window.thisWeek(now)
        ).forEach { window ->
            assertTrue(window.label, window.to > now)
            assertTrue(window.label, window.from <= now)
        }
    }

    @Test
    fun `the named windows span what they claim`() {
        assertEquals(7 * Window.DAY, Window.last7Days(now).let { it.to - 1 - it.from })
        assertEquals(30 * Window.DAY, Window.last30Days(now).let { it.to - 1 - it.from })
        assertEquals(365 * Window.DAY, Window.last365Days(now).let { it.to - 1 - it.from })
    }

    @Test
    fun `all time starts at the epoch rather than a guess`() {
        assertEquals(0L, Window.allTime(now).from)
    }

    @Test
    fun `this week starts on a Monday midnight`() {
        val week = Window.thisWeek(now)
        val calendar = Calendar.getInstance().apply { timeInMillis = week.from }
        assertEquals(Calendar.MONDAY, calendar.get(Calendar.DAY_OF_WEEK))
        assertEquals(0, calendar.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, calendar.get(Calendar.MINUTE))
        assertTrue(week.from <= now)
    }

    // -- durations, as people say them --------------------------------------

    @Test
    fun `durations read as sentences`() {
        assertEquals("under a minute", formatListening(0))
        assertEquals("under a minute", formatListening(59_000))
        assertEquals("1 minute", formatListening(60_000))
        assertEquals("2 minutes", formatListening(120_000))
        assertEquals("1 hour", formatListening(3_600_000))
        assertEquals("1 hour 1 minute", formatListening(3_660_000))
        assertEquals("2 hours 30 minutes", formatListening(9_000_000))
    }

    @Test
    fun `duration never rounds up into a figure that was not listened to`() {
        // 59.9 seconds is not "1 minute": the total has to be a floor of what
        // actually happened, never a flattering round-up.
        assertEquals("under a minute", formatListening(59_999))
    }

    // -- the honesty flag ---------------------------------------------------

    @Test
    fun `measurement is flagged incomplete once a quarter of plays are unmeasured`() {
        fun summary(plays: Int, unmeasured: Int) = ListeningSummary(
            window = Window.allTime(now),
            listenedMs = 1000,
            plays = plays,
            unmeasuredPlays = unmeasured,
            topArtists = emptyList(),
            topTracks = emptyList(),
            topGenres = emptyList()
        )

        assertFalse(summary(plays = 100, unmeasured = 0).measurementIncomplete)
        assertFalse(summary(plays = 100, unmeasured = 5).measurementIncomplete)
        assertTrue(summary(plays = 100, unmeasured = 25).measurementIncomplete)
        assertTrue(summary(plays = 4, unmeasured = 1).measurementIncomplete)
    }

    @Test
    fun `an empty window is empty rather than zeroed`() {
        val empty = ListeningSummary(
            window = Window.last7Days(now),
            listenedMs = 0,
            plays = 0,
            unmeasuredPlays = 0,
            topArtists = emptyList(),
            topTracks = emptyList(),
            topGenres = emptyList()
        )
        assertTrue(empty.isEmpty)
        // No plays means nothing to be incomplete about; the screen must say
        // "nothing recorded", not "the total is understated".
        assertFalse(empty.measurementIncomplete)
    }
}
