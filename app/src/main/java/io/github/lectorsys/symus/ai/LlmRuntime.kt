package io.github.lectorsys.symus.ai

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.core.content.getSystemService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether this device can run a model at all, decided before anything is
 * downloaded rather than after.
 *
 * Every branch except [Ready] is a sentence the settings screen shows. A user
 * on a device that cannot do this is owed the reason — "your phone has 2 GB and
 * this needs 4" is a fact they can act on, a greyed-out button is not.
 */
sealed interface LlmAvailability {
    data object Ready : LlmAvailability
    data class UnsupportedAbi(val abis: List<String>) : LlmAvailability
    data class NotEnoughMemory(val haveBytes: Long, val needBytes: Long) : LlmAvailability
    data class RuntimeMissing(val message: String) : LlmAvailability
}

/** What loading actually cost, reported rather than estimated. */
data class LoadedModel(
    val file: File,
    val spec: ModelSpec?,
    val loadMillis: Long,
    val backend: String
) {
    val name: String get() = spec?.name ?: "Imported model"
}

/** One completion, with the numbers needed to say whether it is usable. */
data class Completion(val text: String, val millis: Long, val promptTokens: Int)

/**
 * The on-device inference runtime.
 *
 * **What this is.** A thin wrapper over MediaPipe's LLM Inference task, which
 * is a real transformer running on this device's CPU. There is no network call
 * in this file and no remote fallback anywhere behind it: if the model is not
 * installed, the feature is absent rather than quietly served from somewhere
 * else.
 *
 * **Why generations are serialised.** The native session is not reentrant and
 * a second `generateResponse` while one is in flight is a native crash, not an
 * exception. One mutex is cheaper than a pool of engines, each of which would
 * hold its own copy of the weights.
 *
 * **On the deprecation warning.** `LlmInference` is marked deprecated in
 * 0.10.35 because Google is moving this API to LiteRT-LM, whose Android
 * artifact is not yet published anywhere this build can reach. The task API is
 * what ships, works, and has x86_64 binaries, so it is what this uses; the
 * wrapper exists partly so that swap is one file when the replacement lands.
 *
 * **Why failures are caught this broadly.** Loading a model bundle runs native
 * code over a file whose shape this code cannot check first. An imported
 * bundle for the wrong runtime throws whatever the native layer felt like, up
 * to and including [UnsatisfiedLinkError] on a device with no matching .so.
 * None of that may take the process down, because the process is also playing
 * music.
 */
@Singleton
class LlmRuntime @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val store: ModelStore
) {

    private val _loaded = MutableStateFlow<LoadedModel?>(null)
    val loaded: StateFlow<LoadedModel?> = _loaded.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val lock = Mutex()
    private var engine: LlmEngine? = null

    /** Total RAM, which is what the runtime is really constrained by. */
    fun deviceMemoryBytes(): Long = runCatching {
        val info = ActivityManager.MemoryInfo()
        context.getSystemService<ActivityManager>()?.getMemoryInfo(info)
        info.totalMem
    }.getOrDefault(0L)

    /**
     * Whether [spec] could run here. Checked against the ABI first because a
     * device with no matching native library cannot run *any* model, and
     * telling someone to free up storage for a download that can never load
     * would be a lie.
     */
    fun availability(spec: ModelSpec?): LlmAvailability {
        // Asked before anything else, and answered without touching the engine:
        // in the `lite` build there is no runtime to touch. This is the branch
        // that keeps a 32-bit install from ever reaching native code.
        if (!LlmEngine.PRESENT) return LlmAvailability.RuntimeMissing(LlmEngine.UNAVAILABLE_MESSAGE)

        val abis = Build.SUPPORTED_ABIS?.toList().orEmpty()
        if (abis.none { it in LlmEngine.SUPPORTED_ABIS }) return LlmAvailability.UnsupportedAbi(abis)

        val need = spec?.minRamBytes ?: ModelCatalog.small.minRamBytes
        val have = deviceMemoryBytes()
        // A zero reading means the query failed, not that the device has no
        // memory; refusing on that would disable the feature over a bad API call.
        if (have in 1 until need) return LlmAvailability.NotEnoughMemory(have, need)
        return LlmAvailability.Ready
    }

    val isLoaded: Boolean get() = engine != null

    /**
     * Loads the installed bundle. Returns the load cost so the UI can show it
     * and the report can quote it.
     */
    suspend fun load(): Result<LoadedModel> = withContext(Dispatchers.IO) {
        lock.withLock {
            val file = store.installed()
                ?: return@withLock Result.failure(IllegalStateException("No model is installed."))
            val spec = store.installedSpec()

            // Already loaded, and from the same file: hand back what is open.
            //
            // Not just a saving. Each create/close cycle maps the weights and
            // builds an XNNPACK weight cache again, and on a small device that
            // churn is where the runtime has been observed to abort inside
            // native code — a crash no Kotlin catch can intercept, in a process
            // that is also playing music. Loading twice was never useful; now
            // it does not happen.
            val open = _loaded.value
            if (engine != null && open != null && open.file.absolutePath == file.absolutePath) {
                return@withLock Result.success(open)
            }
            when (val state = availability(spec)) {
                is LlmAvailability.RuntimeMissing ->
                    return@withLock Result.failure(IllegalStateException(state.message))

                is LlmAvailability.UnsupportedAbi ->
                    return@withLock Result.failure(
                        IllegalStateException(
                            "This device reports ${state.abis.joinToString()}, and the runtime " +
                                "only ships arm64-v8a and x86_64."
                        )
                    )

                is LlmAvailability.NotEnoughMemory ->
                    return@withLock Result.failure(
                        IllegalStateException(
                            "${state.needBytes / (1024 * 1024 * 1024)} GB of RAM is needed and " +
                                "this device has ${state.haveBytes / (1024 * 1024 * 1024)} GB."
                        )
                    )

                else -> Unit
            }

            closeEngine()
            val started = System.currentTimeMillis()
            runCatching {
                LlmEngine.create(context, file.absolutePath, MAX_TOKENS, TOP_K)
            }.fold(
                onSuccess = { created ->
                    engine = created
                    val model = LoadedModel(
                        file = file,
                        spec = spec,
                        loadMillis = System.currentTimeMillis() - started,
                        backend = "CPU"
                    )
                    _loaded.value = model
                    Result.success(model)
                },
                onFailure = { failure ->
                    engine = null
                    _loaded.value = null
                    Result.failure(
                        IllegalStateException(readableLoadFailure(failure), failure)
                    )
                }
            )
        }
    }

    /**
     * One completion. Serialised against every other call into the runtime.
     *
     * [system] and [user] are kept apart rather than concatenated by the caller
     * because the chat template has to put them in different places, and which
     * template applies is a property of the loaded model, not of the question.
     */
    suspend fun complete(
        user: String,
        system: String = DEFAULT_SYSTEM,
        temperature: Float = 0.2f,
        seed: Int = 1,
        /**
         * A ceiling on the answer, in characters.
         *
         * Not a style preference. A bundle whose chat template is unknown never
         * emits an end-of-turn token, so nothing but this stops it before the
         * context is full — which on a CPU is minutes per question.
         */
        maxChars: Int = MAX_ANSWER_CHARS
    ): Result<Completion> = withContext(Dispatchers.IO) {
        lock.withLock {
            val active = engine
                ?: return@withLock Result.failure(IllegalStateException("No model is loaded."))
            // An imported bundle has no catalogue entry and therefore no known
            // template. ChatML is the guess, not PLAIN.
            //
            // PLAIN was the honest answer and the wrong one. A model given no
            // template never emits an end-of-turn token, so the runtime has
            // nothing to stop on and generates until the context is full — over
            // twenty minutes on a CPU for a one-word question. ChatML is what
            // essentially every instruction model converted for this runtime was
            // trained with; where it is wrong the markers are a few stray tokens
            // of prompt, which costs a slightly worse answer instead of an
            // unusable feature.
            val format = _loaded.value?.spec?.format ?: PromptFormat.CHAT_ML
            val prompt = format.wrap(system, user)
            _busy.value = true
            val started = System.currentTimeMillis()
            runCatching {
                val tokens = active.sizeInTokens(prompt)
                // Temperature is low but non-zero: this model routes tool calls
                // and phrases fixed figures. Creativity is not the job.
                //
                // The stop predicate is this coroutine's own cancellation. The
                // generation runs on a native thread, so without it a cancelled
                // question keeps burning CPU until the model runs out of context
                // — the caller returns, the work does not.
                val job = currentCoroutineContext()[Job]
                val deadline = started + GENERATION_TIMEOUT_MS
                val text = active.generate(
                    prompt = prompt,
                    temperature = temperature,
                    topK = TOP_K,
                    seed = seed,
                    maxChars = maxChars,
                    stop = {
                        job?.isActive == false || System.currentTimeMillis() > deadline
                    }
                )
                ensureActive()
                Completion(clean(text), System.currentTimeMillis() - started, tokens)
            }.also { _busy.value = false }
                .recoverCatching { failure ->
                    throw IllegalStateException(
                        "The model stopped partway through an answer.",
                        failure
                    )
                }
        }
    }

    suspend fun unload() = withContext(Dispatchers.IO) {
        lock.withLock { closeEngine() }
    }

    private fun closeEngine() {
        runCatching { engine?.close() }
        engine = null
        _loaded.value = null
    }

    /**
     * Strips the template's own turn markers back out.
     *
     * The runtime stops at the end-of-turn token most of the time and not all
     * of the time; when it runs on, the tail is the start of a turn the model
     * invented for the user, and showing that would be showing the user words
     * they did not say.
     */
    private fun clean(raw: String): String = raw
        .substringBefore(PromptFormat.END)
        .substringBefore(PromptFormat.TURN)
        .trim()

    private fun readableLoadFailure(failure: Throwable): String = when {
        // The `lite` build's engine refuses on the way in rather than failing
        // somewhere native. Its own sentence is the one worth showing.
        failure is UnsupportedOperationException ->
            failure.message ?: LlmEngine.UNAVAILABLE_MESSAGE

        failure is UnsatisfiedLinkError ->
            "The inference library is missing for this device's processor."

        failure.message?.contains("magic", ignoreCase = true) == true ||
            failure.message?.contains("format", ignoreCase = true) == true ->
            "That file is not a model bundle this runtime can read."

        failure is OutOfMemoryError ->
            "The device ran out of memory loading the model."

        else -> failure.message ?: "The model could not be loaded."
    }

    private companion object {
        /**
         * Must not exceed what the bundle was converted for — both catalogue
         * entries are ekv1280 — or the runtime rejects the graph at load.
         */
        const val MAX_TOKENS = 1280
        const val TOP_K = 40

        /**
         * The ceiling on one answer. Three or four sentences of prose, or a
         * routing line, both fit comfortably; a model running away does not.
         */
        const val MAX_ANSWER_CHARS = 900

        /**
         * A wall-clock stop for one generation. The assistant is answering a
         * question about a music library, and an answer nobody waited for is
         * not an answer.
         */
        const val GENERATION_TIMEOUT_MS = 90_000L

        const val DEFAULT_SYSTEM = "You are a helpful assistant inside a music app."
    }
}
