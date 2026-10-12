package io.github.lectorsys.symus.ai

/**
 * A model the app knows how to install, and everything a user needs to decide
 * whether to.
 *
 * [sizeBytes] and [sha256] are published values, not values learned at download
 * time. A download that reports its own size is not a check of anything; these
 * are what the file is supposed to be, recorded here so a truncated transfer, a
 * captive-portal HTML page, or a substituted file all fail loudly rather than
 * becoming a model that loads and behaves strangely.
 *
 * [license] is stated because these weights are not the app's to relicense.
 * Both entries are Apache-2.0, which is why they are the ones offered: a
 * gated or non-commercial model would put the user in a position the app never
 * explained to them.
 */
/**
 * How a model wants its turns delimited.
 *
 * This is not cosmetic. An instruction-tuned model asked a bare question
 * without its template does not follow instructions — it continues the text.
 * The first run of this feature answered "what colour is the sky" with "The
 * colour of a clear midday sky.", which is a perfect completion of a sentence
 * and a useless answer to a question.
 */
enum class PromptFormat {
    /** Qwen, and most instruction models converted for this runtime. */
    CHAT_ML,

    /**
     * No wrapping.
     *
     * Kept for a bundle known to want raw text, but no longer the default for
     * an unknown one — see [ModelSpec.format] and `LlmRuntime.complete`. A model
     * given no template emits no end-of-turn token, so nothing tells the runtime
     * to stop and it generates until the context is full. Measured on an
     * imported bundle answering "reply with one word": over twenty minutes.
     */
    PLAIN;

    fun wrap(system: String, user: String): String = when (this) {
        CHAT_ML -> buildString {
            append(TURN).append("system\n").append(system).append(END).append('\n')
            append(TURN).append("user\n").append(user).append(END).append('\n')
            append(TURN).append("assistant\n")
        }

        PLAIN -> system + "\n\n" + user + "\n"
    }

    companion object {
        const val TURN = "<|im_start|>"
        const val END = "<|im_end|>"
    }
}

data class ModelSpec(
    val id: String,
    val name: String,
    val parameters: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String,
    val license: String,
    val licenseUrl: String,
    val sourceUrl: String,
    /**
     * Refuses to load below this. The bundle is memory-mapped but the KV cache
     * and activations are not, and an OOM kill during generation takes the
     * whole app — including playback — with it.
     */
    val minRamBytes: Long,
    val contextTokens: Int,
    val quantisation: String,
    val format: PromptFormat = PromptFormat.CHAT_ML
) {
    val sizeLabel: String get() = "%.0f MB".format(sizeBytes / 1_000_000.0)
}

/**
 * The two models offered, and why only two.
 *
 * A long list would be a worse decision surface, not a better one: the
 * meaningful choice here is "small enough for my phone" versus "good enough to
 * hold a conversation", and that is one axis with two useful points on it.
 * Anything else the user wants can be side-loaded through
 * [ModelStore.importFrom], which accepts any MediaPipe .task bundle.
 */
object ModelCatalog {

    val small = ModelSpec(
        id = "qwen2.5-0.5b-instruct-q8",
        name = "Qwen2.5 0.5B Instruct",
        parameters = "0.5B",
        url = "https://huggingface.co/litert-community/Qwen2.5-0.5B-Instruct/resolve/main/" +
            "Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
        sizeBytes = 546_660_344L,
        sha256 = "e608953f169aeb1bd7b9155fec2559825e08453fc209b84eda3a781ed0452fd2",
        license = "Apache-2.0",
        licenseUrl = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct/blob/main/LICENSE",
        sourceUrl = "https://huggingface.co/litert-community/Qwen2.5-0.5B-Instruct",
        // 546 MB of weights are memory-mapped rather than read in; what the
        // process actually has to hold is the KV cache and activations, which
        // measures in the low hundreds of MB. 1.5 GB of total RAM is the point
        // below which the system starts killing the player to feed the model.
        minRamBytes = 1_610_612_736L,
        contextTokens = 1280,
        quantisation = "int8"
    )

    val large = ModelSpec(
        id = "qwen2.5-1.5b-instruct-q8",
        name = "Qwen2.5 1.5B Instruct",
        parameters = "1.5B",
        url = "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/" +
            "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
        sizeBytes = 1_597_913_616L,
        sha256 = "8d867a7c93a6acf2892f08e0174e2f6f351ad256b7e3cfb6d6cd9c89794b42e0",
        license = "Apache-2.0",
        licenseUrl = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct/blob/main/LICENSE",
        sourceUrl = "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct",
        minRamBytes = 3L * 1024 * 1024 * 1024,
        contextTokens = 1280,
        quantisation = "int8"
    )

    val entries = listOf(small, large)

    fun byId(id: String?): ModelSpec? = entries.firstOrNull { it.id == id }
}
