package io.github.lectorsys.symus.ui.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.collection.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The Android half of palette extraction: decode, shrink, hand off, cache.
 *
 * Everything here happens on this device. No image is uploaded, and nothing in
 * this file opens a socket — the only I/O is a ContentResolver stream from the
 * photo the user picked and a file in the app's own storage.
 *
 * The decode is the part that has to be careful. A modern phone camera produces
 * 50-megapixel images; decoding one at full size costs 200 MB of heap and will
 * kill the app on the devices this most needs to work on. Every read here goes
 * through a bounds pass first and never materialises more than
 * [SAMPLE_EDGE] pixels on a side, which is far more than a histogram of 32
 * buckets per channel can tell apart anyway.
 */
object ImagePalette {

    /**
     * Edge length the palette is computed from.
     *
     * 144px is ~20k pixels. Going higher changes the resulting colours by
     * amounts no one can see, and costs time on every crop drag.
     */
    private const val SAMPLE_EDGE = 144

    /**
     * Longest edge kept when an image is copied into app storage for use as a
     * wallpaper. Enough for any phone screen, small enough that a themes folder
     * does not quietly become the largest thing in the app's data.
     */
    const val STORED_EDGE = 1440

    /**
     * Results, keyed by source and the focal point it was computed for.
     *
     * Composition re-reads a palette whenever the theme editor recomposes, which
     * during a crop drag is sixty times a second. Without this, that is sixty
     * decodes a second. Sized in entries rather than bytes because each entry is
     * a handful of colours.
     */
    private val cache = LruCache<String, List<PaletteCandidate>>(32)

    /** Clears memoised palettes — used when a theme's image is replaced or deleted. */
    fun forget(key: String? = null) {
        if (key == null) cache.evictAll() else cache.remove(key)
    }

    /**
     * Palettes for an image the user just picked.
     *
     * [focusX]/[focusY] and [focusRadius] describe the crop: the palette is
     * computed from the region the user framed, not the whole frame, so
     * dragging the focal point onto the red car actually changes the result.
     */
    suspend fun of(
        context: Context,
        uri: Uri,
        focusX: Float = 0.5f,
        focusY: Float = 0.5f,
        focusRadius: Float = 0.5f
    ): List<PaletteCandidate> = withContext(Dispatchers.IO) {
        val key = "$uri@${focusX.round2()},${focusY.round2()},${focusRadius.round2()}"
        cache.get(key)?.let { return@withContext it }
        val bitmap = decodeSampled(context, uri) ?: return@withContext emptyList()
        val result = try {
            palettesFrom(bitmap.pixelsIn(focusX, focusY, focusRadius))
        } finally {
            bitmap.recycle()
        }
        cache.put(key, result)
        result
    }

    /** The same, for an image already copied into app storage. */
    suspend fun of(
        context: Context,
        file: File,
        focusX: Float = 0.5f,
        focusY: Float = 0.5f
    ): List<PaletteCandidate> = of(context, Uri.fromFile(file), focusX, focusY)

    /**
     * Copies a picked image into app storage, downsampled, returning the file.
     *
     * The copy is the point: a `content://` URI from the photo picker is a
     * short-lived grant, and a theme that referenced it directly would stop
     * rendering the next time the process started. Writing our own copy also
     * means the app never needs, and never asks for, access to the rest of the
     * gallery.
     *
     * Returns null rather than throwing if the image cannot be read — a picker
     * can hand back a URI that resolves to nothing.
     */
    suspend fun store(context: Context, uri: Uri, destination: File): File? =
        withContext(Dispatchers.IO) {
            val bitmap = decodeSampled(context, uri, STORED_EDGE) ?: return@withContext null
            try {
                destination.parentFile?.mkdirs()
                // Written to a temp sibling and renamed, so a process death
                // mid-write cannot leave a half-decoded image that the theme
                // then renders as a grey rectangle forever.
                val temp = File(destination.parentFile, destination.name + ".part")
                temp.outputStream().use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                }
                if (destination.exists()) destination.delete()
                if (temp.renameTo(destination)) destination else null
            } catch (_: Throwable) {
                null
            } finally {
                bitmap.recycle()
            }
        }

    /**
     * Decodes at the smallest power-of-two scale that still covers [maxEdge].
     *
     * The bounds-only pass first is what keeps a 50-megapixel photo from ever
     * being fully resident.
     */
    private fun decodeSampled(context: Context, uri: Uri, maxEdge: Int = SAMPLE_EDGE): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
        }.getOrNull()
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxEdge &&
            bounds.outHeight / (sample * 2) >= maxEdge
        ) {
            sample *= 2
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            // 8888 because the histogram reads channels back; a hardware bitmap
            // cannot be read at all, and RGB_565 would quantise twice.
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }
        }.getOrNull()
    }

    /** The pixels inside the focal region, as packed ARGB. */
    private fun Bitmap.pixelsIn(focusX: Float, focusY: Float, radius: Float): IntArray {
        val halfW = (width * radius).toInt().coerceIn(1, width)
        val halfH = (height * radius).toInt().coerceIn(1, height)
        val cx = (width * focusX).toInt()
        val cy = (height * focusY).toInt()
        val left = (cx - halfW / 2).coerceIn(0, width - 1)
        val top = (cy - halfH / 2).coerceIn(0, height - 1)
        val right = (left + halfW).coerceAtMost(width)
        val bottom = (top + halfH).coerceAtMost(height)
        val w = (right - left).coerceAtLeast(1)
        val h = (bottom - top).coerceAtLeast(1)
        val out = IntArray(w * h)
        getPixels(out, 0, w, left, top, w, h)
        return out
    }

    private fun Float.round2() = (this * 100).toInt()
}
