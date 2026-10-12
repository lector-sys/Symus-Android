package io.github.lectorsys.symus.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guarantee behind "do not use extracted colours blindly".
 *
 * These are the cases a photo palette actually produces: a pale wash, a
 * near-black, a saturated yellow that no amount of darkening rescues on white.
 * Each one has to come out readable.
 */
class ContrastTest {

    private val white = Color(0xFFFFFFFF)
    private val black = Color(0xFF000000)

    @Test
    fun `known ratios match the WCAG reference values`() {
        // The two extremes of the scale.
        assertEquals(21f, contrastRatio(black, white), 0.01f)
        assertEquals(1f, contrastRatio(white, white), 0.01f)
        // Mid grey #767676 is the canonical "just passes AA on white" value.
        assertEquals(4.54f, contrastRatio(Color(0xFF767676), white), 0.05f)
    }

    @Test
    fun `ratio is symmetric`() {
        val a = Color(0xFF3366CC)
        val b = Color(0xFFEEDDAA)
        assertEquals(contrastRatio(a, b), contrastRatio(b, a), 0.0001f)
    }

    @Test
    fun `readableOn picks dark text for bright accents and light text for dark ones`() {
        assertEquals(Color(0xFF0A0A0B), readableOn(Color(0xFFF0B252)))
        assertEquals(Color(0xFF0A0A0B), readableOn(Color(0xFFFFF59D)))
        assertEquals(Color(0xFFFAFAFA), readableOn(Color(0xFF2B1B4A)))
        assertEquals(Color(0xFFFAFAFA), readableOn(Color(0xFF7B1FA2)))
    }

    @Test
    fun `whatever readableOn returns actually clears AA for large text`() {
        // Sampled across the hue circle at several lightnesses, which is the
        // range a photograph can hand us.
        val samples = listOf(
            0xFFF0B252, 0xFF2B1B4A, 0xFF8BC34A, 0xFFE91E63, 0xFF00BCD4,
            0xFF795548, 0xFFFFFFFF, 0xFF000000, 0xFF808080, 0xFFFF0000,
            0xFF00FF00, 0xFF0000FF, 0xFFFFFF00, 0xFF00FFFF, 0xFFFF00FF
        )
        samples.forEach { argb ->
            val background = Color(argb)
            val on = readableOn(background)
            val ratio = contrastRatio(background, on)
            assertTrue(
                "0x${argb.toString(16)} only reached $ratio with its chosen foreground",
                ratio >= AA_LARGE
            )
        }
    }

    @Test
    fun `a colour that already passes is left alone`() {
        val fg = Color(0xFFF1F1F3)
        assertEquals(fg, ensureContrast(fg, Color(0xFF0B0B0C)))
    }

    @Test
    fun `a dim accent on a dark background is brightened until it passes`() {
        val background = Color(0xFF0B0B0C)
        val dim = Color(0xFF2A2418)
        val fixed = ensureContrast(dim, background)
        assertNotEquals(dim, fixed)
        assertTrue(contrastRatio(fixed, background) >= AA_NORMAL)
    }

    @Test
    fun `a pale accent on a light background is deepened until it passes`() {
        val background = Color(0xFFFCFCFD)
        val pale = Color(0xFFF4E7C9)
        val fixed = ensureContrast(pale, background)
        assertNotEquals(pale, fixed)
        assertTrue(contrastRatio(fixed, background) >= AA_NORMAL)
    }

    /**
     * The case the fallback exists for: saturated yellow on white cannot reach
     * 4.5 by lightness alone, so the hue is abandoned rather than the
     * readability.
     */
    @Test
    fun `an impossible hue falls back to a legible foreground`() {
        val background = Color(0xFFFFFFFF)
        val fixed = ensureContrast(Color(0xFFFFFF00), background)
        assertTrue(contrastRatio(fixed, background) >= AA_NORMAL)
    }

    @Test
    fun `ensureContrast always reaches its target across the hue circle`() {
        val backgrounds = listOf(Color(0xFF0B0B0C), Color(0xFFFCFCFD), Color(0xFF808080))
        val foregrounds = listOf(
            0xFFF0B252, 0xFF2B1B4A, 0xFF8BC34A, 0xFFE91E63, 0xFF00BCD4,
            0xFF795548, 0xFF3F51B5, 0xFFCDDC39, 0xFF9E9E9E
        )
        backgrounds.forEach { bg ->
            foregrounds.forEach { argb ->
                val fixed = ensureContrast(Color(argb), bg, AA_LARGE)
                assertTrue(
                    "0x${argb.toString(16)} on $bg stayed unreadable",
                    contrastRatio(fixed, bg) >= AA_LARGE
                )
            }
        }
    }

    @Test
    fun `chroma separates a real colour from a grey`() {
        assertTrue(Color(0xFFE91E63).chroma() > 0.5f)
        assertTrue(Color(0xFF9E9E9E).chroma() < 0.05f)
        assertEquals(0f, Color(0xFF444444).chroma(), 0.001f)
    }

    @Test
    fun `similarTo catches near-duplicates and admits genuine differences`() {
        assertTrue(Color(0xFFF0B252).similarTo(Color(0xFFF2B65A)))
        assertTrue(!Color(0xFFF0B252).similarTo(Color(0xFF2B1B4A)))
    }

    @Test
    fun `lighten and darken move in the directions they claim`() {
        val c = Color(0xFF808080)
        assertTrue(c.lighten(0.5f).luminanceish() > c.luminanceish())
        assertTrue(c.darken(0.5f).luminanceish() < c.luminanceish())
        // Endpoints stay in gamut.
        assertEquals(1f, Color(0xFF808080).lighten(1f).red, 0.001f)
        assertEquals(0f, Color(0xFF808080).darken(1f).red, 0.001f)
    }

    /** Alpha must survive both operations, or scrims stop being scrims. */
    @Test
    fun `alpha is preserved`() {
        val translucent = Color(0x80FF8800)
        assertEquals(0.5f, translucent.lighten(0.3f).alpha, 0.01f)
        assertEquals(0.5f, translucent.darken(0.3f).alpha, 0.01f)
    }

    private fun Color.luminanceish() = red + green + blue
}
