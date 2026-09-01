package io.github.lectorsys.symus.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Mirrors the desktop smoke suite's genre section. Every case below is either a
 * rule this app relies on or a defect the desktop version already paid for once.
 */
class GenreRulesTest {

    @Test
    fun `infers from the title`() {
        assertEquals("Jazz", inferGenre("Best Jazz Standards"))
    }

    @Test
    fun `specific beats general`() {
        assertEquals("Lo-fi", inferGenre("lofi hip hop radio"))
    }

    @Test
    fun `no match is null`() {
        assertNull(inferGenre("asdfghjkl"))
    }

    @Test
    fun `empty input is null`() {
        assertNull(inferGenre(null, "", "   "))
    }

    // -- the three that were wrong once ------------------------------------

    @Test
    fun `a band name is not a genre`() {
        // Bare `punk` matched "ft. Daft Punk" and tagged The Weeknd as Rock.
        assertNull(inferGenre("Starboy ft. Daft Punk", "The Weeknd"))
    }

    @Test
    fun `a sitcom is not house music`() {
        // Bare `house` matched "Full House Theme Song" and filed it under
        // Electronic. It lands on Soundtrack now — via "theme song", which is a
        // fair reading of it — and the point of the case is the rule it must not
        // hit.
        assertNotEquals("Electronic", inferGenre("Full House Theme Song Long Version"))
        assertEquals("Soundtrack", inferGenre("Full House Theme Song Long Version"))
    }

    @Test
    fun `soul in a song title is not the genre`() {
        assertNull(inferGenre("Hey Soul Sister", "Train"))
    }

    // -- precedence --------------------------------------------------------

    @Test
    fun `the track's own text wins over the search it came from`() {
        // The exact case the desktop app fixed: found by searching for jazz, but
        // the track says metal, so it is Metal.
        assertEquals("Metal", genreFor("Thrash Metal Classics", "Some Band", "jazz essentials"))
    }

    @Test
    fun `the hint is used only when the track says nothing`() {
        assertEquals("Jazz", genreFor("Untitled Recording", "Someone", "jazz essentials"))
    }

    @Test
    fun `an unhelpful hint leaves the track untagged`() {
        assertNull(genreFor("Untitled Recording", "Someone", "my mix"))
    }

    @Test
    fun `realistic titles infer nothing on their own`() {
        // Why coverage is thin in practice: real titles carry no genre word.
        assertNull(inferGenre("The Weeknd - Blinding Lights (Official Video)", "TheWeekndVEVO"))
    }
}
