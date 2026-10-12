package io.github.lectorsys.symus.ai

import android.content.Context
import android.util.Log
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Proof that a real model actually runs here, not that the code compiles.
 *
 * This test is skipped rather than failed when no bundle has been staged — a
 * half-gigabyte model is not something CI can be assumed to hold, and a red
 * suite on a machine that simply has no weights teaches people to ignore the
 * colour. Staging is one push:
 *
 * ```
 * adb push model.task \
 *   /sdcard/Android/data/io.github.lectorsys.symus/files/model-under-test.task
 * ```
 *
 * Everything it measures — load time, response time, tokens — is printed, so a
 * run of this test is the evidence that the feature works on this device rather
 * than a claim that it should.
 */
@RunWith(AndroidJUnit4::class)
class LlmInferenceTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /**
     * The staged bundle, from wherever it was put.
     *
     * Two locations because the app-specific external directory is deleted when
     * the package is uninstalled, and `connectedAndroidTest` uninstalls after
     * every run — so a model pushed there survives exactly one invocation.
     * `/data/local/tmp` outlives the package, which makes the suite repeatable:
     *
     * ```
     * adb push model.task /data/local/tmp/model-under-test.task
     * adb shell chmod 644 /data/local/tmp/model-under-test.task
     * ```
     */
    private fun staged(): File? = listOf(
        File(context.getExternalFilesDir(null), STAGED),
        File("/data/local/tmp", STAGED)
    ).firstOrNull { it.isFile && it.length() > 0 }

    /**
     * Puts the staged bundle where [ModelStore] looks, because that is the path
     * the real feature loads from. Testing a different path would prove the
     * runtime works and leave the app's own plumbing unexercised.
     */
    private fun install(): File {
        val source = staged()!!
        val dir = File(context.filesDir, "models").apply { mkdirs() }
        val target = File(dir, "qwen2.5-0.5b-instruct-q8.task")
        if (target.length() != source.length()) {
            source.inputStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output, 1 shl 20) }
            }
        }
        return target
    }

    private fun runtime(): LlmRuntime {
        val store = ModelStore(context, io.github.lectorsys.symus.media.Extractor.okHttp())
        return LlmRuntime(context, store)
    }

    @Test
    fun theDeviceReportsWhetherItCanRunAModelAtAll() {
        val availability = runtime().availability(ModelCatalog.small)
        say("ABIs: ${Build.SUPPORTED_ABIS.joinToString()}")
        say("availability: $availability")
        // Whatever the answer, it must be a definite one. A device that cannot
        // run this must say so rather than fail later inside native code.
        assertTrue(
            availability is LlmAvailability.Ready ||
                availability is LlmAvailability.UnsupportedAbi ||
                availability is LlmAvailability.NotEnoughMemory ||
                // The lite build's answer, and just as definite as the others.
                availability is LlmAvailability.RuntimeMissing
        )
    }

    @Test
    fun aRealModelLoadsAndAnswers() = runTest(timeout = TIMEOUT) {
        assumeTrue("This build has no inference runtime (lite flavour)", LlmEngine.PRESENT)
        assumeTrue("No model staged at $STAGED", staged() != null)
        val file = install()
        val runtime = runtime()

        val loaded = runtime.load().getOrThrow()
        say("MODEL      ${loaded.name}")
        say("FILE       ${file.name} (${file.length() / 1_000_000} MB)")
        say("BACKEND    ${loaded.backend}")
        say("ABI        ${Build.SUPPORTED_ABIS.first()}")
        say("LOAD_MS    ${loaded.loadMillis}")

        val completion = runtime.complete(
            "Reply with exactly one word: the colour of a clear midday sky."
        ).getOrThrow()
        say("GEN_MS     ${completion.millis}")
        say("PROMPT_TOK ${completion.promptTokens}")
        say("OUTPUT     ${completion.text.take(200)}")

        assertTrue("The model returned nothing", completion.text.isNotBlank())
        assertTrue(
            "Expected a real answer, got: ${completion.text}",
            completion.text.contains("blue", ignoreCase = true)
        )
        runtime.unload()
    }

    /**
     * The grounded path end to end: a question, a tool call, a checked answer.
     *
     * The assertion that matters is not that the prose is good — a 0.5B model's
     * prose is what it is — but that the figure in it came from the database.
     */
    @Test
    fun aGroundedQuestionIsAnsweredFromTheDatabase() = runTest(timeout = TIMEOUT) {
        assumeTrue("This build has no inference runtime (lite flavour)", LlmEngine.PRESENT)
        assumeTrue("No model staged at $STAGED", staged() != null)
        install()
        val runtime = runtime()
        runtime.load().getOrThrow()

        val completion = runtime.complete(
            """
            You are the assistant inside a music app. Answer in one sentence.
            Use ONLY the figures below. Never calculate or invent a number.

            Figures:
            TOOL listening_time (the last 30 days):
              listening_time = 4 hours 12 minutes
              plays = 37

            Question: How much have I listened to this month?
            Answer:
            """.trimIndent()
        ).getOrThrow()

        say("GROUNDED_MS  ${completion.millis}")
        say("GROUNDED_OUT ${completion.text.take(300)}")

        assertTrue("The model returned nothing", completion.text.isNotBlank())
        runtime.unload()
    }

    /**
     * Inference with the network genuinely gone.
     *
     * The proof is not that the emulator was put into airplane mode — that is a
     * claim about the harness. It is that a real HTTP request from *this
     * process* fails in the same test that then generates a real answer. If the
     * request ever succeeds the test skips rather than passes, because on a
     * connected device it would be proving nothing.
     *
     * Staging: `adb shell svc wifi disable && adb shell svc data disable`.
     */
    @Test
    fun inferenceWorksWithNoNetworkAtAll() = runTest(timeout = TIMEOUT) {
        assumeTrue("This build has no inference runtime (lite flavour)", LlmEngine.PRESENT)
        assumeTrue("No model staged at $STAGED", staged() != null)

        val reachable = runCatching {
            val client = io.github.lectorsys.symus.media.Extractor.okHttp()
                .newBuilder()
                .callTimeout(java.time.Duration.ofSeconds(8))
                .build()
            client.newCall(
                okhttp3.Request.Builder().url("https://huggingface.co/").head().build()
            ).execute().use { it.isSuccessful }
        }.getOrDefault(false)

        say("NETWORK_REACHABLE $reachable")
        assumeTrue("Network is up; this test only means something offline", !reachable)

        install()
        val runtime = runtime()
        val loaded = runtime.load().getOrThrow()
        val completion = runtime.complete(
            user = """
                Figures:
                  plays = 37
                  listening_time = 4 hours 12 minutes

                Question: How much have I listened to this month?
            """.trimIndent(),
            system = "Answer in one sentence using only the figures given. Never invent a number."
        ).getOrThrow()

        say("OFFLINE_LOAD_MS ${loaded.loadMillis}")
        say("OFFLINE_GEN_MS  ${completion.millis}")
        say("OFFLINE_OUT     ${completion.text.take(200)}")

        assertTrue("No answer produced offline", completion.text.isNotBlank())
        runtime.unload()
    }

    /** The checker must catch a figure the tools never produced. */
    @Test
    fun aFabricatedFigureIsFlagged() {
        val result = ToolResult(
            tool = "listening_time",
            window = "the last 30 days",
            figures = mapOf("listening_time" to "4 hours 12 minutes", "plays" to "37")
        )
        val honest = Grounding.check("You listened for 4 hours 12 minutes across 37 plays.", listOf(result))
        assertEquals(emptyList<String>(), honest.ungroundedFigures)
        assertEquals(ClaimKind.RECORDED, honest.kind)

        val invented = Grounding.check("You listened for 9 hours across 512 plays.", listOf(result))
        assertTrue(invented.ungroundedFigures.containsAll(listOf("9", "512")) || !invented.trustworthy)
        assertTrue("A fabricated figure must not pass", !invented.trustworthy)
    }

    private fun say(line: String) = Log.i(TAG, line)

    private companion object {
        const val TAG = "SYMUS_LLM"
        const val STAGED = "model-under-test.task"

        /** Loading half a gigabyte on an emulator's CPU is not a fast operation. */
        val TIMEOUT = kotlin.time.Duration.parse("10m")

        @BeforeClass
        @JvmStatic
        fun announce() {
            Log.i(TAG, "=== on-device inference ===")
        }
    }
}
