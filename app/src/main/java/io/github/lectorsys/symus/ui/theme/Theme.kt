package io.github.lectorsys.symus.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.dp
import io.github.lectorsys.symus.data.ThemeChoice

/**
 * The palette.
 *
 * Monochrome by construction: every surface, every divider and every piece of
 * text is a neutral grey, and the only chromatic value in the whole scheme is
 * the accent. That is the structural decision behind the look — colour in this
 * app is meant to come from album artwork, and artwork cannot sing over a
 * background that is already tinted. Warm greys compete with a warm sleeve;
 * neutral ones get out of its way.
 *
 * The accent is a parameter rather than a constant because a user-generated
 * theme replaces it wholesale. The greys never move: a photo the user picked
 * decides what "selected" looks like, not what the app is made of.
 */
val Amber = Color(0xFFF0B252)

/** Dark neutrals. Six steps, close together, so elevation reads without borders. */
private object Dark {
    val base = Color(0xFF0B0B0C)
    val lowest = Color(0xFF060607)
    val low = Color(0xFF101012)
    val container = Color(0xFF151517)
    val high = Color(0xFF1C1C1F)
    val highest = Color(0xFF232327)

    /** Soft white, never pure white: #FFFFFF on near-black is what makes eyes ache. */
    val text = Color(0xFFF1F1F3)

    /** Secondary text. Comfortably past AA for body sizes against [base]. */
    val muted = Color(0xFF9B9BA2)
    val outline = Color(0xFF3A3A3F)

    /** Hairline dividers. Meant to be felt rather than seen. */
    val divider = Color(0xFF232327)
}

private object Light {
    val base = Color(0xFFFCFCFD)
    val lowest = Color(0xFFFFFFFF)
    val low = Color(0xFFF6F6F8)
    val container = Color(0xFFF1F1F4)
    val high = Color(0xFFEAEAEE)
    val highest = Color(0xFFE2E2E8)
    val text = Color(0xFF131316)
    val muted = Color(0xFF5B5B63)
    val outline = Color(0xFFC6C6CE)
    val divider = Color(0xFFE5E5EA)
}

/**
 * Builds the scheme around one accent.
 *
 * Material wants a dozen related colours; these are derived from the accent
 * rather than hand-picked one by one, so that swapping the accent — which a
 * generated theme does — produces a coherent result instead of one amber
 * button left behind in a blue theme.
 *
 * `onPrimary` is computed, never assumed: an extracted colour can be anything,
 * and the label on top of it has to stay readable whether the user picked a
 * pale sky or a near-black plum. See [readableOn].
 */
fun symusColors(dark: Boolean, accent: Color): ColorScheme {
    val onAccent = readableOn(accent)
    return if (dark) {
        darkColorScheme(
            primary = accent,
            onPrimary = onAccent,
            primaryContainer = accent.copy(alpha = 0.22f).compositeOver(Dark.high),
            onPrimaryContainer = Dark.text,
            inversePrimary = accent,
            secondary = Dark.muted,
            onSecondary = Dark.base,
            secondaryContainer = Dark.high,
            onSecondaryContainer = Dark.text,
            tertiary = accent,
            onTertiary = onAccent,
            tertiaryContainer = Dark.high,
            onTertiaryContainer = Dark.text,
            background = Dark.base,
            onBackground = Dark.text,
            surface = Dark.base,
            onSurface = Dark.text,
            surfaceVariant = Dark.high,
            onSurfaceVariant = Dark.muted,
            surfaceTint = accent,
            surfaceContainerLowest = Dark.lowest,
            surfaceContainerLow = Dark.low,
            surfaceContainer = Dark.container,
            surfaceContainerHigh = Dark.high,
            surfaceContainerHighest = Dark.highest,
            inverseSurface = Dark.text,
            inverseOnSurface = Dark.base,
            outline = Dark.outline,
            outlineVariant = Dark.divider,
            error = Color(0xFFE5715C),
            onError = Color(0xFF2B0A05),
            errorContainer = Color(0xFF45150F),
            onErrorContainer = Color(0xFFFFDAD3),
            scrim = Color(0xFF000000)
        )
    } else {
        lightColorScheme(
            primary = accent,
            onPrimary = onAccent,
            primaryContainer = accent.copy(alpha = 0.20f).compositeOver(Light.lowest),
            onPrimaryContainer = Light.text,
            inversePrimary = accent,
            secondary = Light.muted,
            onSecondary = Light.lowest,
            secondaryContainer = Light.high,
            onSecondaryContainer = Light.text,
            tertiary = accent,
            onTertiary = onAccent,
            tertiaryContainer = Light.high,
            onTertiaryContainer = Light.text,
            background = Light.base,
            onBackground = Light.text,
            surface = Light.base,
            onSurface = Light.text,
            surfaceVariant = Light.high,
            onSurfaceVariant = Light.muted,
            surfaceTint = accent,
            surfaceContainerLowest = Light.lowest,
            surfaceContainerLow = Light.low,
            surfaceContainer = Light.container,
            surfaceContainerHigh = Light.high,
            surfaceContainerHighest = Light.highest,
            inverseSurface = Light.text,
            inverseOnSurface = Light.lowest,
            outline = Light.outline,
            outlineVariant = Light.divider,
            error = Color(0xFFB3261E),
            onError = Color(0xFFFFFFFF),
            errorContainer = Color(0xFFF9DEDC),
            onErrorContainer = Color(0xFF410E0B),
            scrim = Color(0xFF000000)
        )
    }
}

/**
 * Corner radii chosen per element, not one radius applied to everything.
 *
 * A 6dp chip and a 28dp sheet are different objects doing different jobs, and
 * giving them the same corner is the shortcut that makes an interface look
 * like a template. The scale roughly tracks the size of the thing it is
 * applied to.
 */
private val SymusShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

/**
 * The colour taken from the current artwork, for the surfaces that use it.
 *
 * Separate from the theme accent on purpose: the player tints itself from the
 * sleeve on screen right now, while the rest of the app keeps the accent the
 * user chose. Defaults to [Amber] so anything reading it is legible before an
 * image loads, and still is if the image never loads.
 */
val LocalArtworkAccent = staticCompositionLocalOf { Amber }

/** True when the app is currently rendering its dark scheme. */
val LocalIsDark = staticCompositionLocalOf { true }

@Composable
fun SymusTheme(
    choice: ThemeChoice = ThemeChoice.DARK,
    accent: Color = Amber,
    content: @Composable () -> Unit
) {
    val dark = when (choice) {
        ThemeChoice.DARK -> true
        ThemeChoice.LIGHT -> false
        ThemeChoice.SYSTEM -> isSystemInDarkTheme()
    }
    // asAccent before the scheme, not after. The colour arriving here is
    // sampled from artwork and deliberately dark — it exists to sit *behind*
    // light text as a backdrop. Used raw as `primary` it produced muddy,
    // low-contrast buttons and chips throughout the app; banded first, it is a
    // usable foreground colour with a guaranteed contrast ratio. The raw value
    // stays available as LocalArtworkAccent for the backdrops that want it.
    val scheme = remember(dark, accent) { symusColors(dark, accent.asAccent(dark)) }
    CompositionLocalProvider(
        LocalArtworkAccent provides accent,
        LocalIsDark provides dark
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = SymusTypography,
            shapes = SymusShapes,
            content = content
        )
    }
}

/**
 * The spacing scale. Four values, all multiples of 4dp.
 *
 * Deliberately small: every gap in the app is one of these, which is what makes
 * unrelated screens feel like the same product. A fifth value would be a fifth
 * opportunity for two screens to end up almost-but-not-quite aligned.
 */
object Space {
    /** Horizontal page margin. Everything lines up on this. */
    val page = 20.dp

    /** Between rows in a list, and between a title and the block under it. */
    val row = 12.dp

    /** Between sections. Large enough to group without needing a divider. */
    val section = 32.dp

    val tight = 8.dp
}
