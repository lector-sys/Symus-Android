package io.github.lectorsys.symus.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The widget picture cache, against the failures that actually happened.
 *
 * The first version of this kept one folder and, after writing a file, deleted
 * everything else in it. With one widget that looks like a tidy cache. With a
 * now-playing widget and a playlist shortcut on the same home screen it means
 * every track change silently deletes the playlist's cover, and the shortcut
 * draws blank until something happens to refresh it. Both tests below fail
 * against that version.
 */
@RunWith(AndroidJUnit4::class)
class WidgetArtTest {

    private lateinit var context: Context
    private lateinit var source: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        clearCache()
        // A real file on disk, so the loader has something to actually decode
        // rather than a mocked pipeline that would not exercise the writing.
        source = File(context.cacheDir, "widget-art-source.png").also { file ->
            val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.rgb(200, 120, 40))
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    @After
    fun tearDown() {
        clearCache()
        source.delete()
    }

    private fun clearCache() {
        File(context.filesDir, "widget").deleteRecursively()
    }

    private fun sourceUrl() = "file://${source.absolutePath}"

    private val trackDir get() = File(context.filesDir, "widget/art")
    private val coverDir get() = File(context.filesDir, "widget/covers")

    @Test
    fun playlistCoversSurviveTrackChurn() = runTest {
        val cover = WidgetArt.cover(context, playlistId = 42, url = sourceUrl())
        assertNotNull("cover was not cached at all", cover)
        assertTrue(cover!!.exists())

        // A dozen track changes, which is a normal half hour of listening.
        repeat(12) { index ->
            assertNotNull(WidgetArt.track(context, "track$index", sourceUrl()))
        }

        assertTrue(
            "the playlist cover was deleted by track changes",
            cover.exists()
        )
        assertNotNull("the cover no longer decodes", WidgetArt.decode(cover.absolutePath))
    }

    @Test
    fun trackArtIsBoundedRatherThanUnlimited() = runTest {
        repeat(20) { index ->
            WidgetArt.track(context, "track$index", sourceUrl())
        }
        val kept = trackDir.listFiles()?.count { it.isFile && !it.name.endsWith(".part") } ?: 0
        assertTrue("kept $kept files, expected a bounded cache", kept in 1..8)
    }

    @Test
    fun coversAreBoundedToo() = runTest {
        repeat(20) { index ->
            WidgetArt.cover(context, playlistId = index.toLong(), url = sourceUrl())
        }
        val kept = coverDir.listFiles()?.count { it.isFile && !it.name.endsWith(".part") } ?: 0
        assertTrue("kept $kept covers, expected a bounded cache", kept in 1..8)
    }

    /**
     * The second call must not re-download. Cheap to check and the reason the
     * eight-second heartbeat is not eight seconds of network traffic.
     */
    @Test
    fun aCachedFileIsReusedRatherThanRefetched() = runTest {
        val first = WidgetArt.track(context, "same", sourceUrl())
        assertNotNull(first)
        val stamp = first!!.lastModified()
        val length = first.length()

        val second = WidgetArt.track(context, "same", "file:///does/not/exist.png")
        assertEquals(first.absolutePath, second?.absolutePath)
        assertEquals("the file was rewritten", length, second!!.length())
        assertTrue("the cached file was replaced", second.lastModified() >= stamp)
    }

    /** A key that is nothing but separators must not escape the cache folder. */
    @Test
    fun hostileKeysCannotEscapeTheFolder() = runTest {
        assertEquals(null, WidgetArt.track(context, "../../..", sourceUrl()))
        assertEquals(null, WidgetArt.track(context, "/", sourceUrl()))
    }

    @Test
    fun aDeletedPlaylistLosesItsCover() = runTest {
        val cover = WidgetArt.cover(context, playlistId = 7, url = sourceUrl())
        assertNotNull(cover)
        WidgetArt.forgetCover(context, 7)
        assertTrue("cover outlived the playlist", !cover!!.exists())
    }
}
