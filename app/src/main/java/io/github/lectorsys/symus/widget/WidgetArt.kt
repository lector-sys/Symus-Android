package io.github.lectorsys.symus.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import coil3.BitmapImage
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The widgets' picture cache.
 *
 * A widget cannot fetch anything — it draws in the launcher's process from
 * whatever the app left behind — so every image it shows has to exist as a file
 * first. This owns those files.
 *
 * Two rules the earlier version of this got wrong and which the tests now hold
 * it to:
 *
 * 1. **Kinds are kept apart.** Track artwork and playlist covers live in
 *    separate folders. A single folder that kept "only the current file" meant
 *    every track change deleted the covers of every playlist widget on the home
 *    screen, which then drew blank until the app happened to refresh them.
 * 2. **Eviction is bounded, not total.** Each folder keeps the most recently
 *    touched [KEEP] files. Several widgets can legitimately want different
 *    pictures at the same moment, and "delete everything except the one I just
 *    wrote" is a cache with a hit rate of zero when more than one asks.
 */
object WidgetArt {

    /** Where track artwork goes. */
    private const val TRACKS = "widget/art"

    /** Where playlist covers go. */
    private const val COVERS = "widget/covers"

    /**
     * Files kept per folder.
     *
     * Six covers a home screen with a handful of playlist widgets plus a couple
     * of recent tracks, at roughly 30 KB each. The bound exists so a year of
     * listening cannot turn this into the largest thing in the app's storage.
     */
    private const val KEEP = 6

    /** Bigger than any widget draws it, small enough to cross IPC without thought. */
    private const val EDGE = 256

    /** Track artwork for [trackId], downloading through Coil if it is not already here. */
    suspend fun track(context: Context, trackId: String, url: String): File? =
        fetch(context, TRACKS, trackId, url)

    /** Playlist cover for [playlistId]. */
    suspend fun cover(context: Context, playlistId: Long, url: String): File? =
        fetch(context, COVERS, "p$playlistId", url)

    /** Null for a missing or unreadable file, which is a widget with no art, not a crash. */
    fun decode(path: String?): Bitmap? =
        path?.let { runCatching { BitmapFactory.decodeFile(it) }.getOrNull() }

    /** Drops a cover the widget should stop showing — a deleted playlist, say. */
    suspend fun forgetCover(context: Context, playlistId: Long) = withContext(Dispatchers.IO) {
        runCatching { File(File(context.filesDir, COVERS), "p$playlistId.jpg").delete() }
        Unit
    }

    /**
     * The image as a local file.
     *
     * Coil is already the app's image loader and already has the thumbnail in
     * its disk cache by the time anything is playing, so this is normally a
     * decode rather than a download. The write is to a temp sibling and then a
     * rename, so a process killed mid-write cannot leave a truncated JPEG that
     * every future decode returns null for — the failure this makes impossible
     * is a widget permanently stuck with no artwork until the track changes.
     */
    private suspend fun fetch(
        context: Context,
        folder: String,
        key: String,
        url: String
    ): File? = withContext(Dispatchers.IO) {
        // Keys reach this from the network by way of the extractor. They have
        // never been anything but [A-Za-z0-9_-], and they are not becoming a
        // path traversal the first time that stops being true.
        val safe = key.filter { it.isLetterOrDigit() || it == '_' || it == '-' }
        if (safe.isEmpty()) return@withContext null

        val dir = File(context.filesDir, folder).apply { mkdirs() }
        val file = File(dir, "$safe.jpg")
        if (file.exists()) {
            // Touch, so eviction measures "least recently wanted" rather than
            // "oldest download" — a pinned playlist widget must not lose its
            // cover to six track changes.
            file.setLastModified(System.currentTimeMillis())
            return@withContext file
        }

        val bitmap = runCatching {
            val request = ImageRequest.Builder(context)
                .data(url)
                .size(EDGE, EDGE)
                // A hardware bitmap cannot be compressed back out.
                .allowHardware(false)
                .build()
            (SingletonImageLoader.get(context).execute(request).image as? BitmapImage)?.bitmap
        }.getOrNull() ?: return@withContext null

        val temp = File(dir, "$safe.jpg.part")
        val written = runCatching {
            temp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        }.getOrDefault(false)
        if (!written || !temp.renameTo(file)) {
            runCatching { temp.delete() }
            return@withContext null
        }
        evict(dir)
        file
    }

    /** Keeps the [KEEP] most recently touched files and deletes the rest. */
    private fun evict(dir: File) {
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        if (files.size <= KEEP) return
        files.sortedByDescending { it.lastModified() }
            .drop(KEEP)
            .forEach { runCatching { it.delete() } }
    }
}
