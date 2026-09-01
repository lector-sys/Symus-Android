package io.github.lectorsys.symus.media

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * The one test that talks to YouTube.
 *
 * Extraction is the assumption this whole app rests on, and it is the one that
 * rots without anyone touching the code: YouTube changes, the pinned extractor
 * stops working, and every screen fails at once. A test that only exercises our
 * own wrappers would still pass on that day, so this one resolves a real stream
 * and checks the URL actually serves bytes.
 *
 * Off by default because a unit suite that needs the network is a unit suite
 * that fails on a train. Run it deliberately:
 *
 *   gradlew :app:testDebugUnitTest --tests '*ExtractorLiveTest*' -Dsymus.live=1
 */
class ExtractorLiveTest {

    companion object {
        private val client = OkHttpClient()

        @BeforeClass
        @JvmStatic
        fun setUp() {
            Extractor.init(client)
        }
    }

    private val live get() = System.getProperty("symus.live") == "1"

    @Test
    fun `search returns playable tracks with ids`() = runBlocking {
        assumeTrue("set -Dsymus.live=1 to run", live)

        val hits = Extractor().search("blinding lights the weeknd", limit = 5)
        assertTrue("search returned nothing", hits.isNotEmpty())

        val first = hits.first()
        // The id is what the entire library is keyed on, and NewPipe only hands
        // back URLs — so parsing one is load-bearing, not a convenience.
        assertEquals(11, first.track.videoId.length)
        assertTrue("no title", first.track.title.isNotBlank())
        assertTrue("zero duration means livestream or premiere", first.track.duration > 0)
    }

    @Test
    fun `an audio url resolves and actually serves audio`() = runBlocking {
        assumeTrue("set -Dsymus.live=1 to run", live)

        val hits = Extractor().search("miles davis so what", limit = 5)
        val track = hits.first { it.track.duration > 60 }.track

        val url = Extractor().audioUrl(track.videoId)
        assertTrue("not an http url: $url", url.startsWith("http"))

        // Resolving is not the same as playing. A URL that 403s is exactly what a
        // broken extractor produces, and it looks fine until playback starts.
        val probe = client.newCall(
            Request.Builder().url(url).header("Range", "bytes=0-1023").build()
        ).execute()
        probe.use {
            assertTrue("stream returned HTTP ${it.code}", it.code == 200 || it.code == 206)
            val type = it.header("content-type")
            assertNotNull("no content type", type)
            assertTrue("not audio: $type", type!!.startsWith("audio/"))
        }
    }
}
