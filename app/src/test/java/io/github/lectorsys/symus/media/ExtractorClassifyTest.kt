package io.github.lectorsys.symus.media

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.IOException
import java.io.InterruptedIOException

/**
 * How a failure is classified, which is what the UI acts on.
 *
 * The important cases are not the messages but the two decisions taken from
 * them: whether to offer a retry, and whether this is a failure at all.
 */
class ExtractorClassifyTest {

    /**
     * The one that cost a real bug.
     *
     * Several callers reach classify() through `runCatching` or a bare
     * `catch (Throwable)`. If cancellation were classified rather than
     * rethrown, leaving a screen mid-search would show the user an extraction
     * error, retry the request, and leave a coroutine scope that believes it is
     * still alive.
     */
    @Test
    fun `cancellation is rethrown rather than classified`() {
        val cancelled = CancellationException("the user left")
        try {
            Extractor.classify(cancelled)
            fail("classify() swallowed a CancellationException")
        } catch (thrown: CancellationException) {
            assertEquals(cancelled, thrown)
        }
    }

    /** Rate limiting clears on its own, but hammering it does not help. */
    @Test
    fun `a 429 is rate limiting and is not offered a retry`() {
        val failure = Extractor.classify(ReCaptchaException("429", "https://youtube.com"))
        assertEquals(Reason.RATE_LIMITED, failure.reason)
        assertFalse("a retry button on a 429 makes the backoff worse", failure.retryable)
    }

    @Test
    fun `a dropped connection is retryable`() {
        val failure = Extractor.classify(IOException("socket closed"))
        assertEquals(Reason.NETWORK, failure.reason)
        assertTrue(failure.retryable)
    }

    @Test
    fun `a timeout is retryable`() {
        val failure = Extractor.classify(InterruptedIOException("timeout"))
        assertEquals(Reason.NETWORK, failure.reason)
        assertTrue(failure.retryable)
    }

    /** A removed video will not come back, so no button should claim it might. */
    @Test
    fun `a removed video is not retryable`() {
        val failure = Extractor.classify(ContentNotAvailableException("gone"))
        assertEquals(Reason.UNAVAILABLE, failure.reason)
        assertFalse(failure.retryable)
    }

    /** An already-classified failure passes through unchanged, not double-wrapped. */
    @Test
    fun `classifying a classified failure is the same failure`() {
        val original = ExtractionFailed(Reason.LIVESTREAM, "That is a livestream.", retryable = false)
        assertEquals(original, Extractor.classify(original))
    }

    /**
     * Anything unrecognised is treated as YouTube having changed, which is
     * retryable: the next attempt may hit a page the extractor still reads.
     */
    @Test
    fun `an unknown failure is retryable and says something`() {
        val failure = Extractor.classify(IllegalStateException("no player response"))
        assertEquals(Reason.UNKNOWN, failure.reason)
        assertTrue(failure.retryable)
        assertTrue(failure.message!!.isNotBlank())
    }

    @Test
    fun `video ids come out of every url shape youtube emits`() {
        assertEquals("dQw4w9WgXcQ", Extractor.videoIdFrom("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", Extractor.videoIdFrom("https://youtu.be/dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", Extractor.videoIdFrom("https://www.youtube.com/shorts/dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", Extractor.videoIdFrom("https://www.youtube.com/live/dQw4w9WgXcQ"))
        assertEquals(
            "dQw4w9WgXcQ",
            Extractor.videoIdFrom("https://music.youtube.com/watch?v=dQw4w9WgXcQ&list=RDABC")
        )
    }

    @Test
    fun `something that is not a video url has no id`() {
        assertNull(Extractor.videoIdFrom(null))
        assertNull(Extractor.videoIdFrom(""))
        assertNull(Extractor.videoIdFrom("https://www.youtube.com/playlist?list=PLabc"))
        assertNull(Extractor.videoIdFrom("blinding lights"))
    }

    @Test
    fun `channel ids come off a channel url only`() {
        assertEquals(
            "UCabc123",
            Extractor.channelIdFrom("https://www.youtube.com/channel/UCabc123")
        )
        assertNull(Extractor.channelIdFrom("https://www.youtube.com/@someone"))
        assertNull(Extractor.channelIdFrom(null))
    }
}
