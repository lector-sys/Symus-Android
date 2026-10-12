package io.github.lectorsys.symus.ui.assistant

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.lectorsys.symus.ai.ChatAgent
import io.github.lectorsys.symus.ai.ChatTurn
import io.github.lectorsys.symus.ai.ClaimKind
import io.github.lectorsys.symus.ai.GroundedAnswer
import io.github.lectorsys.symus.ai.LlmRuntime
import io.github.lectorsys.symus.ai.ModelStore
import io.github.lectorsys.symus.library.Answer
import io.github.lectorsys.symus.library.Assistant
import io.github.lectorsys.symus.library.Listening
import io.github.lectorsys.symus.library.ListeningSummary
import io.github.lectorsys.symus.data.LibraryRepository
import io.github.lectorsys.symus.library.Window
import io.github.lectorsys.symus.media.Playback
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Which engine is answering.
 *
 * Two, not one, and the user picks. [STATISTICS] is the fixed-intent query
 * engine: it cannot hold a conversation and it cannot be wrong about a number.
 * [CONVERSATION] is a real model that phrases and follows up, and whose figures
 * still come from the same queries. Neither replaces the other, so neither is
 * hidden behind the other.
 */
enum class AssistantMode { STATISTICS, CONVERSATION }

/** One turn of the conversation, in whichever form produced it. */
data class Exchange(
    val question: String,
    val answer: Answer? = null,
    val grounded: GroundedAnswer? = null,
    val millis: Long = 0
)

data class AssistantUiState(
    val draft: String = "",
    val history: List<Exchange> = emptyList(),
    val thinking: Boolean = false,
    val summary: ListeningSummary? = null,
    val mode: AssistantMode = AssistantMode.STATISTICS,
    /** Null until a model is installed; the name once one is. */
    val modelName: String? = null,
    val modelReady: Boolean = false,
    val loading: Boolean = false,
    val modelError: String? = null
)

@HiltViewModel
class AssistantViewModel @Inject constructor(
    private val assistant: Assistant,
    private val listening: Listening,
    private val agent: ChatAgent,
    private val runtime: LlmRuntime,
    private val store: ModelStore,
    private val library: LibraryRepository,
    private val playback: Playback
) : ViewModel() {

    private val _state = MutableStateFlow(AssistantUiState())
    val state: StateFlow<AssistantUiState> = _state.asStateFlow()

    val suggestions = assistant.suggestions

    /**
     * The conversational suggestions are phrased as sentences rather than
     * labels, because that is the mode where phrasing is the point — and
     * because a follow-up only makes sense after a question that had one.
     */
    val chatSuggestions = listOf(
        "How much have I listened to this month?",
        "Who are my top artists?",
        "What should I play right now?",
        "How does backup work?"
    )

    private var running: Job? = null

    init {
        store.refresh()
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            _state.value = _state.value.copy(
                summary = runCatching { listening.summary(Window.last30Days(now)) }.getOrNull(),
                modelName = store.installedSpec()?.name
                    ?: store.installed()?.let { "Imported model" },
                modelReady = runtime.isLoaded
            )
        }
    }

    fun setDraft(text: String) {
        _state.value = _state.value.copy(draft = text)
    }

    /**
     * Switching to conversation loads the model, which takes long enough to
     * need saying. Switching away does not unload it: a user toggling back and
     * forth should not pay the load cost twice, and the weights are mapped
     * rather than copied.
     */
    fun setMode(mode: AssistantMode) {
        _state.value = _state.value.copy(mode = mode, modelError = null)
        if (mode == AssistantMode.CONVERSATION && !runtime.isLoaded) loadModel()
    }

    fun loadModel() {
        if (store.installed() == null) {
            _state.value = _state.value.copy(
                modelError = "No model is installed. Settings has the download."
            )
            return
        }
        _state.value = _state.value.copy(loading = true, modelError = null)
        viewModelScope.launch {
            val result = runtime.load()
            _state.value = _state.value.copy(
                loading = false,
                modelReady = result.isSuccess,
                modelName = result.getOrNull()?.name ?: _state.value.modelName,
                modelError = result.exceptionOrNull()?.message
            )
        }
    }

    fun ask(question: String = _state.value.draft) {
        val text = question.trim()
        if (text.isEmpty() || _state.value.thinking) return
        _state.value = _state.value.copy(draft = "", thinking = true)

        running = viewModelScope.launch {
            val exchange = when (_state.value.mode) {
                AssistantMode.STATISTICS -> statisticsAnswer(text)
                AssistantMode.CONVERSATION -> conversationAnswer(text)
            }
            _state.value = _state.value.copy(
                // Newest first: the answer just asked for should not require
                // scrolling past everything that came before it.
                history = listOf(exchange) + _state.value.history,
                thinking = false
            )
        }
    }

    /**
     * Plays the tracks an answer listed.
     *
     * The rows carry ids rather than whole tracks because the model's view of
     * them is a placeholder; the real rows are fetched here, at the moment the
     * user asks for them.
     */
    fun playRows(videoIds: List<String>, index: Int) {
        if (videoIds.isEmpty()) return
        viewModelScope.launch {
            val tracks = runCatching { library.byIds(videoIds) }.getOrDefault(emptyList())
            // byIds does not promise the order it was asked in, and the row the
            // user tapped has to be the one that starts.
            val ordered = videoIds.mapNotNull { id -> tracks.firstOrNull { it.videoId == id } }
            if (ordered.isNotEmpty()) playback.playNow(ordered, index.coerceIn(0, ordered.lastIndex))
        }
    }

    /** Stops a generation that is taking longer than the user wants to wait. */
    fun cancel() {
        running?.cancel()
        running = null
        _state.value = _state.value.copy(thinking = false)
    }

    fun clear() {
        _state.value = _state.value.copy(history = emptyList())
    }

    private suspend fun statisticsAnswer(text: String): Exchange {
        val started = System.currentTimeMillis()
        val answer = runCatching { assistant.ask(text) }.getOrElse {
            Answer("Something went wrong reading your library.", insufficientData = true)
        }
        return Exchange(text, answer = answer, millis = System.currentTimeMillis() - started)
    }

    private suspend fun conversationAnswer(text: String): Exchange {
        if (!runtime.isLoaded) {
            val loaded = runtime.load()
            if (loaded.isFailure) {
                return Exchange(
                    text,
                    grounded = GroundedAnswer(
                        text = loaded.exceptionOrNull()?.message
                            ?: "The model could not be loaded.",
                        kind = ClaimKind.UNAVAILABLE,
                        facts = emptyList(),
                        rows = emptyList(),
                        sources = emptyList()
                    )
                )
            }
            _state.value = _state.value.copy(modelReady = true)
        }

        // Only turns that actually produced text are context. A failed turn is
        // not something the model should try to be consistent with.
        val context = _state.value.history
            .mapNotNull { exchange ->
                exchange.grounded?.takeIf { it.kind != ClaimKind.UNAVAILABLE }
                    ?.let { ChatTurn(exchange.question, it.text, it.toolsUsed) }
            }
            .asReversed()

        val reply = agent.ask(text, context)
        return reply.fold(
            onSuccess = { Exchange(text, grounded = it.answer, millis = it.trace.totalMillis) },
            onFailure = {
                Exchange(
                    text,
                    grounded = GroundedAnswer(
                        text = it.message ?: "The model could not answer that.",
                        kind = ClaimKind.UNAVAILABLE,
                        facts = emptyList(),
                        rows = emptyList(),
                        sources = emptyList()
                    )
                )
            }
        )
    }
}
