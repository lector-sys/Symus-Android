package io.github.lectorsys.symus.media

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The queue's round trip.
 *
 * This is what "consistent restoration" rests on: the session is written every
 * few seconds and read on every cold start, so a format that loses the index or
 * throws on an older shape is a player that comes back wrong — or does not come
 * back at all.
 */
class PlaybackSessionTest {

    private val session = PlaybackSession(
        videoIds = listOf("aaaaaaaaaaa", "bbbbbbbbbbb", "ccccccccccc"),
        index = 1,
        positionMs = 42_500,
        shuffle = true,
        repeat = Player.REPEAT_MODE_ALL
    )

    @Test
    fun `survives a round trip`() {
        assertEquals(session, PlaybackSession.parse(session.serialize()))
    }

    @Test
    fun `a single track round trips`() {
        val one = PlaybackSession(listOf("aaaaaaaaaaa"), 0, 0, false, Player.REPEAT_MODE_OFF)
        assertEquals(one, PlaybackSession.parse(one.serialize()))
    }

    // -- everything a stored line can be, having been written by an older build

    @Test
    fun `garbage is no session rather than a crash`() {
        assertNull(PlaybackSession.parse(""))
        assertNull(PlaybackSession.parse("nonsense"))
        assertNull(PlaybackSession.parse("a,b|0|0"))
        assertNull(PlaybackSession.parse("|3|0|0|0"))
    }

    @Test
    fun `an index past the end lands on the last track`() {
        // A queue that shrank between writing and reading: clamping is what keeps
        // setMediaItems from being handed an index it will reject.
        val parsed = PlaybackSession.parse("aaaaaaaaaaa,bbbbbbbbbbb|9|0|0|0")
        assertEquals(1, parsed?.index)
    }

    @Test
    fun `a negative position starts at zero`() {
        assertEquals(0L, PlaybackSession.parse("aaaaaaaaaaa|0|-500|0|0")?.positionMs)
    }

    @Test
    fun `an unknown repeat mode falls back to off`() {
        // Player rejects a repeat mode it does not define, and the fallback is
        // what stops a stored 7 taking the service down on launch.
        assertEquals(
            Player.REPEAT_MODE_OFF,
            PlaybackSession.parse("aaaaaaaaaaa|0|0|0|7")?.repeat
        )
    }

    @Test
    fun `unparseable numbers degrade rather than throw`() {
        val parsed = PlaybackSession.parse("aaaaaaaaaaa,bbbbbbbbbbb|x|y|0|z")
        assertEquals(0, parsed?.index)
        assertEquals(0L, parsed?.positionMs)
        assertEquals(Player.REPEAT_MODE_OFF, parsed?.repeat)
    }
}
