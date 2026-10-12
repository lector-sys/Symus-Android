package io.github.lectorsys.symus.ai

import android.content.Context

/**
 * The inference engine, as the `lite` build does not have it.
 *
 * **Why this build exists.** MediaPipe's inference runtime ships prebuilt native
 * libraries for `arm64-v8a` and `x86_64` and for no other ABI. An APK that
 * contains native libraries and none for the installing device's ABI is not
 * something Android installs and then degrades gracefully — the package manager
 * refuses it outright with `INSTALL_FAILED_NO_MATCHING_ABIS`. So on a 32-bit
 * phone the choice is not "music app without an assistant" versus "music app
 * with one", it is "music app" versus "nothing installs at all".
 *
 * This file is what makes the first option exist. The `lite` flavour omits the
 * MediaPipe dependency entirely, so nothing in that APK names a native class
 * that is not there, and the APK carries libraries for every ABI Compose needs
 * and no others.
 *
 * Nothing here throws on the way to being constructed, and nothing here is
 * touched during startup: [LlmRuntime.availability] reads [PRESENT] and returns
 * [LlmAvailability.RuntimeMissing] before any engine is asked for. Every other
 * feature — playback, downloads, widgets, themes, import, statistics, and the
 * statistics assistant — is identical to the `full` build.
 */
class LlmEngine private constructor() {

    fun sizeInTokens(prompt: String): Int = -1

    fun generate(
        prompt: String,
        temperature: Float,
        topK: Int,
        seed: Int,
        maxChars: Int,
        stop: () -> Boolean
    ): String = throw UnsupportedOperationException(UNAVAILABLE_MESSAGE)

    fun close() = Unit

    companion object {
        /** Whether this build carries an inference runtime at all. */
        const val PRESENT = false

        /** None. A build with no runtime supports no ABI for one. */
        val SUPPORTED_ABIS = emptySet<String>()

        const val UNAVAILABLE_MESSAGE =
            "This build of SYmuS does not include the on-device inference runtime, so " +
                "conversation mode is unavailable. Everything else, including the " +
                "statistics assistant, works normally."

        fun create(context: Context, modelPath: String, maxTokens: Int, topK: Int): LlmEngine =
            throw UnsupportedOperationException(UNAVAILABLE_MESSAGE)
    }
}
