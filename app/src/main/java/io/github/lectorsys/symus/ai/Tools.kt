package io.github.lectorsys.symus.ai

import io.github.lectorsys.symus.data.LibraryRepository
import io.github.lectorsys.symus.data.TrackEntity
import io.github.lectorsys.symus.library.Listening
import io.github.lectorsys.symus.library.Window
import io.github.lectorsys.symus.library.formatListening
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One row of a tool result, split by who wrote the words.
 *
 * [label] is provider-derived: a track title or an artist name that arrived
 * from YouTube through the extractor. It never reaches the model. [ref] is what
 * the model sees in its place, and the app swaps the real label back in when the
 * answer is rendered.
 *
 * This is not an abstract privacy gesture. YouTube's terms do not permit its
 * data being fed to machine-learning systems, and "the model only reads it, it
 * does not train on it" is an argument rather than a compliance position. The
 * placeholder costs one substitution pass and removes the question.
 */
data class ToolRow(
    val ref: String,
    val label: String,
    val detail: String,
    val videoId: String? = null,
    /**
     * Provider-derived, and never in [ToolResult.forModel]. It reaches the
     * screen without passing through the model, same as [label].
     */
    val artworkUrl: String? = null
)

/**
 * What a tool returned: figures the app computed, and rows the app will render.
 *
 * [figures] is the whole factual surface the model is allowed to quote. If a
 * number is not in here, no tool produced it, and an answer containing it is
 * not grounded — which is exactly the check [Grounding] performs.
 */
data class ToolResult(
    val tool: String,
    val window: String? = null,
    val figures: Map<String, String> = emptyMap(),
    val rows: List<ToolRow> = emptyList(),
    val note: String? = null,
    val sufficient: Boolean = true,
    /**
     * True where the rows are candidates rather than records.
     *
     * Declared by the tool rather than inferred from whether figures came back.
     * "Ten tracks you could play" has a count in it and is still not a thing
     * the user did, and inferring from the shape got that exactly backwards.
     */
    val suggestion: Boolean = false
) {
    /** The model's view: placeholders only, never a provider-written string. */
    fun forModel(): String = buildString {
        append("TOOL ").append(tool)
        window?.let { append(" (").append(it).append(")") }
        appendLine(":")
        if (!sufficient) appendLine("  NO DATA: nothing recorded for this.")
        figures.forEach { (key, value) -> appendLine("  $key = $value") }
        rows.forEach { row -> appendLine("  ${row.ref} ${row.detail}") }
        note?.let { appendLine("  note: $it") }
    }.trim()

    /** Every value a grounded answer may contain a number from. */
    fun numericFacts(): List<String> = figures.values + rows.map { it.detail }
}

/** A tool the model may call, and the exact shape of its arguments. */
data class ToolSpec(
    val name: String,
    val description: String,
    val arguments: String
)

/**
 * The tools the model can reach, and nothing else.
 *
 * **Why there is no SQL tool.** A model that can write a query can write
 * `DELETE`, can read tables the feature has no business in, and can produce a
 * statement that is syntactically fine and semantically wrong in a way no one
 * notices. Every tool here is a fixed query with bounded parameters — a window
 * chosen from a list, a limit clamped to a range — so the worst a confused model
 * can do is ask the wrong correct question.
 *
 * **Why nothing here writes.** These answer questions. Playing, saving and
 * deleting stay on the buttons the user already knows, where the consequences
 * are visible before they happen.
 */
@Singleton
class Tools @Inject constructor(
    private val library: LibraryRepository,
    private val listening: Listening
) {

    val specs = listOf(
        ToolSpec(
            "listening_time",
            "How long the user listened, how many plays, in one period.",
            """{"window":"week|month|year|all"}"""
        ),
        ToolSpec(
            "top_artists",
            "The user's most-played artists in one period.",
            """{"window":"week|month|year|all","limit":1-10}"""
        ),
        ToolSpec(
            "top_tracks",
            "The user's most-played tracks in one period.",
            """{"window":"week|month|year|all","limit":1-10}"""
        ),
        ToolSpec(
            "top_genres",
            "Which genres the user played most in one period.",
            """{"window":"week|month|year|all","limit":1-10}"""
        ),
        ToolSpec(
            "library_counts",
            "Totals: tracks, playlists, liked, saved, downloaded.",
            """{}"""
        ),
        ToolSpec(
            "playlists",
            "The user's playlists and how many tracks each holds.",
            """{"limit":1-20}"""
        ),
        ToolSpec(
            "forgotten_favourites",
            "Tracks the user liked but has not played recently.",
            """{"limit":1-10}"""
        ),
        ToolSpec(
            "suggest_tracks",
            "Candidates to play now, drawn from the user's own library.",
            """{"limit":1-10}"""
        ),
        ToolSpec(
            "app_feature",
            "How a feature of this app works.",
            """{"topic":"downloads|backup|import|widgets|themes|privacy|lyrics|voice|stats"}"""
        )
    )

    /**
     * Runs [name] with [args], or returns a result saying why it could not.
     *
     * Unknown tools and malformed arguments are answers, not exceptions: a small
     * model will hallucinate a tool name eventually, and the loop needs
     * something to feed back rather than a crash.
     */
    suspend fun run(name: String, args: JSONObject, now: Long = System.currentTimeMillis()): ToolResult {
        val window = windowOf(args.optString("window"), now)
        val limit = args.optInt("limit", 5).coerceIn(1, 10)

        return when (name) {
            "listening_time" -> {
                val summary = listening.summary(window, limit = 1)
                ToolResult(
                    tool = name,
                    window = window.label,
                    sufficient = !summary.isEmpty,
                    figures = mapOf(
                        "listening_time" to formatListening(summary.listenedMs),
                        "plays" to summary.plays.toString(),
                        "unmeasured_plays" to summary.unmeasuredPlays.toString()
                    ),
                    note = if (summary.measurementIncomplete) {
                        "${summary.unmeasuredPlays} plays predate duration measurement, " +
                            "so the time is a floor, not a total."
                    } else null
                )
            }

            "top_artists" -> {
                val summary = listening.summary(window, limit)
                ToolResult(
                    tool = name,
                    window = window.label,
                    sufficient = summary.topArtists.isNotEmpty(),
                    figures = mapOf("artists_found" to summary.topArtists.size.toString()),
                    rows = summary.topArtists.mapIndexed { index, row ->
                        ToolRow(
                            ref = ref(index),
                            label = row.artist,
                            detail = "${row.plays} plays, ${formatListening(row.listenedMs)}"
                        )
                    }
                )
            }

            "top_tracks" -> {
                val summary = listening.summary(window, limit)
                ToolResult(
                    tool = name,
                    window = window.label,
                    sufficient = summary.topTracks.isNotEmpty(),
                    figures = mapOf("tracks_found" to summary.topTracks.size.toString()),
                    rows = summary.topTracks.mapIndexed { index, row ->
                        ToolRow(
                            ref = ref(index),
                            label = listOfNotNull(row.title, row.artist).joinToString(" — "),
                            detail = "${row.plays} plays",
                            videoId = row.videoId
                        )
                    }
                )
            }

            "top_genres" -> {
                val summary = listening.summary(window, limit)
                ToolResult(
                    tool = name,
                    window = window.label,
                    sufficient = summary.topGenres.isNotEmpty(),
                    figures = mapOf("genres_found" to summary.topGenres.size.toString()),
                    // Genres are the app's own tags, not YouTube's words, so they
                    // are safe to name directly and the model can reason about them.
                    rows = summary.topGenres.mapIndexed { index, row ->
                        ToolRow(
                            ref = row.genre,
                            label = row.genre,
                            detail = "${row.count} plays"
                        )
                    }
                )
            }

            "library_counts" -> {
                val counts = library.counts()
                ToolResult(
                    tool = name,
                    figures = counts.mapValues { it.value.toString() },
                    sufficient = counts.values.any { it > 0 }
                )
            }

            "playlists" -> {
                val all = library.playlistsNow().take(limit.coerceAtMost(20))
                ToolResult(
                    tool = name,
                    sufficient = all.isNotEmpty(),
                    figures = mapOf("playlists" to all.size.toString()),
                    // Playlist names are the user's own words. Nothing to redact.
                    rows = all.mapIndexed { _, row ->
                        ToolRow(ref = row.title, label = row.title, detail = "${row.count} tracks")
                    }
                )
            }

            "forgotten_favourites" -> {
                val tracks = library.likedNotPlayedRecently(limit)
                ToolResult(
                    tool = name,
                    sufficient = tracks.isNotEmpty(),
                    figures = mapOf("found" to tracks.size.toString()),
                    rows = tracks.mapIndexed { index, track -> trackRow(index, track, "liked") }
                )
            }

            "suggest_tracks" -> {
                val tracks = library.recommended(limit)
                ToolResult(
                    tool = name,
                    sufficient = tracks.isNotEmpty(),
                    figures = mapOf("found" to tracks.size.toString()),
                    rows = tracks.mapIndexed { index, track -> trackRow(index, track, "in library") },
                    note = "These are candidates, not a recorded preference.",
                    suggestion = true
                )
            }

            "app_feature" -> {
                val topic = args.optString("topic").lowercase()
                val text = FEATURES[topic]
                ToolResult(
                    tool = name,
                    sufficient = text != null,
                    figures = if (text != null) mapOf("answer" to text) else emptyMap(),
                    note = if (text == null) {
                        "No such topic. Known: ${FEATURES.keys.joinToString()}."
                    } else null
                )
            }

            else -> ToolResult(
                tool = name,
                sufficient = false,
                note = "No such tool. Available: ${specs.joinToString { it.name }}."
            )
        }
    }

    private fun trackRow(index: Int, track: TrackEntity, detail: String) = ToolRow(
        ref = ref(index),
        label = listOfNotNull(track.title, track.artist).joinToString(" — "),
        detail = listOfNotNull(detail, track.genre).joinToString(", "),
        videoId = track.videoId,
        artworkUrl = track.thumbnailUrl
    )

    private fun ref(index: Int) = "<<${index + 1}>>"

    private fun windowOf(raw: String?, now: Long): Window = when (raw?.lowercase()) {
        "week", "this week", "last week", "7", "7d" -> Window.last7Days(now)
        "year", "this year", "12m" -> Window.last365Days(now)
        "all", "all time", "ever", "total" -> Window.allTime(now)
        else -> Window.last30Days(now)
    }

    private companion object {
        /**
         * Answers about the app itself, written here rather than left to the
         * model. A 0.5B model asked "does SYmuS upload my history" will invent
         * something reassuring, and that is precisely the sentence that must not
         * be invented.
         */
        val FEATURES = mapOf(
            "downloads" to "Downloads keep audio on the device for offline play. " +
                "Settings can restrict them to wi-fi, and the Downloads screen removes them.",
            "backup" to "Backup writes one JSON file holding the library, playlists, " +
                "likes, history and settings. Downloaded audio and artwork are not in it. " +
                "Restoring merges rather than replacing.",
            "import" to "Playlists import from a YouTube link or an exported file " +
                "(CSV, M3U or M3U8). Each track is matched against YouTube and shown for " +
                "review before anything is added.",
            "widgets" to "There are two home-screen widgets: now playing, and a playlist. " +
                "Both are configurable from the launcher and from Settings.",
            "themes" to "Themes can be built from a photo: the accent colour is taken " +
                "from the image and the photo becomes the app background, with adjustable dim and blur.",
            "privacy" to "Your library, history and playlists are stored only on this " +
                "device and no account is used. The app does reach the network: YouTube for " +
                "search and playback, LRCLIB for lyrics, and an import provider if you " +
                "configure one. The assistant itself makes no network request at all.",
            "lyrics" to "Lyrics come from LRCLIB and are cached on the device. " +
                "Synced lyrics scroll with playback where they exist.",
            "voice" to "Voice search uses the device's own speech recogniser. " +
                "The microphone permission is asked for when the button is pressed, not at launch.",
            "stats" to "Listening statistics count real listened time, measured while " +
                "audio was playing, so a track skipped after a few seconds does not count as a play."
        )
    }
}
