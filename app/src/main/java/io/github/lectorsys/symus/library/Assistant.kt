package io.github.lectorsys.symus.library

import io.github.lectorsys.symus.data.LibraryRepository
import io.github.lectorsys.symus.data.TrackEntity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One answer, and where every part of it came from.
 *
 * [sources] is not decoration: it names the tables and queries the answer was
 * computed from, and the screen shows it. An assistant that talks about your
 * listening owes you the ability to check it, and "top artists, from your play
 * history over the last 30 days" is checkable in a way that a confident
 * paragraph is not.
 *
 * [tracks] lets an answer be acted on rather than only read.
 */
data class Answer(
    val text: String,
    val sources: List<String> = emptyList(),
    val tracks: List<TrackEntity> = emptyList(),
    /** Set when the question was understood but the data cannot support it. */
    val insufficientData: Boolean = false
)

/** A question the assistant knows how to answer, offered as a starting point. */
data class Suggestion(val label: String, val question: String)

/**
 * Answers questions about the user's own listening, entirely on this device.
 *
 * **What this is.** A query engine over the local database with a small
 * intent parser in front of it. Every sentence it produces is assembled from
 * figures it just read out of SQLite; there is no language model involved, on
 * the device or off it.
 *
 * **Why it is built this way.** The requirement that matters most for
 * something that talks about your listening history is that it must not invent
 * any of it. A generative model asked "what did I listen to in March" will
 * produce a fluent, plausible, and frequently wrong answer, and the user has no
 * way to tell the difference. Counting rows cannot be fluent, but it also
 * cannot be wrong — and when there is not enough history to answer, this says
 * so ([Answer.insufficientData]) rather than filling the gap.
 *
 * **What it can see.** The history table, the tracks table, playlists, likes
 * and downloads — all local, all written by this app. Nothing leaves the
 * device, and there is no network call anywhere in this file.
 */
@Singleton
class Assistant @Inject constructor(
    private val library: LibraryRepository,
    private val listening: Listening
) {

    /** The starting points offered on an empty screen. */
    val suggestions = listOf(
        Suggestion("Top artists", "Who have I listened to most this month?"),
        Suggestion("Listening time", "How much have I listened to this week?"),
        Suggestion("Top songs", "What are my most played songs?"),
        Suggestion("My genres", "What genres do I listen to?"),
        Suggestion("Forgotten favourites", "What have I liked but not played in a while?"),
        Suggestion("Something to play", "What should I play now?")
    )

    /**
     * Answers [question], or says plainly that it cannot.
     *
     * [now] is a parameter so the windows are testable against a fixed instant
     * rather than whatever the clock said when the test ran.
     */
    suspend fun ask(question: String, now: Long = System.currentTimeMillis()): Answer {
        val text = question.lowercase().trim()
        if (text.isBlank()) return unknown()

        val window = windowFor(text, now)

        return when {
            matches(text, "how much", "how long", "listening time", "hours", "time have i") ->
                listeningTime(window)

            matches(text, "artist", "who do i", "who have i", "who am i") ->
                topArtists(window)

            matches(text, "genre", "kind of music", "style") ->
                topGenres(window)

            matches(text, "most played", "top song", "top track", "favourite song", "favorite song") ->
                topTracks(window)

            matches(text, "forgotten", "not played", "haven't played", "havent played", "neglected") ->
                forgotten()

            matches(text, "rediscover", "used to play", "used to listen") ->
                rediscover()

            matches(text, "offline", "download", "saved for offline") ->
                offline()

            matches(text, "what should i play", "recommend", "suggest", "something to play", "play something") ->
                recommend()

            matches(text, "how many", "play count", "plays") ->
                playCount(window)

            else -> artistQuestion(text, window) ?: unknown()
        }
    }

    // -- answers -------------------------------------------------------------

    private suspend fun listeningTime(window: Window): Answer {
        val summary = listening.summary(window)
        if (summary.isEmpty) {
            return Answer(
                "Nothing is recorded for ${window.label}. Play something and it will " +
                    "show up here.",
                sources = listOf("play history, ${window.label}"),
                insufficientData = true
            )
        }

        val base = "You listened for ${formatListening(summary.listenedMs)} across " +
            "${summary.plays} ${if (summary.plays == 1) "play" else "plays"} in ${window.label}."

        // The caveat is part of the answer, not a disclaimer bolted on: an
        // unmeasured play contributes zero to the total, and saying the total
        // without saying that would be stating a floor as if it were a figure.
        val caveat = if (summary.unmeasuredPlays > 0) {
            " ${summary.unmeasuredPlays} of those ${
                if (summary.unmeasuredPlays == 1) "play has" else "plays have"
            } no measured time, so the real total is higher."
        } else {
            ""
        }

        return Answer(base + caveat, sources = listOf("play history, ${window.label}"))
    }

    private suspend fun topArtists(window: Window): Answer {
        val summary = listening.summary(window, limit = 5)
        if (summary.topArtists.isEmpty()) {
            return Answer(
                "No artist has enough recorded plays in ${window.label} to rank.",
                sources = listOf("play history joined to tracks, ${window.label}"),
                insufficientData = true
            )
        }
        val lines = summary.topArtists.mapIndexed { index, row ->
            "${index + 1}. ${row.artist} — ${row.plays} " +
                "${if (row.plays == 1) "play" else "plays"}" +
                if (row.listenedMs > 0) ", ${formatListening(row.listenedMs)}" else ""
        }
        return Answer(
            "Your most played artists in ${window.label}:\n" + lines.joinToString("\n"),
            sources = listOf("play history joined to tracks, ${window.label}")
        )
    }

    private suspend fun topTracks(window: Window): Answer {
        val summary = listening.summary(window, limit = 5)
        if (summary.topTracks.isEmpty()) {
            return Answer(
                "Nothing has been played in ${window.label} yet.",
                sources = listOf("play history, ${window.label}"),
                insufficientData = true
            )
        }
        val lines = summary.topTracks.mapIndexed { index, row ->
            "${index + 1}. ${row.title}${row.artist?.let { " — $it" } ?: ""} " +
                "(${row.plays} ${if (row.plays == 1) "play" else "plays"})"
        }
        return Answer(
            "Most played in ${window.label}:\n" + lines.joinToString("\n"),
            sources = listOf("play history, ${window.label}"),
            tracks = summary.topTracks.map { it.track() }
        )
    }

    private suspend fun topGenres(window: Window): Answer {
        val summary = listening.summary(window, limit = 6)
        if (summary.topGenres.isEmpty()) {
            return Answer(
                "None of what you have played in ${window.label} carries a genre yet. " +
                    "Genres are guessed when a track is added and can be edited by hand.",
                sources = listOf("play history joined to tracks, ${window.label}"),
                insufficientData = true
            )
        }
        val total = summary.topGenres.sumOf { it.count }
        val lines = summary.topGenres.map { row ->
            val share = if (total == 0) 0 else row.count * 100 / total
            "${row.genre} — $share%"
        }
        return Answer(
            "What you played in ${window.label}, by genre:\n" + lines.joinToString("\n"),
            sources = listOf("play history joined to tracks, ${window.label}")
        )
    }

    private suspend fun forgotten(): Answer {
        val tracks = library.likedNotPlayedRecently(limit = 10)
        if (tracks.isEmpty()) {
            return Answer(
                "Nothing you have liked has gone unplayed long enough to count as " +
                    "forgotten.",
                sources = listOf("likes, play history"),
                insufficientData = true
            )
        }
        return Answer(
            "Liked, but not played for a while:",
            sources = listOf("likes, play history"),
            tracks = tracks
        )
    }

    private suspend fun rediscover(): Answer {
        val tracks = library.rediscover(limit = 10)
        if (tracks.isEmpty()) {
            return Answer(
                "Nothing has fallen far enough out of rotation to rediscover yet.",
                sources = listOf("play history"),
                insufficientData = true
            )
        }
        return Answer(
            "You used to play these and have not lately:",
            sources = listOf("play history"),
            tracks = tracks
        )
    }

    private suspend fun offline(): Answer {
        val tracks = library.cachedNow()
        if (tracks.isEmpty()) {
            return Answer(
                "Nothing is downloaded. Anything you download shows up under " +
                    "Library, and plays without a connection.",
                sources = listOf("downloads"),
                insufficientData = true
            )
        }
        return Answer(
            "${tracks.size} ${if (tracks.size == 1) "track is" else "tracks are"} " +
                "downloaded and will play offline:",
            sources = listOf("downloads"),
            tracks = tracks
        )
    }

    private suspend fun recommend(): Answer {
        val pool = library.recommendPool(40)
        if (pool.isEmpty()) {
            return Answer(
                "There is not enough in your library to suggest anything yet. " +
                    "Search for something and play it, and this gets useful quickly.",
                sources = listOf("your library"),
                insufficientData = true
            )
        }
        val seed = pool.first()
        val rest = library.similarTo(seed, setOf(seed.videoId), limit = 9)
        return Answer(
            "Based on what you have played and liked — no two people get the " +
                "same list, and this one is computed here, not fetched:",
            sources = listOf("your library, likes, play history"),
            tracks = listOf(seed) + rest
        )
    }

    private suspend fun playCount(window: Window): Answer {
        val summary = listening.summary(window)
        return Answer(
            "${summary.plays} ${if (summary.plays == 1) "play" else "plays"} in " +
                "${window.label}, across ${summary.topArtists.size} " +
                "${if (summary.topArtists.size == 1) "artist" else "artists"} that " +
                "made the top list.",
            sources = listOf("play history, ${window.label}"),
            insufficientData = summary.isEmpty
        )
    }

    /**
     * "How much have I listened to Daft Punk?"
     *
     * Matched against artists that are actually in the library rather than
     * parsed out of the sentence, so a misspelling fails honestly instead of
     * producing a confident answer about an artist nobody has.
     */
    private suspend fun artistQuestion(text: String, window: Window): Answer? {
        val artists = library.knownArtists()
        val match = artists.firstOrNull { text.contains(it.lowercase()) } ?: return null
        val summary = listening.summary(window, limit = 50)
        val row = summary.topArtists.firstOrNull { it.artist.equals(match, ignoreCase = true) }
            ?: return Answer(
                "$match is in your library, but has no recorded plays in ${window.label}.",
                sources = listOf("play history joined to tracks, ${window.label}"),
                insufficientData = true
            )
        return Answer(
            "$match: ${row.plays} ${if (row.plays == 1) "play" else "plays"} in " +
                "${window.label}" +
                if (row.listenedMs > 0) ", ${formatListening(row.listenedMs)} of listening." else ".",
            sources = listOf("play history joined to tracks, ${window.label}"),
            tracks = library.byArtist(match, limit = 10)
        )
    }

    /**
     * The honest failure.
     *
     * Lists what it can actually do rather than apologising vaguely, because
     * the useful information at this moment is the shape of the thing, not
     * that it did not understand.
     */
    private fun unknown() = Answer(
        "I can only answer from what this app has recorded. Try asking about " +
            "your top artists, your listening time, your genres, what you have " +
            "forgotten, or what to play next.",
        insufficientData = true
    )

    // -- parsing -------------------------------------------------------------

    private fun matches(text: String, vararg phrases: String) = phrases.any { text.contains(it) }

    /**
     * The period the question is about, defaulting to everything.
     *
     * Deliberately explicit: an answer that silently picked "the last 30 days"
     * when the user said "ever" would be wrong in a way that reads as right.
     */
    internal fun windowFor(text: String, now: Long): Window = when {
        matches(text, "today") -> Window("today", now - Window.DAY, now + 1)
        matches(text, "this week", "past week", "last week", "7 days") -> Window.last7Days(now)
        matches(text, "this month", "past month", "last month", "30 days") -> Window.last30Days(now)
        matches(text, "this year", "past year", "last year") -> Window.last365Days(now)
        matches(text, "all time", "ever", "overall", "in total") -> Window.allTime(now)
        else -> Window.allTime(now)
    }
}
