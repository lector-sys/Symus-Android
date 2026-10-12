package io.github.lectorsys.symus.ui.player

import android.graphics.Bitmap
import androidx.core.graphics.get
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import io.github.lectorsys.symus.ui.theme.Amber
import coil3.BitmapImage
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/**
 * A colour taken from the artwork, for the player's backdrop.
 *
 * Deliberately not the literal dominant colour: album art is full of
 * near-blacks and blown-out whites, and either would give a backdrop that is
 * indistinguishable from the page or unreadable against white text. So the
 * sample is weighted towards whatever is actually colourful, then pushed into a
 * band that is guaranteed to sit behind light text — which is why this can be
 * used directly as a background with no further checks.
 *
 * Falls back to [fallback] whenever there is no artwork, the fetch fails, or the
 * user has turned artwork colours off, so every caller always has a legible
 * colour including on the very first frame.
 *
 * The fallback is a parameter rather than a MaterialTheme lookup on purpose.
 * This is called from SymusRoot to decide what accent to *build* the theme
 * with, which is outside any MaterialTheme — reading the scheme there silently
 * returned Material's stock purple and tinted the whole app with it.
 */
@Composable
fun rememberArtworkAccent(
    url: String?,
    enabled: Boolean = true,
    fallback: Color = Amber
): Color {
    val context = LocalContext.current
    var accent by remember(url, enabled) { mutableStateOf(fallback) }

    LaunchedEffect(url, enabled, fallback) {
        if (!enabled || url.isNullOrBlank()) {
            accent = fallback
            return@LaunchedEffect
        }
        val sampled = withContext(Dispatchers.IO) {
            runCatching {
                val request = ImageRequest.Builder(context)
                    .data(url)
                    // 32px is plenty for an average and keeps the decode trivial.
                    .size(32, 32)
                    // A hardware bitmap cannot be read back pixel by pixel.
                    .allowHardware(false)
                    .build()
                val image = SingletonImageLoader.get(context).execute(request).image
                (image as? BitmapImage)?.bitmap?.let(::accentOf)
            }.getOrNull()
        }
        accent = sampled?.takeIf { it != Color.Unspecified } ?: fallback
    }
    return accent
}

/** The weighted average of a thumbnail's colourful pixels, forced into a usable band. */
internal fun accentOf(bitmap: Bitmap): Color {
    val width = min(bitmap.width, 32)
    val height = min(bitmap.height, 32)
    if (width <= 0 || height <= 0) return Color.Unspecified

    var red = 0.0
    var green = 0.0
    var blue = 0.0
    var weightTotal = 0.0

    for (y in 0 until height) {
        for (x in 0 until width) {
            val pixel = bitmap[x * bitmap.width / width, y * bitmap.height / height]
            val r = ((pixel shr 16) and 0xFF) / 255.0
            val g = ((pixel shr 8) and 0xFF) / 255.0
            val b = (pixel and 0xFF) / 255.0
            val maxChannel = maxOf(r, g, b)
            val minChannel = minOf(r, g, b)
            // Saturation times distance from both extremes: a grey pixel and a
            // blown-out one both contribute nearly nothing.
            val saturation = if (maxChannel <= 0.0) 0.0 else (maxChannel - minChannel) / maxChannel
            val midness = 1.0 - kotlin.math.abs(0.5 - (maxChannel + minChannel) / 2) * 2
            val weight = saturation * saturation * max(0.05, midness)
            red += r * weight
            green += g * weight
            blue += b * weight
            weightTotal += weight
        }
    }

    if (weightTotal <= 0.0001) return Color.Unspecified

    val hsl = FloatArray(3)
    android.graphics.Color.colorToHSV(
        android.graphics.Color.rgb(
            (red / weightTotal * 255).toInt().coerceIn(0, 255),
            (green / weightTotal * 255).toInt().coerceIn(0, 255),
            (blue / weightTotal * 255).toInt().coerceIn(0, 255)
        ),
        hsl
    )
    // Saturation floor so a nearly grey cover still tints; value ceiling so the
    // result is always dark enough for light text to sit on.
    return Color.hsv(
        hue = hsl[0],
        saturation = hsl[1].coerceIn(0.25f, 0.62f),
        value = hsl[2].coerceIn(0.22f, 0.42f)
    )
}
