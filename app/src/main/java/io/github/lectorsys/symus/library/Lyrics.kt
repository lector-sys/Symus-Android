package io.github.lectorsys.symus.library

import io.github.lectorsys.symus.data.LyricsDao
import io.github.lectorsys.symus.data.LyricsEntity
import io.github.lectorsys.symus.data.TrackDao
import io.github.lectorsys.symus.data.TrackEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/** One timed line. [timeSec] is seconds from the start of the track. */
data class LyricLine(val timeSec: Double, val text: String)

/**
 * What a lookup produced.
 *
 * "None" and "Failed" are deliberately different: the first means the source
 * genuinely has no words for this track, the second means we could not ask. Only
 * the first is worth caching, and only the second is worth a retry button.
 */
sealed interface LyricsResult {
    data class Found(val lines: List<LyricLine>?, val plain: String?) : LyricsResult
    data object None : LyricsResult
    data class Failed(val message: String) : LyricsResult
}

/**
 * Turns a YouTube title and channel into something a lyrics database recognises.
 *
 * Real titles read "The Weeknd - Blinding Lights (Official Video)" on a channel
 * called "TheWeekndVEVO". Ported from the desktop app, where this lifted LRCLIB
 * from mostly-missing to 8 of 10 real tracks found.
 */
fun cleanQuery(rawTitle: String, rawArtist: String?): Pair<String, String> {
    var title = rawTitle
        // Emoji are decoration in titles and only confuse the lookup. Two passes:
        // the pictographic property, then the variation selector separately,
        // because one character class combining them is ambiguous about whether
        // it matches a whole emoji or its individual code points.
        .replace(Regex("""\p{So}"""), " ")
        .replace("️", "")
        .replace(NOISE, " ")
        .replace(Regex("""\s+"""), " ")
        .trim()

    var artist = (rawArtist ?: "")
        .replace(Regex("""\s*-\s*Topic$""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""VEVO$""", RegexOption.IGNORE_CASE), "")
        .trim()

    // "Artist - Title" is the dominant convention; the right-hand side is the song.
    val dash = Regex("""^(.{1,60}?)\s+[-–—]\s+(.+)$""").find(title)
    if (dash != null) {
        if (artist.isEmpty()) artist = dash.groupValues[1].trim()
        title = dash.groupValues[2]
    }

    title = title
        .replace(Regex("""\(.*?\)|\[.*?]"""), " ")
        .replace(Regex("""\s*\b(ft|feat)\.?\s+.*$""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""\s+"""), " ")
        .replace(Regex("""\s*[-–—|]\s*$"""), "")
        .trim()

    return title to artist
}

/**
 * Parses LRC text into timed lines.
 *
 * Lines with no timestamp are dropped rather than guessed at — a line shown at
 * the wrong moment is worse than one not shown.
 */
fun parseLrc(lrc: String): List<LyricLine> {
    val lines = mutableListOf<LyricLine>()
    for (raw in lrc.split("\n")) {
        val match = LRC_LINE.find(raw.trimEnd('\r')) ?: continue
        val (mm, ss, frac, text) = match.destructured
        // The divisor scales with the digit count: ".5" is five tenths, ".05" is
        // five hundredths. Assuming centiseconds puts "[01:05.5]" at 65.05s.
        val fraction = if (frac.isEmpty()) 0.0 else frac.toDouble() / Math.pow(10.0, frac.length.toDouble())
        lines += LyricLine(mm.toInt() * 60 + ss.toInt() + fraction, text.trim())
    }
    return lines.sortedBy { it.timeSec }
}

private val NOISE = Regex(
    """\((?:official|lyrics?|audio|video|music|visuali[sz]er|hd|4k|explicit|clean|remaster(?:ed)?)[^)]*\)""" +
        """|\[(?:official|lyrics?|audio|video|music|hd|4k|explicit|clean)[^]]*]""" +
        """|\b(?:official\s+(?:music\s+)?video|official\s+audio|lyrics?\s+video|with\s+lyrics|audio|hd|hq|4k|mv)\b""",
    RegexOption.IGNORE_CASE
)

private val LRC_LINE = Regex("""^\s*\[(\d+):(\d{1,2})(?:[.:](\d{1,3}))?]\s?(.*)$""")

/**
 * Lyrics, from LRCLIB.
 *
 * No key and no account, which is why it is the one network source this app uses
 * besides YouTube. Results are cached on the track, including the negative ones —
 * but those expire, because LRCLIB keeps growing and "nothing found in March"
 * should not be the answer forever.
 */
@Singleton
class LyricsRepository @Inject constructor(
    private val client: OkHttpClient,
    private val lyrics: LyricsDao,
    private val tracks: TrackDao
) {

    suspend fun forTrack(track: TrackEntity): LyricsResult = withContext(Dispatchers.IO) {
        val known = lyrics.byId(track.videoId)
        if (known != null) {
            val empty = known.synced.isNullOrBlank() && known.plain.isNullOrBlank()
            if (!empty) return@withContext stored(known)
            val stale = System.currentTimeMillis() - known.fetchedAt > NEGATIVE_TTL_MS
            if (!stale) return@withContext LyricsResult.None
        }

        val (title, artist) = cleanQuery(track.title, track.artist)
        if (title.isEmpty()) return@withContext LyricsResult.None

        val found = try {
            lookup(title, artist, track.duration)
        } catch (err: IOException) {
            // Offline or LRCLIB down. Deliberately not cached and deliberately
            // not reported as "no lyrics": those are different answers.
            return@withContext LyricsResult.Failed(
                err.message ?: "Could not reach the lyrics service."
            )
        }

        // Only recorded against a track the library actually holds, because the
        // row is keyed to one.
        if (tracks.byId(track.videoId) != null) {
            lyrics.put(
                LyricsEntity(
                    videoId = track.videoId,
                    synced = found?.first,
                    plain = found?.second,
                    fetchedAt = System.currentTimeMillis()
                )
            )
        }

        if (found == null) LyricsResult.None
        else toResult(found.first, found.second)
    }

    /** Throws away what is cached for a track, so the next look is a fresh one. */
    suspend fun refresh(track: TrackEntity): LyricsResult = withContext(Dispatchers.IO) {
        lyrics.put(LyricsEntity(track.videoId, null, null, 0))
        forTrack(track)
    }

    private fun stored(row: LyricsEntity): LyricsResult = toResult(row.synced, row.plain)

    private fun toResult(synced: String?, plain: String?): LyricsResult {
        val lines = synced?.takeIf { it.isNotBlank() }?.let(::parseLrc).orEmpty()
        if (lines.isNotEmpty()) return LyricsResult.Found(lines, plain)
        if (!plain.isNullOrBlank()) return LyricsResult.Found(null, plain)
        return LyricsResult.None
    }

    /**
     * Asks LRCLIB, exact match first and then its search.
     *
     * Duration is deliberately left off the /get call: LRCLIB matches it within
     * about ten seconds, and a YouTube upload's length routinely differs from the
     * released track's, which turns a good match into a 404. The search fallback
     * then picks whichever result is closest to the length we do have.
     */
    private fun lookup(title: String, artist: String, durationSec: Long): Pair<String?, String?>? {
        val params = "artist_name=${encode(artist)}&track_name=${encode(title)}"

        get("https://lrclib.net/api/get?$params")?.let { body ->
            val json = JSONObject(body)
            if (!json.optBoolean("instrumental", false)) {
                val synced = json.optStringOrNull("syncedLyrics")
                val plain = json.optStringOrNull("plainLyrics")
                if (synced != null || plain != null) return synced to plain
            }
        }

        val searchBody = get("https://lrclib.net/api/search?$params") ?: return null
        val results = JSONArray(searchBody)
        var best: JSONObject? = null
        var bestDelta = Long.MAX_VALUE
        for (i in 0 until results.length()) {
            val candidate = results.optJSONObject(i) ?: continue
            if (candidate.optBoolean("instrumental", false)) continue
            val synced = candidate.optStringOrNull("syncedLyrics")
            val plain = candidate.optStringOrNull("plainLyrics")
            if (synced == null && plain == null) continue
            val delta = if (durationSec <= 0) 0
            else abs(candidate.optLong("duration", 0L) - durationSec)
            if (best == null || delta < bestDelta) {
                best = candidate
                bestDelta = delta
            }
            if (durationSec <= 0) break
        }
        val chosen = best ?: return null
        return chosen.optStringOrNull("syncedLyrics") to chosen.optStringOrNull("plainLyrics")
    }

    private fun get(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            return response.body.string()
        }
    }

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8")

    private fun JSONObject.optStringOrNull(key: String): String? =
        optString(key, "").takeIf { it.isNotBlank() }

    private companion object {
        const val USER_AGENT = "SYmuS Android (personal music player)"

        /** Negative results are cached too, but rechecked eventually. */
        const val NEGATIVE_TTL_MS = 30L * 24 * 60 * 60 * 1000
    }
}
