package io.github.lectorsys.symus.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The check that makes a language model safe to put in front of someone's own
 * data: every figure it prints has to be one the database produced.
 *
 * These are the cases that actually happen. A small model asked to repeat "412"
 * will round it, restate it as a different unit, or occasionally produce a
 * number from nowhere, and all three look equally confident on screen.
 */
class GroundingTest {

    private val listening = ToolResult(
        tool = "listening_time",
        window = "the last 30 days",
        figures = mapOf(
            "listening_time" to "4 hours 12 minutes",
            "plays" to "37",
            "unmeasured_plays" to "0"
        )
    )

    private val artists = ToolResult(
        tool = "top_artists",
        window = "the last 30 days",
        figures = mapOf("artists_found" to "2"),
        rows = listOf(
            ToolRow("<<1>>", "Burial", "128 plays, 9 hours"),
            ToolRow("<<2>>", "Aphex Twin", "94 plays, 6 hours")
        )
    )

    @Test
    fun `a figure the tools produced passes`() {
        val answer = Grounding.check(
            "You listened for 4 hours 12 minutes across 37 plays.",
            listOf(listening)
        )
        assertEquals(emptyList<String>(), answer.ungroundedFigures)
        assertTrue(answer.trustworthy)
        assertEquals(ClaimKind.RECORDED, answer.kind)
    }

    @Test
    fun `a figure the tools never produced is caught`() {
        val answer = Grounding.check(
            "You listened for 6 hours across 512 plays.",
            listOf(listening)
        )
        assertTrue("512 came from nowhere", "512" in answer.ungroundedFigures)
        assertFalse(answer.trustworthy)
    }

    @Test
    fun `rounding a real figure is still an invented figure`() {
        val answer = Grounding.check("You listened for about 250 minutes.", listOf(listening))
        assertTrue("250" in answer.ungroundedFigures)
        assertFalse(answer.trustworthy)
    }

    @Test
    fun `placeholders become the real names the model never saw`() {
        val answer = Grounding.check(
            "Your most played were <<1>> and then <<2>>.",
            listOf(artists)
        )
        assertEquals("Your most played were Burial and then Aphex Twin.", answer.text)
        assertTrue(answer.trustworthy)
    }

    /**
     * A reference to a row that was never supplied is the model inventing a
     * name. Dropping it is the only safe reading: substituting something else
     * would attribute plays to an artist at random.
     */
    @Test
    fun `a placeholder with no row behind it is dropped, not guessed`() {
        val answer = Grounding.check("You played <<1>> and <<9>> most.", listOf(artists))
        assertFalse(answer.text.contains("<<"))
        assertTrue(answer.text.contains("Burial"))
        assertFalse(answer.text.contains("Aphex Twin"))
    }

    /**
     * The model invents markers it was never given. Asked for a figure it wrote
     * `<<180>>`, which matched no row and reached the screen intact.
     */
    @Test
    fun `a marker the model invented never reaches the screen`() {
        val answer = Grounding.check(
            "You have listened for a total of <<180>> minutes this month.",
            listOf(listening)
        )
        assertFalse("A raw marker leaked: ${answer.text}", answer.text.contains("<<"))
        // "this month" is rewritten to the window the figures actually came
        // from — see `the period is corrected to the one the figures came from`.
        assertEquals(
            "You have listened for a total of minutes over the last 30 days.",
            answer.text
        )
    }

    /**
     * The right numbers under the wrong heading.
     *
     * Asked about all time, the 0.5B model returned the all-time totals and
     * then wrote "in the last 7 days" after them. Every figure was correct and
     * the sentence was false. The window is the app's own string, so it is
     * written in rather than hoped for.
     */
    @Test
    fun `the period is corrected to the one the figures came from`() {
        val allTime = ToolResult(
            tool = "listening_time",
            window = "all time",
            figures = mapOf("listening_time" to "1 hour 40 minutes", "plays" to "10")
        )
        val answer = Grounding.check(
            "You listened for 1 hour 40 minutes across 10 plays in the last 7 days.",
            listOf(allTime)
        )
        assertEquals(
            "You listened for 1 hour 40 minutes across 10 plays over all time.",
            answer.text
        )
        assertTrue(answer.trustworthy)
    }

    @Test
    fun `a correct period is left alone`() {
        val answer = Grounding.check(
            "You listened for 4 hours 12 minutes across 37 plays in the last 30 days.",
            listOf(listening)
        )
        assertTrue("The period was already right", answer.text.contains("the last 30 days"))
        assertTrue(answer.trustworthy)
    }

    /** With two windows in play there is no single right period to impose. */
    @Test
    fun `mixed windows are left for the number check to judge`() {
        val week = ToolResult(
            tool = "top_artists",
            window = "this week",
            figures = mapOf("artists_found" to "1")
        )
        val text = "You played 37 plays in the last 30 days and 1 artist this week."
        val answer = Grounding.check(text, listOf(listening, week))
        assertEquals("Nothing should have been rewritten", text, answer.text)
    }

    @Test
    fun `small numbers are not treated as claims`() {
        // "1." and "2." are list markers in every model's output.
        val answer = Grounding.check("Top 2 artists: 1. <<1>> 2. <<2>>", listOf(artists))
        assertTrue(answer.ungroundedFigures.isEmpty())
    }

    /**
     * The hole the magnitude exemption used to leave.
     *
     * A real answer from the 0.5B model read "total_play_time = 7 hours" when
     * the tools had produced one hour. Seven is below the small-number
     * threshold, so nothing caught it, and a fabricated duration reached the
     * screen looking exactly like a measured one.
     */
    @Test
    fun `a small number carrying a unit is still a claim`() {
        val answer = Grounding.check("You listened for 7 hours this month.", listOf(listening))
        assertTrue("7 hours came from nowhere", "7" in answer.ungroundedFigures)
        assertFalse(answer.trustworthy)
    }

    @Test
    fun `a small figure the tools did produce still passes`() {
        val short = ToolResult(
            tool = "listening_time",
            window = "the last 7 days",
            figures = mapOf("listening_time" to "3 hours", "plays" to "9")
        )
        val answer = Grounding.check("You listened for 3 hours across 9 plays.", listOf(short))
        assertEquals(emptyList<String>(), answer.ungroundedFigures)
        assertTrue(answer.trustworthy)
    }

    /** The window is the app's own wording, so quoting it is not inventing. */
    @Test
    fun `the window a tool reports is quotable`() {
        val answer = Grounding.check(
            "You listened for 4 hours 12 minutes over the last 30 days.",
            listOf(listening)
        )
        assertEquals(emptyList<String>(), answer.ungroundedFigures)
        assertTrue(answer.trustworthy)
    }

    /**
     * The answer that says nothing and looks fine.
     *
     * A real run produced "You have listened for hours in the last days." after
     * the model wrapped its figures in row markers and the cleanup stripped
     * them. No number survives, so the ungrounded-figure check has nothing to
     * object to — this is the check that does.
     */
    @Test
    fun `an answer with its figures stripped out is detected`() {
        assertFalse(
            Grounding.quotesAnyFigure("You have listened for hours in the last days.", listOf(listening))
        )
        assertTrue(
            Grounding.quotesAnyFigure("You listened for 4 hours 12 minutes.", listOf(listening))
        )
    }

    @Test
    fun `an answer with no figures to quote is not held to account for them`() {
        val feature = ToolResult(tool = "app_feature", note = "Backup writes a file you keep.")
        assertTrue(Grounding.quotesAnyFigure("Backup writes a file you keep.", listOf(feature)))
    }

    /**
     * The recorded answer is written by the app, not the model.
     *
     * These strings are what the assistant screen shows above the prose, so
     * they have to carry the exact value and the period it covers whatever the
     * model wrote — including when the model wrote nonsense.
     */
    @Test
    fun `the facts card states exact figures with their period`() {
        val nonsense = Grounding.check("You listened for 9 hours across 512 plays.", listOf(listening))
        assertEquals(
            listOf(
                "Time listened: 4 hours 12 minutes (the last 30 days)",
                "Plays: 37 (the last 30 days)",
                "Plays predating measurement: 0 (the last 30 days)"
            ),
            nonsense.facts
        )
        // …and the prose that disagreed with them is marked untrustworthy.
        assertFalse(nonsense.trustworthy)
    }

    @Test
    fun `a tool with no period still states its figures`() {
        val counts = ToolResult(
            tool = "library_counts",
            figures = mapOf("tracks" to "128", "playlists" to "4")
        )
        val answer = Grounding.check("You have 128 tracks in 4 playlists.", listOf(counts))
        assertEquals(listOf("Tracks: 128", "Playlists: 4"), answer.facts)
        assertTrue(answer.trustworthy)
    }

    @Test
    fun `an empty result set is reported as unavailable rather than answered`() {
        val nothing = ToolResult(tool = "top_artists", window = "this week", sufficient = false)
        val answer = Grounding.check("There is not enough recorded yet.", listOf(nothing))
        assertEquals(ClaimKind.UNAVAILABLE, answer.kind)
    }

    @Test
    fun `a suggestion is not labelled as a record`() {
        val suggestion = ToolResult(
            tool = "suggest_tracks",
            rows = listOf(ToolRow("<<1>>", "Untrue", "in library", videoId = "abc")),
            note = "These are candidates, not a recorded preference."
        )
        val answer = Grounding.check("You could play <<1>>.", listOf(suggestion))
        assertEquals(ClaimKind.SUGGESTED, answer.kind)
    }

    @Test
    fun `sources name the table an answer came from`() {
        val answer = Grounding.check("...", listOf(listening, artists))
        assertTrue(answer.sources.any { it.contains("play history") })
        assertTrue(answer.sources.all { it.contains("the last 30 days") })
    }
}
