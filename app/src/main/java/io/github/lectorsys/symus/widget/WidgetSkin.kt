package io.github.lectorsys.symus.widget

import androidx.compose.ui.graphics.Color
import io.github.lectorsys.symus.ui.theme.AA_LARGE
import io.github.lectorsys.symus.ui.theme.AA_NORMAL
import io.github.lectorsys.symus.ui.theme.Amber
import io.github.lectorsys.symus.ui.theme.darken
import io.github.lectorsys.symus.ui.theme.ensureContrast
import io.github.lectorsys.symus.ui.theme.readableOn

/**
 * The colours one widget instance draws itself in.
 *
 * Resolved from the appearance the user picked plus whatever the app last told
 * the widget about itself, and resolved *here* rather than inline in the
 * composables so that the one property that matters — that the text can be read
 * — is a function with a test rather than four call sites hoping.
 */
data class WidgetSkin(
    /** The panel behind everything, already carrying the configured opacity. */
    val background: Color,
    /** Primary text. Chosen for contrast against [background], never assumed. */
    val ink: Color,
    /** Secondary text: the same ink, stepped back. */
    val muted: Color,
    /**
     * The one coloured element — the play control, when the panel is flat.
     *
     * Only meaningful over [background]. Over artwork the caller uses [ink]
     * instead: see [accentOver].
     */
    val accent: Color,
    /** Alpha of the panel when it sits on top of artwork instead of replacing it. */
    val scrimOverArt: Float
)

/** Neutral dark, the default for everything that has no better idea. */
private val NeutralDark = Color(0xFF0E0D0C)
private val NeutralLight = Color(0xFFFAFAFB)

/**
 * How much of the artwork the scrim covers.
 *
 * Artwork is the point of the widget, so the scrim is light enough to see
 * through — but text over an arbitrary album cover is unreadable without one,
 * and 0.62 is where white text survives a pale cover. Multiplied by the
 * configured opacity, never replaced by it: a user who turns the panel down is
 * asking for a lighter background, not for illegible text over a photograph.
 */
private const val SCRIM_BASE = 0.62f

/**
 * The colours for [appearance], guaranteed legible.
 *
 * [snapshot] supplies the two colours the widget cannot know on its own: what
 * the app's theme currently is, and what the current artwork is tinted like.
 * Both are nullable, because a widget can be asked to draw before either has
 * ever been pushed to it — the first frame after it is placed, or after a
 * reboot with the app never having run. Every branch falls back to a neutral
 * that works rather than to a colour that might not.
 */
fun resolveSkin(
    appearance: WidgetAppearance,
    opacity: Float,
    snapshot: WidgetSnapshot
): WidgetSkin {
    val opaque = opacity.coerceIn(WidgetConfig.MIN_OPACITY, 1f)

    val base = when (appearance) {
        WidgetAppearance.DARK -> NeutralDark
        WidgetAppearance.LIGHT -> NeutralLight
        WidgetAppearance.APP -> snapshot.appSurface?.let(::Color) ?: NeutralDark
        // Not the artwork colour itself: an accent is by definition a colour
        // chosen to stand out, and a whole panel of it is a widget that shouts.
        // Pulled most of the way to black so it reads as "tinted dark" — the
        // artwork's hue, at surface brightness.
        WidgetAppearance.ARTWORK ->
            snapshot.artworkAccent?.let { Color(it).darken(ARTWORK_SURFACE_MIX) } ?: NeutralDark
    }

    // Always derived from the background actually in use, so a photo theme that
    // turns the app's surface pale flips the text to dark automatically.
    val ink = readableOn(base)
    val accentSource = when (appearance) {
        WidgetAppearance.ARTWORK -> snapshot.artworkAccent?.let(::Color)
        WidgetAppearance.APP -> snapshot.appAccent?.let(::Color)
        else -> null
    } ?: Amber

    return WidgetSkin(
        background = base.copy(alpha = opaque),
        ink = ink,
        muted = ink.copy(alpha = 0.72f),
        // 3:1 rather than 4.5:1 — this tints an icon, which is a large graphical
        // object, not body text. Below that it stops reading as coloured at all.
        accent = ensureContrast(accentSource, base, AA_LARGE),
        scrimOverArt = SCRIM_BASE * opaque
    )
}

/** How far the artwork accent is pulled toward black to become a surface. */
private const val ARTWORK_SURFACE_MIX = 0.78f

/** Body-text contrast, re-exported so tests and callers agree on the number. */
internal const val WIDGET_TEXT_CONTRAST = AA_NORMAL

/**
 * The colour a control should be, given whether artwork is behind it.
 *
 * The accent's contrast is guaranteed against the flat panel and only against
 * the flat panel. Put it over an album cover — an image the app has never seen,
 * showing through a partial scrim — and that guarantee is void: a tan accent on
 * a silver sleeve was legible in the test and nearly invisible on the home
 * screen. Over artwork the control reverts to ink, which is picked for the
 * scrim colour and holds up over anything; the colour in that layout is
 * supposed to be coming from the artwork anyway.
 */
fun WidgetSkin.accentOver(artwork: Boolean): Color = if (artwork) ink else accent
