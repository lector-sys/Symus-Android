package io.github.lectorsys.symus.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pulling a usable palette out of a photograph.
 *
 * Deliberately pure: it takes an `IntArray` of packed ARGB and returns colours,
 * with no Android type anywhere in the signature. Colour quantisation is fiddly
 * enough that it needs tests, and a function that wants a `Bitmap` can only be
 * tested on a device. [io.github.lectorsys.symus.ui.theme.ImagePalette] is the
 * thin Android side that decodes and downsamples before calling in here.
 *
 * The algorithm is a weighted histogram, not k-means. A photo has to become a
 * palette in the time it takes a sheet to open, on a phone that may be six
 * years old, and repeatedly as the user drags a crop around. Histogram
 * bucketing is one pass over the pixels; k-means is a dozen passes for a result
 * no one could pick out of a line-up.
 */

/** A generated palette: what to accent with, and what it came from. */
data class PaletteCandidate(
    val accent: Color,
    /** How much of the image this colour speaks for, 0..1. Drives the ordering. */
    val weight: Float,
    /** A short human label, so the choices are distinguishable in a list. */
    val label: String
)

/** Bits kept per channel when bucketing. 5 gives 32 levels, i.e. 32768 buckets. */
private const val BITS = 5
private const val LEVELS = 1 shl BITS

/**
 * Candidate palettes for an image, best first.
 *
 * Scoring is population times "usefulness as an accent", and the second term is
 * what stops this returning the sky. A photograph is mostly one or two large,
 * desaturated regions — sky, skin, wall, shadow — and the literal dominant
 * colour of almost any image is a muddy grey-brown that makes an invisible
 * highlight. Chroma is weighted heavily and the extreme ends of lightness are
 * pushed down, so what comes back is the colour a person would point at.
 *
 * Returns at most [limit] visually distinct colours; near-duplicates are merged
 * rather than offered twice.
 */
fun palettesFrom(pixels: IntArray, limit: Int = 5): List<PaletteCandidate> {
    if (pixels.isEmpty()) return emptyList()

    val population = HashMap<Int, Int>(1024)
    var counted = 0

    for (argb in pixels) {
        val alpha = (argb ushr 24) and 0xFF
        // A transparent pixel is not part of the picture.
        if (alpha < 128) continue
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        val key = (r shr (8 - BITS)) shl (BITS * 2) or
            ((g shr (8 - BITS)) shl BITS) or
            (b shr (8 - BITS))
        population[key] = (population[key] ?: 0) + 1
        counted++
    }
    if (counted == 0) return emptyList()

    val scored = population.entries.map { (key, count) ->
        val color = keyToColor(key)
        val share = count.toFloat() / counted
        Scored(color, share, share * accentSuitability(color))
    }
        .filter { it.color.chroma() >= MIN_ACCENT_CHROMA }
        .sortedByDescending { it.score }

    val chosen = ArrayList<Scored>(limit)
    for (candidate in scored) {
        if (chosen.size >= limit) break
        // Merging is by perceptual-ish closeness, so a palette does not offer
        // four shades of the same jumper.
        if (chosen.none { it.color.similarTo(candidate.color, MERGE_TOLERANCE) }) {
            chosen += candidate
        }
    }

    // Nothing colourful at all — a black-and-white photograph, a screenshot of
    // text. Rather than returning greys that would make an invisible accent,
    // say so by returning an empty list and let the caller keep its default.
    if (chosen.isEmpty()) return emptyList()

    return chosen.map { PaletteCandidate(it.color, it.share, labelFor(it.color)) }
}

private class Scored(val color: Color, val share: Float, val score: Float)

private const val MERGE_TOLERANCE = 0.16f

/**
 * Below this chroma a colour is a grey, and a grey accent is an invisible one.
 *
 * Applied as a hard filter rather than a low score: a black-and-white photo has
 * no accent in it, and the honest answer is to offer none and let the app keep
 * its own, not to return the least-grey grey and call it a palette.
 */
private const val MIN_ACCENT_CHROMA = 0.10f

private fun keyToColor(key: Int): Color {
    val r = (key shr (BITS * 2)) and (LEVELS - 1)
    val g = (key shr BITS) and (LEVELS - 1)
    val b = key and (LEVELS - 1)
    // Scale the bucket back to the middle of the range it represents rather than
    // its floor, or every colour comes back slightly too dark.
    fun expand(v: Int) = ((v * 255f / (LEVELS - 1)).roundToInt()).coerceIn(0, 255)
    return Color(expand(r), expand(g), expand(b))
}

/**
 * How well a colour would work as an accent, 0..1.
 *
 * Three factors: it has to be colourful, it has to be in the middle of the
 * lightness range, and it must not be so dark or so pale that it disappears
 * against one of the two app backgrounds.
 */
private fun accentSuitability(color: Color): Float {
    val chroma = color.chroma()
    val lightness = (max(color.red, max(color.green, color.blue)) +
        min(color.red, min(color.green, color.blue))) / 2f

    // Squared so a genuinely saturated colour beats a nearly-grey one decisively
    // rather than by a nose.
    val chromaTerm = chroma * chroma

    // Peaks at mid lightness, falls off towards both ends.
    val lightnessTerm = 1f - abs(0.5f - lightness) * 1.6f

    return (chromaTerm * lightnessTerm.coerceAtLeast(0.02f)).coerceIn(0f, 1f)
}

/**
 * A rough colour name.
 *
 * Only has to be good enough to tell four swatches apart in a picker; nobody is
 * navigating by these. Greys get named by lightness because "grey" three times
 * would be useless.
 */
internal fun labelFor(color: Color): String {
    val r = color.red
    val g = color.green
    val b = color.blue
    val hi = max(r, max(g, b))
    val lo = min(r, min(g, b))
    val chroma = hi - lo
    val lightness = (hi + lo) / 2f

    if (chroma < 0.08f) {
        return when {
            lightness < 0.2f -> "Charcoal"
            lightness < 0.45f -> "Slate"
            lightness < 0.75f -> "Stone"
            else -> "Chalk"
        }
    }

    val hue = when {
        hi == r && g >= b -> 60f * ((g - b) / chroma)
        hi == r -> 360f + 60f * ((g - b) / chroma)
        hi == g -> 120f + 60f * ((b - r) / chroma)
        else -> 240f + 60f * ((r - g) / chroma)
    }

    val name = when {
        hue < 15f || hue >= 345f -> "Red"
        hue < 45f -> "Amber"
        hue < 70f -> "Gold"
        hue < 100f -> "Lime"
        hue < 160f -> "Green"
        hue < 195f -> "Teal"
        hue < 240f -> "Blue"
        hue < 280f -> "Indigo"
        hue < 320f -> "Violet"
        else -> "Rose"
    }
    return when {
        lightness < 0.28f -> "Deep $name"
        lightness > 0.72f -> "Pale $name"
        else -> name
    }
}

/** The two page colours an accent has to survive. Kept in step with [symusColors]. */
private val DarkPage = Color(0xFF0B0B0C)
private val LightPage = Color(0xFFFCFCFD)

/**
 * Forces a colour into a band that works as an accent on the given background.
 *
 * Applied after the user has chosen, not before: the picker shows the colours
 * actually in the photograph, and this is what makes the chosen one survive
 * contact with a near-black page and a near-white one.
 *
 * Two stages, and the second is the one that matters. Nudging towards a target
 * *lightness* gets most colours into a sensible range, but lightness is not
 * contrast: pure green sits at mid-lightness and has a relative luminance of
 * 0.72, so it passes every lightness check and is still illegible on white.
 * Only the explicit contrast pass can promise the result is visible, so that is
 * what the guarantee rests on.
 */
fun Color.asAccent(forDark: Boolean): Color {
    val page = if (forDark) DarkPage else LightPage
    val target = if (forDark) 0.62f else 0.42f
    val hi = max(red, max(green, blue))
    val lo = min(red, min(green, blue))
    val lightness = (hi + lo) / 2f

    val banded = when {
        abs(lightness - target) < 0.12f -> this
        lightness < target ->
            lighten((target - lightness) / (1f - lightness).coerceAtLeast(0.01f))
        else -> darken((lightness - target) / lightness.coerceAtLeast(0.01f))
    }
    return ensureContrast(banded, page, AA_LARGE)
}
