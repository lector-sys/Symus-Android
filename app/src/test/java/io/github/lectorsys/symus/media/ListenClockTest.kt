package io.github.lectorsys.symus.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The property that matters is negative: no amount of seeking can add time.
 *
 * These tests drive the clock the way the service does, with an explicit
 * monotonic reading, and never hand it a playback position — because there is
 * no parameter to hand one to. That is the guarantee, expressed as a type.
 */
class ListenClockTest {

    @Test
    fun `credits wall-clock time while playing`() {
        val clock = ListenClock()
        clock.start("a", 0)
        val credit = clock.sample(5_000, restart = false)
        assertEquals(ListenClock.Credit("a", 5_000), credit)
    }

    /**
     * The headline case. A user drags through a ten-minute track in two
     * seconds: two seconds of wall-clock elapse, so two seconds is what gets
     * credited, and the track's ten-minute duration never enters into it.
     */
    @Test
    fun `scrubbing through a long track credits only the seconds that passed`() {
        val clock = ListenClock()
        clock.start("long-track", 1_000)
        // However many seeks happened in between, the clock only ever sees this.
        val credit = clock.sample(3_000, restart = false)
        assertEquals(2_000, credit!!.ms)
    }

    @Test
    fun `checkpoints keep running and do not double count`() {
        val clock = ListenClock()
        clock.start("a", 0)
        assertEquals(8_000, clock.sample(8_000, restart = true)!!.ms)
        assertEquals(8_000, clock.sample(16_000, restart = true)!!.ms)
        // Total credited is 16s of a 16s listen, not 24s.
        assertEquals(4_000, clock.sample(20_000, restart = false)!!.ms)
        assertFalse(clock.running)
    }

    @Test
    fun `a track change banks time against the outgoing track`() {
        val clock = ListenClock()
        clock.start("first", 0)
        val credit = clock.sample(30_000, restart = false)
        assertEquals("first", credit!!.videoId)
        clock.start("second", 30_000)
        assertEquals("second", clock.sample(40_000, restart = false)!!.videoId)
    }

    @Test
    fun `a paused clock accrues nothing`() {
        val clock = ListenClock()
        clock.start("a", 0)
        clock.sample(5_000, restart = false)
        // Ten minutes pass with playback paused; nothing is running.
        assertNull(clock.sample(605_000, restart = false))
        assertFalse(clock.running)
    }

    @Test
    fun `a tap that starts and stops playback credits nothing`() {
        val clock = ListenClock()
        clock.start("a", 0)
        assertNull(clock.sample(200, restart = false))
    }

    /**
     * A sub-threshold checkpoint must not reset the clock, or a stream of them
     * would round a real listen away a sliver at a time.
     */
    @Test
    fun `short checkpoints accumulate instead of being discarded`() {
        val clock = ListenClock()
        clock.start("a", 0)
        assertNull(clock.sample(400, restart = true))
        assertNull(clock.sample(800, restart = true))
        assertEquals(1_200, clock.sample(1_200, restart = true)!!.ms)
    }

    @Test
    fun `a clock that was never started credits nothing`() {
        val clock = ListenClock()
        assertNull(clock.sample(10_000, restart = false))
        clock.start(null, 0)
        assertNull(clock.sample(10_000, restart = true))
    }

    @Test
    fun `a backwards clock reading cannot produce negative credit`() {
        val clock = ListenClock()
        clock.start("a", 10_000)
        assertNull(clock.sample(9_000, restart = false))
    }

    @Test
    fun `running reflects whether audio is being credited`() {
        val clock = ListenClock()
        assertFalse(clock.running)
        clock.start("a", 0)
        assertTrue(clock.running)
        assertEquals("a", clock.current)
        clock.stop()
        assertFalse(clock.running)
    }
}
