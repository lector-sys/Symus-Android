package io.github.lectorsys.symus.ai

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lectorsys.symus.media.Extractor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/**
 * The catalogue download path, run against the real host.
 *
 * Separate from [OfflineAssistantTest] because this one is the opposite kind of
 * evidence: it needs the network, and it is the only test here that does. It
 * proves the thing the offline tests cannot — that the bundle the app installs
 * is the bundle the catalogue says it is, fetched by the app's own downloader
 * and checked against a digest written down before the transfer began.
 *
 * Skipped when offline rather than failed, and a skip is reported as a skip.
 */
@RunWith(AndroidJUnit4::class)
class ModelDownloadTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun modelsDir() = File(context.filesDir, "models").apply { mkdirs() }

    @Test
    fun theCatalogueDownloadInstallsAChecksumVerifiedBundle(): Unit = runBlocking {
        assumeTrue("This build has no inference runtime (lite flavour)", LlmEngine.PRESENT)
        val spec = ModelCatalog.small
        val reachable = runCatching {
            Extractor.okHttp().newBuilder()
                .callTimeout(java.time.Duration.ofSeconds(10))
                .build()
                .newCall(okhttp3.Request.Builder().url(spec.url).head().build())
                .execute().use { it.isSuccessful }
        }.getOrDefault(false)
        say("DOWNLOAD_REACHABLE $reachable")
        assumeTrue("The model host is not reachable; nothing to download", reachable)

        // Start from nothing, so the transfer and the verification both happen.
        modelsDir().listFiles()?.forEach { it.delete() }

        val store = ModelStore(context, Extractor.okHttp())
        say("DOWNLOAD_URL   ${spec.url}")
        say("DOWNLOAD_EXPECT ${spec.sizeBytes} bytes, sha256 ${spec.sha256}")

        val started = System.currentTimeMillis()
        val file = store.download(spec).getOrThrow()
        val millis = System.currentTimeMillis() - started

        say("DOWNLOAD_MS    $millis")
        say("DOWNLOAD_FILE  ${file.name}")
        say("DOWNLOAD_BYTES ${file.length()}")
        say("DOWNLOAD_STATE ${store.state.value}")

        assertEquals("Downloaded size differs from the published size", spec.sizeBytes, file.length())

        // Hash it again here rather than trusting that the store did: the claim
        // under test is that what landed on disk is what was published.
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        say("DOWNLOAD_SHA256 $actual")
        assertEquals("The installed bundle is not the published one", spec.sha256, actual)

        assertTrue("Nothing reports as installed", store.installed() != null)
        assertEquals(
            "A catalogue download must be recognised as its catalogue entry",
            spec.id,
            store.installedSpec()?.id
        )

        // And the downloaded bundle has to load and answer, or the download
        // proved only that bytes moved.
        val runtime = LlmRuntime(context, store)
        val loaded = runtime.load().getOrThrow()
        say("DOWNLOAD_LOAD_MS ${loaded.loadMillis}")
        val reply = runtime.complete(
            "Reply with exactly one word: the colour of a clear midday sky."
        ).getOrThrow()
        say("DOWNLOAD_GEN_MS  ${reply.millis}")
        say("DOWNLOAD_OUT     ${reply.text.take(160)}")
        assertTrue("A freshly downloaded model produced nothing", reply.text.isNotBlank())
        runtime.unload()
    }

    /**
     * A bad checksum has to discard the file rather than install it.
     *
     * Served from a socket on this device rather than from the internet, for two
     * reasons: the bytes and their digest are then known exactly, and the test
     * means the same thing with the network switched off. The transfer succeeds;
     * the digest check is what must fail, and nothing may be left installed.
     */
    @Test
    fun aChecksumMismatchIsRefusedAndDiscarded(): Unit = runBlocking {
        modelsDir().listFiles()?.forEach { it.delete() }
        val payload = ByteArray(64 * 1024) { (it % 251).toByte() }

        serving(payload) { url ->
            val lying = ModelCatalog.small.copy(
                id = "checksum-probe",
                url = url,
                sizeBytes = payload.size.toLong(),
                sha256 = "0".repeat(64)
            )
            val store = ModelStore(context, Extractor.okHttp())
            val result = store.download(lying)
            val state = store.state.value
            say("MISMATCH_RESULT ${result.isFailure}")
            say("MISMATCH_STATE  $state")

            assertTrue("A wrong checksum must fail the download", result.isFailure)
            assertTrue(
                "The failure must say the checksum did not match, got: $state",
                (state as? ModelState.Failed)?.message?.contains("checksum", ignoreCase = true) == true
            )
            assertTrue("A rejected download must leave nothing installed", store.installed() == null)
            assertTrue(
                "The discarded transfer must not be left lying around",
                modelsDir().listFiles()?.none { it.name.endsWith(".part") } != false
            )
        }
    }

    /**
     * A truncated transfer must be reported by how far it got, not installed.
     *
     * The server closes early on purpose, which is what a dropped connection
     * looks like from the downloader's side.
     */
    @Test
    fun aTruncatedDownloadIsReportedRatherThanInstalled(): Unit = runBlocking {
        modelsDir().listFiles()?.forEach { it.delete() }
        val payload = ByteArray(64 * 1024) { (it % 251).toByte() }

        // Claim twice what is actually sent, so the transfer ends short.
        serving(payload) { url ->
            val short = ModelCatalog.small.copy(
                id = "truncation-probe",
                url = url,
                sizeBytes = payload.size * 2L,
                sha256 = "0".repeat(64)
            )
            val store = ModelStore(context, Extractor.okHttp())
            val result = store.download(short)
            val state = store.state.value
            say("TRUNCATED_RESULT ${result.isFailure}")
            say("TRUNCATED_STATE  $state")

            assertTrue("A short transfer must fail", result.isFailure)
            assertTrue(
                "The failure must say how far it got, got: $state",
                (state as? ModelState.Failed)?.message?.contains("stopped after", ignoreCase = true) == true
            )
            assertTrue("Nothing may be installed", store.installed() == null)
        }
    }

    /**
     * A one-request HTTP server on the loopback interface.
     *
     * Enough to answer a GET with [payload] and a matching `Content-Length`,
     * and nothing more — this exists to give [ModelStore] a real socket to read
     * from, not to be an HTTP implementation.
     */
    private inline fun serving(payload: ByteArray, block: (String) -> Unit) {
        val server = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val thread = Thread {
            runCatching {
                while (!server.isClosed) {
                    server.accept().use { socket ->
                        // Read the request line and headers, then answer.
                        val input = socket.getInputStream().bufferedReader()
                        while (true) {
                            val line = input.readLine() ?: break
                            if (line.isEmpty()) break
                        }
                        socket.getOutputStream().apply {
                            write(
                                (
                                    "HTTP/1.1 200 OK\r\n" +
                                        "Content-Length: ${payload.size}\r\n" +
                                        "Content-Type: application/octet-stream\r\n" +
                                        "Connection: close\r\n\r\n"
                                    ).toByteArray()
                            )
                            write(payload)
                            flush()
                        }
                    }
                }
            }
        }
        thread.isDaemon = true
        thread.start()
        try {
            block("http://127.0.0.1:${server.localPort}/bundle.task")
        } finally {
            runCatching { server.close() }
        }
    }

    private fun say(line: String) = Log.i(TAG, line)

    private companion object {
        const val TAG = "SYMUS_LLM"
    }
}
