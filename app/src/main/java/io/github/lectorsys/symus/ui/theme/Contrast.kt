package io.github.lectorsys.symus.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.graphics.luminance
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Contrast, by the WCAG definition, and the functions that keep generated
 * themes readable.
 *
 * This exists because a palette pulled out of a photograph is not a design
 * decision — it is whatever happened to be in the picture. Someone will pick a
 * sunset and get a background one shade off its own caption. Every colour that
 * ends up behind text in this app goes through here first.
 *
 * Plain maths on plain floats, with no Android dependency, so it can be tested
 * on the JVM rather than on a device.
 */

/** WCAG 2.1 contrast ratio between two opaque colours. 1.0 to 21.0. */
fun contrastRatio(a: Color, b: Color): Float {
    val la = a.relativeLuminance()
    val lb = b.relativeLuminance()
    val lighter = max(la, lb)
    val darker = min(la, lb)
    return (lighter + 0.05f) / (darker + 0.05f)
}

/**
 * Compose's own `luminance()` already implements the WCAG formula, but only
 * meaningfully for sRGB. Converting first means a colour that arrived in a
 * wider space still gets scored on the scale the thresholds were written for.
 */
private fun Color.relativeLuminance(): Float =
    convert(ColorSpaces.Srgb).luminance().coerceIn(0f, 1f)

/** WCAG AA for body text. */
const val AA_NORMAL = 4.5f

/** WCAG AA for text at 18pt-plus, and for icons and UI boundaries. */
const val AA_LARGE = 3.0f

/**
 * The better of black or white to put on top of [background].
 *
 * Deliberately only two candidates. A mid-grey label on a mid-blue button
 * technically passes at some ratios and always looks like a mistake; near-black
 * and near-white are the two that read as intentional.
 *
 * Neither is pure: #0A0A0B and #FAFAFA, matching the rest of the palette, so an
 * accent button does not become the one place in the app with #000000 text on
 * it.
 */
fun readableOn(background: Color): Color {
    val onDark = Color(0xFFFAFAFA)
    val onLight = Color(0xFF0A0A0B)
    return if (contrastRatio(background, onLight) >= contrastRatio(background, onDark)) {
        onLight
    } else {
        onDark
    }
}

/**
 * Nudges [foreground] until it clears [minimum] against [background], keeping
 * its hue.
 *
 * Lightens or darkens depending on which direction has room — on a dark
 * background a dim colour is brightened, on a light one it is deepened. If
 * neither direction gets there (a fully saturated yellow on white cannot,
 * whatever you do to its lightness), it falls back to [readableOn], because a
 * legible label in the wrong hue beats an illegible one in the right hue.
 *
 * Returns [foreground] untouched when it already passes, so a well-chosen
 * colour is never "corrected" into something duller.
 */
fun ensureContrast(
    foreground: Color,
    background: Color,
    minimum: Float = AA_NORMAL
): Color {
    if (contrastRatio(foreground, background) >= minimum) return foreground

    val goLighter = background.relativeLuminance() < 0.5f
    var best = foreground
    var bestRatio = contrastRatio(foreground, background)

    // Twenty steps of 5%: fine enough that the result still looks like the
    // colour asked for, coarse enough to stay a loop rather than a solver.
    for (step in 1..20) {
        val amount = step * 0.05f
        val candidate = if (goLighter) foreground.lighten(amount) else foreground.darken(amount)
        val ratio = contrastRatio(candidate, background)
        if (ratio > bestRatio) {
            bestRatio = ratio
            best = candidate
        }
        if (ratio >= minimum) return candidate
    }
    return if (bestRatio >= minimum) best else readableOn(background)
}

/** Mixes toward white by [amount], 0..1. */
fun Color.lighten(amount: Float): Color = Color(
    red = red + (1f - red) * amount,
    green = green + (1f - green) * amount,
    blue = blue + (1f - blue) * amount,
    alpha = alpha
)

/** Mixes toward black by [amount], 0..1. */
fun Color.darken(amount: Float): Color = Color(
    red = red * (1f - amount),
    green = green * (1f - amount),
    blue = blue * (1f - amount),
    alpha = alpha
)

/**
 * How colourful this is, 0..1, as max-minus-min of the RGB channels.
 *
 * Used to reject palette candidates rather than to describe them: a photo of
 * snow yields a "colour" that is really just a grey, and promoting that to an
 * accent gives the user a theme whose highlight is invisible.
 */
fun Color.chroma(): Float {
    val hi = max(red, max(green, blue))
    val lo = min(red, min(green, blue))
    return hi - lo
}

/** True when two colours are close enough that showing both is pointless. */
fun Color.similarTo(other: Color, tolerance: Float = 0.10f): Boolean =
    abs(red - other.red) < tolerance &&
        abs(green - other.green) < tolerance &&
        abs(blue - other.blue) < tolerance
