package io.github.lectorsys.symus.library

import io.github.lectorsys.symus.data.MatchState
import io.github.lectorsys.symus.media.SearchHit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** A candidate for one source track, with the confidence that put it there. */
data class Match(
    val videoId: String,
    val confidence: Double,
    val state: MatchState
)

/**
 * Deciding whether a YouTube result is the track a source playlist meant.
 *
 * The hard part is not finding something plausible — a search for any song
 * returns twenty plausible things. It is refusing the near misses: the live
 * version, the sped-up edit, the ten-hour loop, the cover by someone else, the
 * "full album" upload that happens to mention the title. Each of those is a
 * track the user did not ask for, sitting in their playlist under the right
 * name, and it is worse than an unresolved row because nothing flags it.
 *
 * So the scoring is deliberately suspicious, the duration is weighted heavily
 * where it is known, and anything that does not clear [LIKELY_AT] comes back
 * as UNCERTAIN for a human to look at rather than being quietly accepted.
 */
object Matching {

    /** Above this, accept without asking. */
    const val LIKELY_AT = 0.80

    /** Below this, do not offer the candidate at all. */
    const val FLOOR = 0.42

    /**
     * Words that mean "this is a different recording".
     *
     * Only counted when the *source* did not ask for them: a playlist entry
     * that says "(Live)" should match a live upload.
     */
    private val VERSION_WORDS = listOf(
        "live", "remix", "cover", "karaoke", "instrumental", "acoustic",
        "sped up", "slowed", "nightcore", "8d", "reverb", "mashup",
        "tribute", "loop", "extended", "full album", "mix", "medley"
    )

    /** The search terms to look a source track up with. */
    fun queryFor(track: SourceTrack): String =
        listOfNotNull(track.artist, track.title).joinToString(" ").trim()

    /**
     * Picks the best candidate, or nothing.
     *
     * [isrc] is accepted as an exact match without scoring when the candidate
     * carries one, because a shared ISRC means the same recording by
     * definition — but YouTube results do not carry ISRCs, so in practice this
     * only fires for sources that supplied one and matched a track already in
     * the library.
     */
    fun best(source: SourceTrack, candidates: List<SearchHit>): Match? {
        if (candidates.isEmpty()) return null

        val scored = candidates
            .map { it to score(source, it) }
            .sortedByDescending { it.second }

        val (hit, confidence) = scored.first()
        if (confidence < FLOOR) return null

        return Match(
            videoId = hit.track.videoId,
            confidence = confidence,
            state = if (confidence >= LIKELY_AT) MatchState.LIKELY else MatchState.UNCERTAIN
        )
    }

    /**
     * How well one candidate answers one source track, 0..1.
     *
     * Title similarity is the base; artist agreement and duration agreement
     * move it; an unasked-for version word pulls it down hard.
     */
    fun score(source: SourceTrack, hit: SearchHit): Double {
        val sourceTitle = normalise(source.title)
        val hitTitle = normalise(hit.track.title)
        if (sourceTitle.isEmpty() || hitTitle.isEmpty()) return 0.0

        var score = similarity(sourceTitle, hitTitle) * 0.55

        val sourceArtist = source.artist?.let(::normalise).orEmpty()
        val hitArtist = hit.track.artist?.let(::normalise).orEmpty()
        score += when {
            sourceArtist.isEmpty() || hitArtist.isEmpty() -> 0.10
            hitArtist.contains(sourceArtist) || sourceArtist.contains(hitArtist) -> 0.25
            else -> similarity(sourceArtist, hitArtist) * 0.25
        }

        // Duration is the strongest single signal that two uploads are the same
        // recording, and the one that rules out the ten-hour loop and the
        // "full album" upload outright.
        val sourceSeconds = source.durationSec
        val hitSeconds = hit.track.duration
        score += when {
            sourceSeconds <= 0 || hitSeconds <= 0 -> 0.05
            abs(sourceSeconds - hitSeconds) <= 3 -> 0.20
            abs(sourceSeconds - hitSeconds) <= 10 -> 0.14
            abs(sourceSeconds - hitSeconds) <= 30 -> 0.05
            // More than double or less than half: not the same recording,
            // whatever the title says.
            hitSeconds > sourceSeconds * 2 || hitSeconds < sourceSeconds / 2 -> -0.45
            else -> -0.15
        }

        val askedFor = VERSION_WORDS.filter { sourceTitle.contains(it) }.toSet()
        val offered = VERSION_WORDS.filter { hitTitle.contains(it) }.toSet()
        val unwanted = offered - askedFor
        if (unwanted.isNotEmpty()) score -= 0.30
        // And the reverse: the source wanted a live version and this is not one.
        val missing = askedFor - offered
        if (missing.isNotEmpty()) score -= 0.15

        return score.coerceIn(0.0, 1.0)
    }

    /**
     * Comparable form of a title.
     *
     * Bracketed noise is dropped — "(Official Video)", "[HD]", "(Lyrics)" —
     * because it is on the YouTube side of almost every upload and on the
     * source side of almost none, and leaving it in makes every score worse by
     * the same amount, which is just noise with extra steps.
     */
    internal fun normalise(text: String): String =
        text.lowercase()
            .replace(Regex("""\((?:official|lyrics?|audio|video|hd|hq|mv|m/v)[^)]*\)"""), " ")
            .replace(Regex("""\[[^]]*]"""), " ")
            .replace(Regex("""\bfeat\.?\b|\bft\.?\b"""), " ")
            .replace(Regex("""[^a-z0-9 ]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    /**
     * Token overlap, weighted by how much of each side is covered.
     *
     * Not an edit distance: "Get Lucky" against "Get Lucky (feat. Pharrell
     * Williams and Nile Rodgers)" is a long way apart character by character
     * and obviously the same song, which is exactly the case that matters.
     */
    internal fun similarity(a: String, b: String): Double {
        val left = a.split(' ').filter { it.isNotBlank() }.toSet()
        val right = b.split(' ').filter { it.isNotBlank() }.toSet()
        if (left.isEmpty() || right.isEmpty()) return 0.0
        val shared = left.intersect(right).size.toDouble()
        // Harmonic-ish: rewards covering the shorter side without letting a
        // very long title score well just by containing everything.
        val coverage = shared / min(left.size, right.size)
        val balance = shared / max(left.size, right.size)
        return coverage * 0.7 + balance * 0.3
    }
}
