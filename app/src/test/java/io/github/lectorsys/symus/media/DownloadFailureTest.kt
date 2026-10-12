package io.github.lectorsys.symus.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * What a failing download tells the user, and when one is refused outright.
 *
 * Both were wrong in ways that only showed up on a device: every local failure
 * is an IOException, and running those through the extractor's classifier
 * reported a full disk or an unwritable cache directory as
 * "No connection to YouTube."
 */
class DownloadFailureTest {

    // -- the space check ---------------------------------------------------

    private val headroom = 32L * 1024 * 1024

    @Test
    fun `a download that fits with headroom to spare is allowed`() {
        assertTrue(Downloads.hasRoomFor(needed = 10_000_000, free = 500_000_000))
    }

    @Test
    fun `a download larger than the free space is refused`() {
        assertFalse(Downloads.hasRoomFor(needed = 400_000_000, free = 100_000_000))
    }

    /** Filling the device to the last byte is how a phone stops being usable. */
    @Test
    fun `a download that would eat the headroom is refused`() {
        assertFalse(Downloads.hasRoomFor(needed = 100_000_000, free = 100_000_000 + headroom - 1))
    }

    @Test
    fun `exactly the headroom is enough`() {
        assertTrue(Downloads.hasRoomFor(needed = 100_000_000, free = 100_000_000 + headroom))
    }

    /**
     * StatFs returning zero means the check itself failed, not that the disk is
     * full. Refusing everything because we could not measure would be worse
     * than letting the write fail with a real error.
     */
    @Test
    fun `an unreadable free-space figure does not block the download`() {
        assertTrue(Downloads.hasRoomFor(needed = 10_000_000, free = 0))
    }

    // -- the message -------------------------------------------------------

    /**
     * The regression this file exists for: a storage fault must not be
     * described as a connection fault.
     */
    @Test
    fun `a storage failure keeps its own message`() {
        val failure = DownloadFailed("Not enough free space for that download.")
        assertEquals("Not enough free space for that download.", failure.message)
        // It is an IOException, which is exactly why it used to be mistaken for
        // a network problem — the type is what now keeps them apart.
        assertTrue(failure is IOException)
    }

    @Test
    fun `an extraction failure is still classified as one`() {
        val failure = Extractor.classify(ExtractionFailed(Reason.RATE_LIMITED, "rate limited"))
        assertEquals(Reason.RATE_LIMITED, failure.reason)
    }

    /**
     * A bare IOException could be either half, so the message says both rather
     * than guessing — which is what produced the wrong answer before.
     */
    @Test
    fun `a bare io failure is not claimed to be a network problem`() {
        val classified = Extractor.classify(IOException("EACCES (Permission denied)"))
        assertEquals(
            "the classifier still treats a bare IOException as network, which is " +
                "why Downloads must not route local failures through it",
            Reason.NETWORK,
            classified.reason
        )
    }
}
