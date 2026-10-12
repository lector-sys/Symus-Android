package io.github.lectorsys.symus.ai

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession

/**
 * The inference engine, as the `full` build has it: MediaPipe's LLM Inference
 * task, running a real transformer on this device's CPU.
 *
 * **Why this file exists at all.** It is the only place in the app that names a
 * MediaPipe type. Everything else — [LlmRuntime], the view models, the settings
 * screen — talks to this seam, which means the `lite` build can drop the
 * dependency by swapping one file rather than by threading a flag through the
 * feature. See `src/lite/.../LlmEngine.kt` for the other half, and
 * `deliverables/README.md` for why there are two builds.
 *
 * **On the deprecation warning.** `LlmInference` is marked deprecated in 0.10.35
 * because Google is moving this API to LiteRT-LM, whose Android artifact is not
 * published anywhere this build can reach. The task API is what ships, works,
 * and has x86_64 binaries. When the replacement lands, this file is the only one
 * that changes.
 */
class LlmEngine private constructor(private val inference: LlmInference) {

    fun sizeInTokens(prompt: String): Int =
        runCatching { inference.sizeInTokens(prompt) }.getOrDefault(-1)

    /**
     * One completion, streamed so that it can be stopped.
     *
     * **Why not `generateResponse()`.** The blocking call runs until the model
     * emits its end token or fills the context, and nothing can interrupt it —
     * not a coroutine cancellation, not a timeout, not the user leaving the
     * screen. A bundle with no chat template never emits an end token at all,
     * so it runs to the full 1280 and takes minutes on a CPU. That was
     * reproducible: an imported bundle answering "reply with one word" took
     * over twenty minutes.
     *
     * Streaming fixes both. [stop] is polled as chunks arrive, so cancelling
     * actually cancels; [maxChars] bounds an answer that will not end on its
     * own; and a model that emits a turn marker is cut off there rather than
     * being allowed to write the user's next line for them.
     *
     * Whatever has arrived when it stops is returned. A truncated answer is
     * worth more than a discarded one, and the caller can see it is short.
     */
    fun generate(
        prompt: String,
        temperature: Float,
        topK: Int,
        seed: Int,
        maxChars: Int,
        stop: () -> Boolean
    ): String {
        val options = LlmInferenceSession.LlmInferenceSessionOptions.builder()
            .setTemperature(temperature)
            .setTopK(topK)
            .setRandomSeed(seed)
            .build()
        return LlmInferenceSession.createFromOptions(inference, options).use { session ->
            session.addQueryChunk(prompt)

            // A StringBuffer rather than a StringBuilder: the listener runs on
            // a native callback thread and the loop below reads from this one.
            val text = StringBuffer()
            val finished = java.util.concurrent.CountDownLatch(1)
            var halted = false

            session.generateResponseAsync { partial, done ->
                if (partial != null) text.append(partial)
                if (done) finished.countDown()
            }

            while (finished.count > 0L) {
                if (finished.await(POLL_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) break
                val tooLong = text.length >= maxChars
                val ranOn = text.indexOf(PromptFormat.END) >= 0 ||
                    text.indexOf(PromptFormat.TURN) >= 0
                if (stop() || tooLong || ranOn) {
                    halted = true
                    runCatching { session.cancelGenerateResponseAsync() }
                    // The cancel is asynchronous too; give it a moment to land
                    // rather than closing the session out from under it.
                    finished.await(CANCEL_GRACE_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
                    break
                }
            }
            if (halted && text.isEmpty()) "" else text.toString()
        }
    }

    fun close() {
        runCatching { inference.close() }
    }

    companion object {
        /** Whether this build carries an inference runtime at all. */
        const val PRESENT = true

        /** How often the stream is checked for a reason to stop. */
        private const val POLL_MS = 120L

        /** How long a cancel is given to take effect before the session closes. */
        private const val CANCEL_GRACE_MS = 3_000L

        /**
         * The ABIs the runtime ships `.so` files for. Not a preference — a
         * device outside this set has no library to load, and the packaging in
         * `build.gradle.kts` filters to exactly these two for the same reason.
         */
        val SUPPORTED_ABIS = setOf("arm64-v8a", "x86_64")

        const val UNAVAILABLE_MESSAGE =
            "This build does not include the on-device inference runtime."

        fun create(context: Context, modelPath: String, maxTokens: Int, topK: Int): LlmEngine {
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelPath)
                .setMaxTokens(maxTokens)
                .setMaxTopK(topK)
                // CPU explicitly. The GPU delegate is faster where it works, but
                // where it does not it fails inside native code after the weights
                // are already mapped, and a slower answer beats an inference path
                // that works on some drivers only.
                .setPreferredBackend(LlmInference.Backend.CPU)
                .build()
            return LlmEngine(LlmInference.createFromOptions(context, options))
        }
    }
}
