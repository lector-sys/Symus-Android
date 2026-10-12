package io.github.lectorsys.symus.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two pure halves of the lyrics feature.
 *
 * Both are ported from the desktop app, where the query cleaning is what lifted
 * LRCLIB from mostly-missing to 8 of 10 real tracks — so these cases are the
 * measurement, not a formality.
 */
class LyricsTest {

    @Test
    fun `strips what YouTube adds to a title`() {
        val (title, artist) = cleanQuery("The Weeknd - Blinding Lights (Official Video)", "TheWeekndVEVO")
        assertEquals("Blinding Lights", title)
        assertEquals("TheWeeknd", artist)
    }

    @Test
    fun `takes the artist from the title when the channel has none`() {
        val (title, artist) = cleanQuery("Arijit Singh - Tum Hi Ho", null)
        assertEquals("Tum Hi Ho", title)
        assertEquals("Arijit Singh", artist)
    }

    @Test
    fun `keeps a channel name that is already the artist`() {
        val (title, artist) = cleanQuery("Blinding Lights", "The Weeknd")
        assertEquals("Blinding Lights", title)
        assertEquals("The Weeknd", artist)
    }

    @Test
    fun `drops a Topic suffix`() {
        val (_, artist) = cleanQuery("Some Song", "Miles Davis - Topic")
        assertEquals("Miles Davis", artist)
    }

    @Test
    fun `drops featured artists and bracketed noise`() {
        val (title, _) = cleanQuery("Starboy (Official Audio) ft. Daft Punk", "The Weeknd")
        assertEquals("Starboy", title)
    }

    @Test
    fun `an empty title stays empty rather than becoming a lookup`() {
        val (title, _) = cleanQuery("(Official Video)", null)
        assertEquals("", title)
    }

    // -- LRC parsing -------------------------------------------------------

    @Test
    fun `parses timestamps in order`() {
        val lines = parseLrc(
            """
            [00:12.00]First line
            [00:15.50]Second line
            [01:05.00]Third line
            """.trimIndent()
        )
        assertEquals(3, lines.size)
        assertEquals(12.0, lines[0].timeSec, 0.001)
        assertEquals(15.5, lines[1].timeSec, 0.001)
        assertEquals(65.0, lines[2].timeSec, 0.001)
        assertEquals("First line", lines[0].text)
    }

    @Test
    fun `the fraction divisor scales with its digits`() {
        // Assuming centiseconds put "[01:05.5]" at 65.05s instead of 65.5s, which
        // is half a second of drift on every line of a synced file.
        assertEquals(65.5, parseLrc("[01:05.5]x").first().timeSec, 0.001)
        assertEquals(65.05, parseLrc("[01:05.05]x").first().timeSec, 0.001)
        assertEquals(65.005, parseLrc("[01:05.005]x").first().timeSec, 0.001)
    }

    @Test
    fun `lines without a timestamp are dropped`() {
        // A line shown at the wrong moment is worse than one not shown.
        val lines = parseLrc("[ar:Someone]\n[00:10.00]Real line\nstray text")
        assertEquals(1, lines.size)
        assertEquals("Real line", lines.first().text)
    }

    @Test
    fun `out of order input comes back sorted`() {
        val lines = parseLrc("[00:20.00]second\n[00:10.00]first")
        assertEquals(listOf("first", "second"), lines.map { it.text })
    }

    @Test
    fun `an empty file is no lines rather than a failure`() {
        assertTrue(parseLrc("").isEmpty())
    }
}
