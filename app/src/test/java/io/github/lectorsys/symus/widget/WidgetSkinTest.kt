package io.github.lectorsys.symus.widget

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import io.github.lectorsys.symus.ui.theme.AA_LARGE
import io.github.lectorsys.symus.ui.theme.contrastRatio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A widget sits on a photograph the app has never seen, at whatever opacity the
 * user dragged the slider to, in whichever of four appearances they picked.
 * There is no design review that can check all of that, so the guarantee is
 * arithmetic: whatever the inputs, the text clears AA and the one coloured
 * control clears the large-object threshold.
 *
 * The inputs below are deliberately hostile — pure yellow, pure white, pure
 * black as "surfaces" — because a photo-derived theme can produce any of them
 * and the widget does not get to refuse.
 */
class WidgetSkinTest {

    private val hostileColors = listOf(
        Color.White,
        Color.Black,
        Color(0xFFFFFF00), // pure yellow: the classic contrast failure
        Color(0xFF00FF00),
        Color(0xFF0000FF),
        Color(0xFF808080),
        Color(0xFFF0B252), // the app's own amber
        Color(0xFF1A1A1A)
    )

    @Test
    fun `text clears AA against its own background in every appearance`() {
        for (appearance in WidgetAppearance.entries) {
            for (surface in hostileColors) {
                for (accent in hostileColors) {
                    for (opacity in listOf(WidgetConfig.MIN_OPACITY, 0.6f, 1f)) {
                        val skin = resolveSkin(
                            appearance,
                            opacity,
                            WidgetSnapshot(
                                appSurface = surface.toArgb(),
                                appAccent = accent.toArgb(),
                                artworkAccent = accent.toArgb()
                            )
                        )
                        // Against the opaque panel colour, which is what the
                        // text is actually drawn on once the launcher has
                        // composited it over the wallpaper.
                        val panel = skin.background.copy(alpha = 1f)
                        val ratio = contrastRatio(skin.ink, panel)
                        assertTrue(
                            "$appearance ink on $surface was $ratio",
                            ratio >= WIDGET_TEXT_CONTRAST
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `the accent stays visible as a large object`() {
        for (appearance in WidgetAppearance.entries) {
            for (surface in hostileColors) {
                val skin = resolveSkin(
                    appearance,
                    1f,
                    WidgetSnapshot(
                        appSurface = surface.toArgb(),
                        appAccent = Color(0xFFF0B252).toArgb(),
                        artworkAccent = Color(0xFF3355AA).toArgb()
                    )
                )
                val panel = skin.background.copy(alpha = 1f)
                val ratio = contrastRatio(skin.accent, panel)
                assertTrue("$appearance accent on $surface was $ratio", ratio >= AA_LARGE)
            }
        }
    }

    /**
     * The widget can be asked to draw before the app has ever pushed to it —
     * the first frame after it is placed, or after a reboot with the app never
     * having run. Every branch has to survive that.
     */
    @Test
    fun `an empty snapshot still produces a legible skin`() {
        for (appearance in WidgetAppearance.entries) {
            val skin = resolveSkin(appearance, 1f, WidgetSnapshot())
            val panel = skin.background.copy(alpha = 1f)
            assertTrue(
                "$appearance with no state",
                contrastRatio(skin.ink, panel) >= WIDGET_TEXT_CONTRAST
            )
        }
    }

    @Test
    fun `opacity is clamped rather than trusted`() {
        val transparent = resolveSkin(WidgetAppearance.DARK, 0f, WidgetSnapshot())
        assertEquals(WidgetConfig.MIN_OPACITY, transparent.background.alpha, 0.001f)

        val overshoot = resolveSkin(WidgetAppearance.DARK, 4f, WidgetSnapshot())
        assertEquals(1f, overshoot.background.alpha, 0.001f)
    }

    /**
     * Lowering the panel must not make text over artwork illegible: the scrim
     * scales with opacity but is never removed, which is the difference between
     * "let my wallpaper through" and "make this unreadable".
     */
    @Test
    fun `the scrim over artwork survives the lowest opacity`() {
        val skin = resolveSkin(WidgetAppearance.DARK, WidgetConfig.MIN_OPACITY, WidgetSnapshot())
        assertTrue("scrim was ${skin.scrimOverArt}", skin.scrimOverArt > 0.15f)
    }

    @Test
    fun `light and dark are genuinely different and ignore the app`() {
        val appIsWhite = WidgetSnapshot(appSurface = Color.White.toArgb())
        val dark = resolveSkin(WidgetAppearance.DARK, 1f, appIsWhite)
        val light = resolveSkin(WidgetAppearance.LIGHT, 1f, appIsWhite)

        assertTrue(dark.background.red < 0.2f)
        assertTrue(light.background.red > 0.8f)
        // And the explicit choices did not quietly follow the app's surface.
        assertTrue(dark.ink.red > 0.8f)
        assertTrue(light.ink.red < 0.2f)
    }

    @Test
    fun `match app follows the surface it was given`() {
        val pale = resolveSkin(
            WidgetAppearance.APP,
            1f,
            WidgetSnapshot(appSurface = Color(0xFFFAFAFA).toArgb())
        )
        val deep = resolveSkin(
            WidgetAppearance.APP,
            1f,
            WidgetSnapshot(appSurface = Color(0xFF0B0B0C).toArgb())
        )
        // The point of the feature: a photo theme that turns the app pale must
        // flip the widget's text to dark rather than leaving white on white.
        assertTrue(pale.ink.red < 0.2f)
        assertTrue(deep.ink.red > 0.8f)
    }

    /**
     * Regression. The accent's contrast is only guaranteed against the flat
     * panel, and a tan accent over a silver album sleeve passed that test while
     * being close to invisible on the home screen. Over artwork the control has
     * to fall back to the ink colour, which is chosen for the scrim.
     */
    @Test
    fun `a control over artwork uses ink rather than the accent`() {
        val skin = resolveSkin(
            WidgetAppearance.APP,
            1f,
            WidgetSnapshot(
                appSurface = Color(0xFF0B0B0C).toArgb(),
                appAccent = Color(0xFF8A7A5C).toArgb()
            )
        )
        assertEquals(skin.ink, skin.accentOver(artwork = true))
        assertEquals(skin.accent, skin.accentOver(artwork = false))
    }
}
