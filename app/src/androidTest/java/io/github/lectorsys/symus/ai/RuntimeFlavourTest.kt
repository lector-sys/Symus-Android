package io.github.lectorsys.symus.ai

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lectorsys.symus.media.Extractor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * What each build says about itself, asserted rather than assumed.
 *
 * The same test runs in both flavours and expects opposite things, because the
 * two builds exist to behave differently. In `full` the runtime is present and
 * must be usable on a supported ABI; in `lite` it is absent and must say so
 * without ever reaching a native class — which is the property that makes the
 * 32-bit APK installable in the first place.
 */
@RunWith(AndroidJUnit4::class)
class RuntimeFlavourTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun theBuildReportsItsOwnInferenceCapabilityHonestly(): Unit = runBlocking {
        val runtime = LlmRuntime(context, ModelStore(context, Extractor.okHttp()))
        val availability = runtime.availability(ModelCatalog.small)
        say("FLAVOUR_PRESENT   ${LlmEngine.PRESENT}")
        say("FLAVOUR_ABIS      ${Build.SUPPORTED_ABIS.joinToString()}")
        say("FLAVOUR_SUPPORTED ${LlmEngine.SUPPORTED_ABIS}")
        say("FLAVOUR_AVAIL     $availability")

        if (LlmEngine.PRESENT) {
            assertFalse(
                "A build with a runtime must not report it missing",
                availability is LlmAvailability.RuntimeMissing
            )
        } else {
            assertTrue(
                "A build without a runtime must say exactly that, got $availability",
                availability is LlmAvailability.RuntimeMissing
            )
            assertTrue("A runtime-free build supports no ABI for one", LlmEngine.SUPPORTED_ABIS.isEmpty())
        }
    }

    /**
     * In the `lite` build, a model file sitting on disk must still not lead
     * anywhere near native code — it must fail on the availability check, with a
     * sentence, and leave the process alive.
     */
    @Test
    fun aRuntimeFreeBuildRefusesToLoadWithoutTouchingNativeCode(): Unit = runBlocking {
        if (LlmEngine.PRESENT) {
            say("FLAVOUR_SKIP_LOAD full build; covered by OfflineAssistantTest")
            return@runBlocking
        }

        // A plausible-looking file, so the refusal cannot be blamed on absence.
        val dir = File(context.filesDir, "models").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val decoy = File(dir, "qwen2.5-0.5b-instruct-q8.task")
        decoy.outputStream().use { out ->
            val chunk = ByteArray(1 shl 20)
            repeat(16) { out.write(chunk) }
        }

        val runtime = LlmRuntime(context, ModelStore(context, Extractor.okHttp()))
        val result = runtime.load()
        val message = result.exceptionOrNull()?.message
        say("FLAVOUR_LOAD_FAILED ${result.isFailure}")
        say("FLAVOUR_LOAD_MSG    $message")

        assertTrue("A runtime-free build must refuse to load a model", result.isFailure)
        assertFalse("Nothing may be loaded", runtime.isLoaded)
        assertTrue(
            "The refusal must explain itself, got: $message",
            message?.contains("inference runtime", ignoreCase = true) == true
        )
        decoy.delete()
    }

    private fun say(line: String) = Log.i(TAG, line)

    private companion object {
        const val TAG = "SYMUS_LLM"
    }
}
