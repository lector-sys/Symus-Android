package io.github.lectorsys.symus.media

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
 * The tests that talk to YouTube.
 *
 * Extraction is the assumption this whole app rests on, and it is the one that
 * rots without anyone touching the code: YouTube changes, the pinned extractor
 * stops working, and every screen fails at once. A test that only exercised our
 * own wrappers would still pass on that day, so these resolve real streams and
 * check the URL actually serves bytes.
 *
 * Off by default because a unit suite that needs the network is a unit suite
 * that fails on a train. Run them deliberately:
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

        val page = Extractor().search("blinding lights the weeknd", SearchKind.SONGS, 5)
        assertTrue("search returned nothing", page.hits.isNotEmpty())

        val first = page.hits.first()
        // The id is what the entire library is keyed on, and NewPipe only hands
        // back URLs — so parsing one is load-bearing, not a convenience.
        assertEquals(11, first.track.videoId.length)
        assertTrue("no title", first.track.title.isNotBlank())
        assertTrue("zero duration means livestream or premiere", first.track.duration > 0)
    }

    @Test
    fun `search paginates`() = runBlocking {
        assumeTrue("set -Dsymus.live=1 to run", live)

        val extractor = Extractor()
        val first = extractor.search("lofi hip hop", SearchKind.SONGS, 10)
        assumeTrue("no second page offered", first.hasMore)

        val second = extractor.searchMore("lofi hip hop", SearchKind.SONGS, first.nextPage!!, 10)
        assertTrue("second page was empty", second.hits.isNotEmpty())
        val overlap = first.hits.map { it.track.videoId }
            .intersect(second.hits.map { it.track.videoId }.toSet())
        assertTrue("the second page repeated the first", overlap.size < second.hits.size)
    }

    @Test
    fun `an audio url resolves and actually serves audio`() = runBlocking {
        assumeTrue("set -Dsymus.live=1 to run", live)

        val extractor = Extractor()
        val page = extractor.search("miles davis so what", SearchKind.SONGS, 5)
        val track = page.hits.first { it.track.duration > 60 }.track

        val url = extractor.audioUrl(track.videoId)
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

    @Test
    fun `the same url is resolved once for concurrent callers`() = runBlocking {
        assumeTrue("set -Dsymus.live=1 to run", live)

        val extractor = Extractor()
        val page = extractor.search("miles davis so what", SearchKind.SONGS, 5)
        val videoId = page.hits.first { it.track.duration > 60 }.track.videoId

        // Both of these go through the in-flight map; a second extraction would
        // produce a different signed URL.
        coroutineScope {
            val first = async { extractor.audioUrl(videoId) }
            val second = async { extractor.audioUrl(videoId) }
            assertEquals(first.await(), second.await())
        }
    }

    @Test
    fun `related streams come back for a real video`() = runBlocking {
        assumeTrue("set -Dsymus.live=1 to run", live)

        val extractor = Extractor()
        val page = extractor.search("the weeknd starboy", SearchKind.SONGS, 5)
        val related = extractor.related(page.hits.first().track.videoId, 10)
        // Autoplay and radio are built on this list; empty means both degrade to
        // whatever the library already had.
        assertTrue("no related streams", related.isNotEmpty())
    }

    @Test
    fun `suggestions come back for a prefix`() = runBlocking {
        assumeTrue("set -Dsymus.live=1 to run", live)

        val suggestions = Extractor().suggestions("blinding")
        assertTrue("no suggestions", suggestions.isNotEmpty())
    }
}
