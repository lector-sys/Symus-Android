package io.github.lectorsys.symus.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp
import io.github.lectorsys.symus.R

/**
 * Manrope, bundled, under the SIL Open Font License 1.1.
 *
 * The licence ships with it at assets/licenses/manrope-OFL.txt, which is what
 * the OFL asks for and what a font dropped into res/font without one does not
 * do.
 *
 * One variable file rather than five static cuts: 165 KB covers every weight
 * from 200 to 800, where five instances would cost roughly four times that for
 * fewer options. Chosen over the alternatives for the reason that matters in a
 * list of song titles — a tall x-height and open apertures, so "Illusion" and
 * "IIIusion" are still different words at 13sp on a phone held at arm's length.
 *
 * Latin, Cyrillic and Greek are in the file. Everything else — CJK, Devanagari,
 * Arabic, Thai, emoji — resolves through the platform's own fallback chain,
 * which is the only sane way to cover it: a font that actually contained CJK
 * would be larger than the rest of this APK put together. See
 * [io.github.lectorsys.symus.ui.components.multilingualSamples] for the strings
 * this was checked against on a device, because a fallback chain that silently
 * renders tofu looks exactly like one that works until you look at it.
 */
private fun manrope(weight: Int) = Font(
    R.font.manrope_variable,
    FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight))
)

val Manrope = FontFamily(
    manrope(300),
    manrope(400),
    manrope(500),
    manrope(600),
    manrope(700),
    manrope(800)
)

/**
 * Line height trimmed at neither end.
 *
 * A song title that wraps to two lines has to stay vertically aligned with the
 * one-line title in the row above it, and trimming only the first line is how
 * that alignment drifts.
 */
private val trim = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None
)

/**
 * The type scale.
 *
 * Five sizes doing real work, not thirteen. A music app is two lines of text
 * repeated a thousand times — a title and the artist under it — so the pair
 * that carries the whole product is [Typography.titleMedium] over
 * [Typography.bodySmall], and everything else exists to frame them.
 *
 * Negative tracking on the large sizes only. Display type set at its default
 * spacing looks loose; body text at 13sp set tight becomes a smear, so the
 * adjustment reverses sign as the size falls rather than being applied
 * uniformly. No uppercase anywhere and no letter-spaced labels: a track called
 * "Ånd" should look like its own name, not like a section header.
 */
val SymusTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = Manrope, fontSize = 34.sp, lineHeight = 40.sp,
        fontWeight = FontWeight.W700, letterSpacing = (-0.8).sp, lineHeightStyle = trim
    ),
    headlineLarge = TextStyle(
        fontFamily = Manrope, fontSize = 28.sp, lineHeight = 34.sp,
        fontWeight = FontWeight.W700, letterSpacing = (-0.6).sp, lineHeightStyle = trim
    ),
    headlineMedium = TextStyle(
        fontFamily = Manrope, fontSize = 22.sp, lineHeight = 28.sp,
        fontWeight = FontWeight.W700, letterSpacing = (-0.4).sp, lineHeightStyle = trim
    ),
    headlineSmall = TextStyle(
        fontFamily = Manrope, fontSize = 19.sp, lineHeight = 25.sp,
        fontWeight = FontWeight.W600, letterSpacing = (-0.3).sp, lineHeightStyle = trim
    ),
    titleLarge = TextStyle(
        fontFamily = Manrope, fontSize = 17.sp, lineHeight = 23.sp,
        fontWeight = FontWeight.W600, letterSpacing = (-0.2).sp, lineHeightStyle = trim
    ),
    /** The song title. The single most-rendered style in the app. */
    titleMedium = TextStyle(
        fontFamily = Manrope, fontSize = 15.sp, lineHeight = 20.sp,
        fontWeight = FontWeight.W600, letterSpacing = (-0.1).sp, lineHeightStyle = trim
    ),
    titleSmall = TextStyle(
        fontFamily = Manrope, fontSize = 13.sp, lineHeight = 18.sp,
        fontWeight = FontWeight.W600, lineHeightStyle = trim
    ),
    bodyLarge = TextStyle(
        fontFamily = Manrope, fontSize = 15.sp, lineHeight = 22.sp,
        fontWeight = FontWeight.W400, lineHeightStyle = trim
    ),
    bodyMedium = TextStyle(
        fontFamily = Manrope, fontSize = 14.sp, lineHeight = 20.sp,
        fontWeight = FontWeight.W400, lineHeightStyle = trim
    ),
    /** The artist line. Sits under a W600 title, so W400 carries the hierarchy. */
    bodySmall = TextStyle(
        fontFamily = Manrope, fontSize = 13.sp, lineHeight = 17.sp,
        fontWeight = FontWeight.W400, letterSpacing = 0.1.sp, lineHeightStyle = trim
    ),
    labelLarge = TextStyle(
        fontFamily = Manrope, fontSize = 14.sp, lineHeight = 18.sp,
        fontWeight = FontWeight.W600, lineHeightStyle = trim
    ),
    labelMedium = TextStyle(
        fontFamily = Manrope, fontSize = 12.sp, lineHeight = 16.sp,
        fontWeight = FontWeight.W600, letterSpacing = 0.1.sp, lineHeightStyle = trim
    ),
    /** Durations, counts, timestamps. Tabular-ish at this size and weight. */
    labelSmall = TextStyle(
        fontFamily = Manrope, fontSize = 11.sp, lineHeight = 14.sp,
        fontWeight = FontWeight.W500, letterSpacing = 0.2.sp, lineHeightStyle = trim
    )
)
