package io.github.lectorsys.symus.ai

import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One turn kept for context, so "what about last month?" has an antecedent.
 *
 * [tools] is what the turn actually called. Without it a follow-up has to be
 * re-derived from the prose, and a 0.5B model re-deriving it guesses: asked
 * "and what about all time?" after a listening question it routed to
 * `app_feature` and answered by repeating the previous turn verbatim. Naming
 * the tool it just used removes the guess.
 */
data class ChatTurn(
    val question: String,
    val answer: String,
    val tools: List<String> = emptyList()
)

/**
 * A model call that was attempted, and what it cost. Reported, not estimated.
 */
data class AgentTrace(
    val toolCalls: List<String>,
    val modelCalls: Int,
    val totalMillis: Long,
    val promptTokens: Int
)

data class AgentReply(val answer: GroundedAnswer, val trace: AgentTrace)

/**
 * The conversation loop: a model that routes, tools that know, and an app that
 * checks the result before showing it.
 *
 * **The division of labour.** The model does two things — decide which tool a
 * sentence is asking for, and turn the tool's output into English that follows
 * on from the last thing the user said. It computes nothing. Every figure in a
 * finished answer came out of SQLite through [Tools], and [Grounding] rejects
 * the ones that did not.
 *
 * **Why the loop is capped.** Two tool calls. A small model given an open loop
 * will call the same tool repeatedly, or chain until the context is full of its
 * own observations. Two is enough for "top artists this month" followed by
 * "and how long was that", and the cap is what keeps a stuck model from
 * spending a minute of the user's battery.
 *
 * **Why the prompt is rebuilt every turn.** The session is not reused between
 * questions, so what the model remembers is exactly what is written here: the
 * last few turns, in full, under the app's control. There is no hidden state to
 * diverge from what the user can see on screen.
 */
@Singleton
class ChatAgent @Inject constructor(
    private val runtime: LlmRuntime,
    private val tools: Tools
) {

    suspend fun ask(
        question: String,
        history: List<ChatTurn>,
        now: Long = System.currentTimeMillis()
    ): Result<AgentReply> {
        val started = System.currentTimeMillis()
        val used = mutableListOf<ToolResult>()
        val called = mutableListOf<String>()
        var modelCalls = 0
        var promptTokens = 0

        var prompt = routingPrompt(question, history)

        repeat(MAX_TOOL_CALLS) {
            val completion = runtime.complete(user = prompt, system = ROUTING_SYSTEM, temperature = 0f)
                .getOrElse { return Result.failure(it) }
            modelCalls++
            if (promptTokens == 0) promptTokens = completion.promptTokens

            val call = parseToolCall(completion.text)
                ?: followUpFallback(question, history, used)
                ?: return@repeat // no tool wanted; fall through to the writing pass

            if (call.first in called && used.size >= 1) return@repeat // no re-asking the same thing

            val result = tools.run(call.first, applyWindow(question, call.second), now)
            used += result
            called += call.first
            prompt = routingPrompt(question, history, used)
        }

        val writing = writingPrompt(question, history, used)
        val final = runtime.complete(user = writing, system = WRITING_SYSTEM)
            .getOrElse { return Result.failure(it) }
        modelCalls++

        // No retry, and no second chance at the numbers.
        //
        // An earlier version asked the model again whenever the check caught an
        // invented figure. It often worked, which was the problem: it made the
        // feature look reliable while resting on the same thing going wrong
        // less often. The figures a user reads are now rendered by the app from
        // the tool results — see [GroundedAnswer.facts] and the assistant
        // screen — and the model's sentence is commentary beside them. When the
        // sentence is wrong the screen says so and shows the recorded figures
        // instead of it, which is a guarantee rather than an improved average.
        val answer = Grounding.check(final.text, used)
        return Result.success(
            AgentReply(
                answer = answer,
                trace = AgentTrace(
                    toolCalls = called,
                    modelCalls = modelCalls,
                    totalMillis = System.currentTimeMillis() - started,
                    promptTokens = promptTokens
                )
            )
        )
    }

    // -- prompts -------------------------------------------------------------

    private fun toolMenu() = tools.specs.joinToString("\n") { spec ->
        "- ${spec.name} ${spec.arguments} — ${spec.description}"
    }

    /**
     * Asks only "which tool, if any". Kept separate from the writing pass
     * because a model asked to decide and compose at once does both worse, and
     * a routing answer is short enough to be cheap to get wrong and retry.
     */
    private fun routingPrompt(
        question: String,
        history: List<ChatTurn>,
        results: List<ToolResult> = emptyList()
    ) = buildString {
        appendLine("Tools:")
        appendLine(toolMenu())
        appendLine()
        appendLine("Reply with ONE line of JSON: {\"tool\":\"name\",\"args\":{...}}")
        // DONE is only offered once something has actually been retrieved.
        // "The tools already answer this" cannot be true before any tool has
        // run, and offering the option anyway is how a follow-up ended up
        // answered with the previous turn's figures and no new query at all.
        if (results.isNotEmpty()) {
            appendLine("If the figures below already answer the question, reply exactly: DONE")
        } else {
            appendLine("You must choose a tool. Do not reply DONE.")
        }
        appendLine()
        // Worked examples, because a 0.5B model given only a schema describes
        // the schema instead of using it — the first version of this prompt got
        // prose back roughly half the time and the loop then had no tool to run.
        // These are safe to show in full: routing sees no figures, so there is
        // nothing here for the model to copy into an answer later.
        // "the last 30 days" is how the app labels the month window, so a
        // question phrased that way has to land on month rather than week.
        appendLine("Windows: \"this week\"/\"7 days\" = week. \"this month\"/\"30 days\" = month.")
        appendLine("\"this year\"/\"365 days\" = year. \"ever\"/\"all time\"/\"total\" = all.")
        appendLine()
        // Order matters. With the app_feature example last, a follow-up like
        // "and what about all time?" routed to app_feature — the model was
        // copying the nearest example rather than reading the question. The
        // follow-up cases go last because those are the ones it gets wrong.
        appendLine("Examples:")
        appendLine("Question: how does backup work")
        appendLine("""{"tool":"app_feature","args":{"topic":"backup"}}""")
        appendLine("Question: who have I played most this year")
        appendLine("""{"tool":"top_artists","args":{"window":"year","limit":5}}""")
        appendLine("Question: how long did I listen this week")
        appendLine("""{"tool":"listening_time","args":{"window":"week"}}""")
        appendLine("Question: how much have I listened in the last 30 days")
        appendLine("""{"tool":"listening_time","args":{"window":"month"}}""")
        appendLine("Previous tool: listening_time. Question: and what about all time?")
        appendLine("""{"tool":"listening_time","args":{"window":"all"}}""")
        appendLine("Previous tool: top_artists. Question: and this week?")
        appendLine("""{"tool":"top_artists","args":{"window":"week","limit":5}}""")
        appendLine()
        if (history.isNotEmpty()) {
            appendLine("Earlier in this conversation:")
            history.takeLast(HISTORY_TURNS).forEach { turn ->
                appendLine("  user: ${turn.question}")
                appendLine("  you: ${turn.answer.take(160)}")
            }
            appendLine()
            history.lastOrNull()?.tools?.firstOrNull()?.let { previous ->
                appendLine("The previous question was answered with: $previous")
                appendLine("Unless this question changes the subject, use $previous again.")
                appendLine()
            }
            appendLine("Follow-ups refer to the previous question: keep the same tool and")
            appendLine("change only the argument the follow-up mentions.")
            appendLine("  \"what about last month?\"  -> same tool, window=month")
            appendLine("  \"and all time?\"           -> same tool, window=all")
            appendLine("  \"and this week?\"          -> same tool, window=week")
            appendLine("A follow-up always needs a tool call. Never reply DONE to one")
            appendLine("unless the figures above already cover the period it asks about.")
            appendLine()
        }
        if (results.isNotEmpty()) {
            appendLine("Already retrieved:")
            results.forEach { appendLine(it.forModel()) }
            appendLine()
        }
        // Phrased exactly like the follow-up examples above when there is a
        // previous tool, so the model has one pattern to complete rather than
        // two to choose between.
        val previous = history.lastOrNull()?.tools?.firstOrNull()
        if (previous != null && results.isEmpty()) {
            appendLine("Previous tool: $previous. Question: $question")
        } else {
            appendLine("Question: $question")
        }
    }

    /**
     * The composing pass. The rules are stated as prohibitions because that is
     * what a small model follows: "do not invent a number" survives a rephrase,
     * "be accurate" does not.
     */
    private fun writingPrompt(
        question: String,
        history: List<ChatTurn>,
        results: List<ToolResult>
    ) = buildString {
        appendLine("Rules:")
        appendLine("- Write full sentences addressed to the user, starting with \"You\".")
        appendLine("- Copy each figure's value character for character, but never copy a")
        appendLine("  field name, an equals sign, or the word TOOL. Those are not English.")
        appendLine("- Use ONLY the figures below. Never calculate, estimate or round them.")
        appendLine("- Never add figures together and never convert between units.")
        // The model was writing "in the last 7 days" over figures that came
        // from a different window — the right numbers under the wrong label,
        // which is a false statement made out of true parts.
        appendLine("- The period is in brackets after the tool name. Use those exact")
        appendLine("  words for the period, and never any other period.")
        appendLine("- Items are written <<1>>, <<2>>. Copy those markers exactly; the app")
        appendLine("  replaces them with real names. Never invent a name.")
        appendLine("- If a tool says NO DATA, say plainly that there is not enough recorded yet.")
        appendLine("- Suggestions are suggestions. Do not present them as things the user did.")
        appendLine("- No markdown, no lists, no preamble. Plain sentences.")
        appendLine()
        if (history.isNotEmpty()) {
            appendLine("Earlier:")
            history.takeLast(HISTORY_TURNS).forEach { turn ->
                appendLine("  user: ${turn.question}")
                appendLine("  you: ${turn.answer.take(160)}")
            }
            appendLine()
        }
        if (results.isEmpty()) {
            appendLine("No data was retrieved. Say you can only answer questions about their")
            appendLine("library, listening history and this app's features.")
        } else {
            appendLine("Figures:")
            results.forEach { appendLine(it.forModel()) }
        }
        appendLine()
        appendLine("Question: $question")
        appendLine()
        // Deliberately no worked example, after trying two.
        //
        // An example with real numbers taught the model to reproduce *those*
        // numbers: asked about a month it answered with the example's figures,
        // scaled. An example with letters in their place was worse — the model
        // read A, B and N as the row markers it had seen elsewhere, wrote
        // "<<A>> hours <<B>> minutes", and the marker cleanup stripped them,
        // leaving "You listened for hours minutes across plays".
        //
        // The prohibitions above are what actually work: they describe the
        // failure ("= is not English") without giving anything to copy.
        appendLine("Write the answer now, as one or two sentences.")
        appendLine("Answer:")
    }

    // -- parsing -------------------------------------------------------------

    /**
     * Finds a tool call in whatever the model produced.
     *
     * Deliberately forgiving about wrapping — a small model will fence the JSON,
     * prefix it with "Sure!", or add a trailing sentence — and deliberately
     * strict about content: the tool name must be one that exists, and the
     * arguments must parse. Anything else is treated as "no tool", which ends
     * the loop safely rather than guessing.
     */
    internal fun parseToolCall(text: String): Pair<String, JSONObject>? {
        if (text.trimStart().startsWith("DONE", ignoreCase = true)) return null
        val start = text.indexOf('{')
        if (start < 0) return null

        var depth = 0
        var end = -1
        for (index in start until text.length) {
            when (text[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        end = index
                        break
                    }
                }
            }
        }
        if (end < 0) return null

        return runCatching {
            val json = JSONObject(text.substring(start, end + 1))
            val name = json.optString("tool").ifBlank { json.optString("name") }
            if (tools.specs.none { it.name == name }) return null
            val args = json.optJSONObject("args")
                ?: json.optJSONObject("arguments")
                ?: JSONObject()
            name to args
        }.getOrNull()
    }

    // -- deterministic repairs -------------------------------------------------

    /**
     * The period the question names, when it names one.
     *
     * The window is a four-value enum, and which one a sentence means is not a
     * judgement call — "all time" means all time. Leaving it to a 0.5B model
     * produced the right tool with the wrong period often enough to matter, and
     * a correct figure under the wrong heading is a wrong answer. So the app
     * reads it directly and overrides whatever the model put in `args`.
     *
     * Returns null when the question names no period, which is the common case;
     * the tool's own default then applies, exactly as before.
     */
    internal fun windowHint(question: String): String? {
        val text = question.lowercase()
        return when {
            Regex("""all[- ]?time|\bever\b|\bin total\b|\boverall\b|\baltogether\b""")
                .containsMatchIn(text) -> "all"

            Regex("""\bthis year\b|\blast year\b|\b12 months\b|\b365 days\b|\bthe year\b""")
                .containsMatchIn(text) -> "year"

            Regex("""\bthis month\b|\blast month\b|\b30 days\b|\bthe month\b""")
                .containsMatchIn(text) -> "month"

            Regex("""\bthis week\b|\blast week\b|\b7 days\b|\bthe week\b""")
                .containsMatchIn(text) -> "week"

            else -> null
        }
    }

    /** Puts the question's own period into the arguments, over the model's. */
    private fun applyWindow(question: String, args: JSONObject): JSONObject {
        val hint = windowHint(question) ?: return args
        return JSONObject(args.toString()).put("window", hint)
    }

    /**
     * A follow-up the model failed to route, routed anyway.
     *
     * "And what about all time?" is not a question the app can answer without a
     * tool, and it is also the shape a small model most often replies to with
     * prose or DONE. When the previous turn used a tool and this question names
     * a period but no new subject, the only sensible reading is the same tool
     * over the new period — so that is what happens, rather than the user being
     * told there is nothing recorded.
     */
    private fun followUpFallback(
        question: String,
        history: List<ChatTurn>,
        used: List<ToolResult>
    ): Pair<String, JSONObject>? {
        if (used.isNotEmpty()) return null
        val previous = history.lastOrNull()?.tools?.firstOrNull() ?: return null
        if (tools.specs.none { it.name == previous }) return null
        val hint = windowHint(question) ?: return null
        // Only for tools that actually take a window; app_feature does not.
        if (tools.specs.first { it.name == previous }.arguments.contains("window").not()) return null
        return previous to JSONObject().put("window", hint)
    }

    private companion object {
        const val MAX_TOOL_CALLS = 2
        const val HISTORY_TURNS = 3

        const val ROUTING_SYSTEM =
            "You route questions about a music app to tools. You never answer from memory " +
                "and you never write prose. You reply with one line of JSON, or DONE."

        const val WRITING_SYSTEM =
            "You are the assistant inside a music app. You state what the user's own " +
                "records show, in two or three plain sentences. You never invent a number " +
                "and you never invent a name."
    }
}
