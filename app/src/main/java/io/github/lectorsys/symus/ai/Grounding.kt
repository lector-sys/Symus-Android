package io.github.lectorsys.symus.ai

/**
 * What kind of statement a piece of an answer is.
 *
 * The distinction is the feature. "You listened for six hours" and "you might
 * like this" are different kinds of claim, and a screen that renders them
 * identically teaches the user to trust both equally — which means trusting the
 * first one less than they should, and the second one more.
 */
enum class ClaimKind {
    /** Computed by the app from rows it wrote. Quotable. */
    RECORDED,

    /** The model's phrasing or a candidate to play. Not a measurement. */
    SUGGESTED,

    /** Asked and answered: there is not enough recorded to say. */
    UNAVAILABLE
}

/**
 * An answer after the app has checked it, rather than as the model produced it.
 *
 * [ungroundedFigures] is the important field. A small model asked to repeat
 * "412 plays" will sometimes write "over 400" or, worse, "512". Any number in
 * the prose that no tool produced is listed here, the UI marks the answer, and
 * [facts] — which came from the tools, not the model — is shown alongside so
 * the real figures are on screen regardless of what the model said.
 */
data class GroundedAnswer(
    val text: String,
    val kind: ClaimKind,
    val facts: List<String>,
    val rows: List<ToolRow>,
    val sources: List<String>,
    val ungroundedFigures: List<String> = emptyList(),
    val toolsUsed: List<String> = emptyList(),
    /**
     * Whether [text] actually repeats at least one figure the tools produced.
     *
     * False means the prose said nothing quotable — usually because the model
     * wrapped its numbers in row markers and the cleanup removed them. Such a
     * sentence is not wrong, it is empty, and the screen leaves it out rather
     * than printing "you have listened for hours in the last days" beside the
     * real figures.
     */
    val statesFigures: Boolean = true
) {
    val trustworthy: Boolean get() = ungroundedFigures.isEmpty()
}

/**
 * Turns raw model output into something the app is willing to show.
 *
 * Three passes, in order: put the real names back, check the numbers, decide
 * what kind of claim it is. None of them can be skipped — the substitution is
 * what makes the answer readable, the number check is what makes it honest, and
 * the classification is what stops a guess being read as a measurement.
 */
object Grounding {

    private val PLACEHOLDER = Regex("<<(\\d{1,2})>>")

    /**
     * Anything else wearing the placeholder's clothes.
     *
     * The model does not only emit the markers it was given. Asked for a figure
     * it wrote `<<180>>`, having learned the shape from the rows — and a
     * three-digit index matched no row, so it survived substitution and reached
     * the screen intact. Whatever is left once the real markers are resolved is
     * model invention, and it is removed rather than shown.
     */
    private val ANY_MARKER = Regex("<<[^>]{0,40}>>")

    /** Numbers as written: 412, 1,024, 3.5, but not the digits inside a word. */
    private val NUMBER = Regex("(?<![\\w.])\\d[\\d,]*(?:\\.\\d+)?(?![\\w])")

    /**
     * A number with a unit attached to it, which is always a claim.
     *
     * The magnitude exemption below exists for ordinals and list positions, and
     * it used to apply to every small number. That let "7 hours" through — a
     * figure the tools never produced, in a sentence about how long someone had
     * listened, under the threshold and therefore unchecked. A number carrying
     * a unit is never an ordinal, so it is checked whatever its size.
     */
    private val QUANTIFIED = Regex(
        "(?<![\\w.])(\\d[\\d,]*(?:\\.\\d+)?)\\s*" +
            "(hours?|hrs?|minutes?|mins?|seconds?|secs?|days?|weeks?|months?|years?|" +
            "plays?|tracks?|songs?|artists?|albums?|playlists?|genres?|times?)\\b",
        RegexOption.IGNORE_CASE
    )

    fun check(
        raw: String,
        results: List<ToolResult>
    ): GroundedAnswer {
        val rows = results.flatMap { it.rows }
        // The recorded answer, written by the app.
        //
        // Read as a result card rather than a field dump, and carrying the
        // period each figure was computed over — a duration without its window
        // is not a fact anyone can check. Every string here comes from the
        // query result; none of it passes through the model.
        val facts = results.flatMap { result ->
            val period = result.window?.let { " ($it)" } ?: ""
            result.figures.map { (key, value) -> "${factLabel(key)}: $value$period" }
        }

        val substituted = alignPeriod(substitute(raw, rows), results)
        val grounded = groundedNumbers(results)
        val quantified = QUANTIFIED.findAll(substituted)
            .map { it.groupValues[1].replace(",", "") }
            .toSet()
        val ungrounded = NUMBER.findAll(substituted)
            .map { it.value.replace(",", "") }
            .filter { candidate ->
                // Small integers are ordinals and list positions far more often
                // than they are claims — "1." at the head of a line is not a
                // figure anyone could be misled by. A number wearing a unit is
                // not one of those, and is checked regardless of size.
                val isClaim =
                    candidate.toDoubleOrNull()?.let { it >= 10 } == true ||
                        candidate in quantified
                isClaim && candidate !in grounded
            }
            .distinct()
            .toList()

        val kind = when {
            results.isNotEmpty() && results.none { it.sufficient } -> ClaimKind.UNAVAILABLE
            // A tool that says it is suggesting wins over one that merely
            // returned a count. "Ten tracks you could play" is not a record.
            results.any { it.suggestion } -> ClaimKind.SUGGESTED
            results.any { it.sufficient && it.figures.isNotEmpty() } -> ClaimKind.RECORDED
            else -> ClaimKind.SUGGESTED
        }

        return GroundedAnswer(
            text = substituted,
            kind = kind,
            facts = facts,
            rows = rows,
            sources = results.map { result ->
                buildString {
                    append(sourceLabel(result.tool))
                    result.window?.let { append(", ").append(it) }
                }
            }.distinct(),
            ungroundedFigures = ungrounded,
            toolsUsed = results.map { it.tool }.distinct(),
            statesFigures = quotesAnyFigure(substituted, results)
        )
    }

    /**
     * A period phrase in the model's own words, which is the part it gets wrong.
     *
     * Deliberately only the shapes a model actually writes. Anything broader
     * starts rewriting sentences it was not asked to touch.
     */
    private val PERIOD_PHRASE = Regex(
        """\b(?:in|over|during|within|for)?\s*""" +
            """(?:the\s+)?(?:last|past|previous)?\s*""" +
            """(?:\d+\s+(?:days?|weeks?|months?|years?)|""" +
            """this\s+(?:week|month|year)|all[- ]?time|the\s+(?:week|month|year))\b""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Makes the period in the sentence the period the figures came from.
     *
     * The model reliably produces the right numbers under the wrong heading —
     * asked about all time it returned the all-time totals and then wrote "in
     * the last 7 days" after them. Every part of that sentence is true except
     * the part that says what it is about, which makes the whole thing false.
     *
     * The window is the app's own string, exactly like the row labels
     * [substitute] puts back, so it is written in rather than checked. Only
     * applied when every tool used the same window — with two windows in play
     * there is no single right answer and the grounding check is left to flag
     * whatever the model said.
     */
    internal fun alignPeriod(text: String, results: List<ToolResult>): String {
        val windows = results.mapNotNull { it.window }.distinct()
        val window = windows.singleOrNull() ?: return text
        var replaced = false
        return PERIOD_PHRASE.replace(text) { match ->
            // One correction per sentence; a second match is usually part of a
            // quoted figure like "3 months" and is left alone.
            if (replaced) return@replace match.value
            replaced = true
            val head = match.value.takeWhile { it.isWhitespace() }
            if (match.value.trim().equals(window, ignoreCase = true)) match.value
            else "${head}over $window"
        }
    }

    /**
     * Puts provider-derived names back where the placeholders were.
     *
     * An unmatched placeholder is dropped rather than shown: `<<7>>` on screen
     * is worse than a slightly shorter sentence, and it happens whenever the
     * model invents a reference to a row it was never given.
     */
    fun substitute(text: String, rows: List<ToolRow>): String {
        val byIndex = rows.withIndex().associate { (index, row) -> (index + 1).toString() to row.label }
        return PLACEHOLDER.replace(text) { match ->
            byIndex[match.groupValues[1]] ?: ""
        }
            .replace(ANY_MARKER, "")
            .replace(Regex(" {2,}"), " ")
            .replace(Regex(" +([.,])"), "$1")
            .trim()
    }

    /**
     * Whether [text] actually states at least one figure the tools produced.
     *
     * The failure this catches looks like success. Asked for a listening total
     * the model wrote `<<1>> hours in the last <<30>> days`, having learned the
     * marker shape from the row references; [substitute] stripped the markers it
     * could not resolve and left "You have listened for hours in the last days."
     * That sentence contains no number at all, so the ungrounded-figure check
     * passes it — it reads as an answer while saying nothing.
     *
     * Vacuously true when no tool produced a figure: an answer about how a
     * feature works has none to quote and is not expected to.
     */
    fun quotesAnyFigure(text: String, results: List<ToolResult>): Boolean {
        val values = results.flatMap { it.figures.values } +
            results.flatMap { result -> result.rows.map { it.detail } }
        val numeric = values.filter { NUMBER.containsMatchIn(it) }
        if (numeric.isEmpty()) return true
        return numeric.any { value ->
            NUMBER.findAll(value).map { it.value }.toList()
                .let { digits -> digits.isNotEmpty() && digits.all { text.contains(it) } }
        }
    }

    /**
     * Every number any tool actually produced, as bare digits.
     *
     * The window label counts. "the last 30 days" is the app's own words for
     * the period it just queried, so a model that repeats it back is quoting a
     * fact rather than inventing one — and flagging that 30 made honest answers
     * about a 30-day window look untrustworthy.
     */
    private fun groundedNumbers(results: List<ToolResult>): Set<String> =
        results.flatMap { result -> result.numericFacts() + listOfNotNull(result.window) }
            .flatMap { fact -> NUMBER.findAll(fact).map { it.value.replace(",", "") }.toList() }
            .toSet()

    /** A tool's figure key, in the words the screen should show. */
    private fun factLabel(key: String) = when (key) {
        "listening_time" -> "Time listened"
        "plays" -> "Plays"
        "unmeasured_plays" -> "Plays predating measurement"
        "artists_found" -> "Artists"
        "tracks_found" -> "Tracks"
        "genres_found" -> "Genres"
        "playlists" -> "Playlists"
        else -> key.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    private fun sourceLabel(tool: String) = when (tool) {
        "listening_time" -> "your play history"
        "top_artists" -> "top artists, from your play history"
        "top_tracks" -> "top tracks, from your play history"
        "top_genres" -> "genre tags on tracks you played"
        "library_counts" -> "your library totals"
        "playlists" -> "your playlists"
        "forgotten_favourites" -> "liked tracks and their last play"
        "suggest_tracks" -> "candidates from your library"
        "app_feature" -> "this app's documentation"
        else -> tool
    }
}
