package io.github.lectorsys.symus.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Palette extraction, on images built to have a known right answer.
 *
 * The interesting cases are all failures of the naive approach: the dominant
 * colour of a photograph is usually a grey nobody would choose, and a
 * black-and-white image has no accent in it at all. Both have to be handled
 * without producing an invisible highlight.
 */
class PaletteTest {

    private fun argb(color: Long) = color.toInt()

    /** An image made of the given colours in the given proportions. */
    private fun image(vararg parts: Pair<Long, Int>): IntArray {
        val out = ArrayList<Int>()
        parts.forEach { (color, count) -> repeat(count) { out += argb(color) } }
        return out.toIntArray()
    }

    @Test
    fun `an empty image yields no palette`() {
        assertTrue(palettesFrom(IntArray(0)).isEmpty())
    }

    @Test
    fun `a single-colour image yields that colour`() {
        val result = palettesFrom(image(0xFFE91E63 to 100))
        assertEquals(1, result.size)
        // Bucketing to 5 bits means the result is close, not bit-identical.
        assertTrue(result[0].accent.similarTo(Color(0xFFE91E63), 0.06f))
    }

    /**
     * The headline case. Three quarters of the image is a desaturated grey-blue
     * sky; the accent a person would name is the small saturated orange. A
     * plain "most common colour" implementation returns the sky.
     */
    @Test
    fun `a small saturated region beats a large desaturated one`() {
        val result = palettesFrom(
            image(
                0xFF8A93A0 to 750,   // sky, dominant by area
                0xFFFF6B1A to 250    // a jacket
            )
        )
        assertTrue("no palette produced", result.isNotEmpty())
        val top = result.first().accent
        assertTrue(
            "picked the sky (${top.toArgbHex()}) instead of the orange",
            top.chroma() > 0.4f
        )
    }

    @Test
    fun `a black and white image yields no accent rather than a grey one`() {
        val result = palettesFrom(
            image(
                0xFF000000 to 300,
                0xFF555555 to 300,
                0xFFAAAAAA to 200,
                0xFFFFFFFF to 200
            )
        )
        // Every candidate would be an invisible highlight, so none is offered
        // and the caller keeps its own accent.
        assertTrue("greys must not be promoted to accents", result.isEmpty())
    }

    @Test
    fun `transparent pixels are ignored`() {
        val result = palettesFrom(
            image(
                0x00FF0000L to 900,   // fully transparent red
                0xFF1E88E5 to 100    // an opaque blue
            )
        )
        assertEquals(1, result.size)
        assertTrue(result[0].accent.blue > result[0].accent.red)
    }

    @Test
    fun `a fully transparent image yields nothing`() {
        assertTrue(palettesFrom(image(0x00FF0000L to 500)).isEmpty())
    }

    @Test
    fun `distinct colours are offered separately and near-duplicates merged`() {
        val result = palettesFrom(
            image(
                0xFFE91E63 to 200,
                0xFFE8216A to 200,   // a hair away from the first
                0xFF1E88E5 to 200,
                0xFF43A047 to 200
            ),
            limit = 5
        )
        assertEquals("the two pinks should have merged", 3, result.size)
    }

    @Test
    fun `the limit is respected`() {
        val result = palettesFrom(
            image(
                0xFFE91E63 to 100, 0xFF1E88E5 to 100, 0xFF43A047 to 100,
                0xFFFFB300 to 100, 0xFF8E24AA to 100, 0xFF00ACC1 to 100
            ),
            limit = 3
        )
        assertEquals(3, result.size)
    }

    @Test
    fun `weights are shares of the image and sum to no more than one`() {
        val result = palettesFrom(image(0xFFE91E63 to 250, 0xFF1E88E5 to 750))
        assertTrue(result.all { it.weight in 0f..1f })
        assertTrue(result.sumOf { it.weight.toDouble() } <= 1.0001)
    }

    @Test
    fun `labels distinguish the common hues`() {
        assertEquals("Red", labelFor(Color(0xFFCC2222)))
        assertEquals("Blue", labelFor(Color(0xFF2266CC)))
        assertEquals("Green", labelFor(Color(0xFF22AA55)))
        assertEquals("Charcoal", labelFor(Color(0xFF111111)))
        assertEquals("Chalk", labelFor(Color(0xFFEFEFEF)))
        assertTrue(labelFor(Color(0xFF3A1010)).startsWith("Deep"))
        assertTrue(labelFor(Color(0xFFFFD0D0)).startsWith("Pale"))
    }

    /**
     * An accent has to survive both backgrounds. A near-black colour picked out
     * of a night photograph is useless as a highlight on a near-black page
     * until it has been pushed into range.
     */
    @Test
    fun `asAccent lifts a near-black into a visible band on dark`() {
        val nearBlack = Color(0xFF12060A)
        val accent = nearBlack.asAccent(forDark = true)
        assertTrue(
            "accent stayed invisible against the dark page",
            contrastRatio(accent, Color(0xFF0B0B0C)) >= AA_LARGE
        )
    }

    @Test
    fun `asAccent deepens a near-white into a visible band on light`() {
        val nearWhite = Color(0xFFFDF4F6)
        val accent = nearWhite.asAccent(forDark = false)
        assertTrue(
            "accent stayed invisible against the light page",
            contrastRatio(accent, Color(0xFFFCFCFD)) >= AA_LARGE
        )
    }

    @Test
    fun `asAccent leaves a colour that is already in band alone`() {
        val good = Color(0xFFF0B252)
        assertEquals(good, good.asAccent(forDark = true))
    }

    @Test
    fun `every extracted accent becomes usable on both backgrounds`() {
        // A spread of awkward inputs: very dark, very pale, fully saturated.
        val colors = listOf(
            0xFF12060A, 0xFFFDF4F6, 0xFFFF0000, 0xFF00FF00, 0xFF0000FF,
            0xFF7B1FA2, 0xFF004D40, 0xFFFFF176
        )
        colors.forEach { argb ->
            val onDark = Color(argb).asAccent(forDark = true)
            val onLight = Color(argb).asAccent(forDark = false)
            assertTrue(
                "0x${argb.toString(16)} unusable on dark",
                contrastRatio(onDark, Color(0xFF0B0B0C)) >= AA_LARGE
            )
            assertTrue(
                "0x${argb.toString(16)} unusable on light",
                contrastRatio(onLight, Color(0xFFFCFCFD)) >= AA_LARGE
            )
        }
    }

    private fun Color.toArgbHex() =
        "#%02X%02X%02X".format((red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt())
}
