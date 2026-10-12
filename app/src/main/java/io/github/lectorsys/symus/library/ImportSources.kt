package io.github.lectorsys.symus.library

/**
 * Where a playlist is being imported from, and what reaching it takes.
 *
 * [needsCredentials] replaced an earlier `reachable = false` on Spotify and
 * Apple Music, which was wrong. The old reasoning was that both "need a client
 * secret a distributed app cannot keep". Spotify's own documentation
 * recommends Authorization Code with PKCE for exactly this case and it uses no
 * secret at all; Apple's developer token is a finished JWT that can be pasted
 * in without the signing key ever touching the device.
 *
 * What is true is that both need a registration the user holds rather than one
 * this app can ship: a Spotify client id from their dashboard, an Apple
 * developer token from their membership. So the adapters are complete and the
 * credentials are configuration — which is a different thing to tell a user
 * than "not supported", and the Import screen says which one they are looking
 * at.
 */
enum class Provider(
    val id: String,
    val label: String,
    /** True where an import cannot start until the user supplies credentials. */
    val needsCredentials: Boolean
) {
    /** The extractor speaks YouTube directly. Nothing to configure. */
    YOUTUBE("youtube", "YouTube", needsCredentials = false),
    SPOTIFY("spotify", "Spotify", needsCredentials = true),
    APPLE("apple", "Apple Music", needsCredentials = true),

    /** A file the user exported themselves. It is already on the device. */
    FILE("file", "File", needsCredentials = false);

    companion object {
        fun of(id: String): Provider = entries.firstOrNull { it.id == id } ?: FILE
    }
}

/** A link, once it has been recognised. */
data class SourceLink(
    val provider: Provider,
    /** The provider's own id for the playlist, used to make re-imports idempotent. */
    val sourceId: String,
    val url: String
)

/**
 * What a provider needs from the user before it can be used, stated exactly.
 *
 * Written out rather than summarised as "unsupported" because the user is
 * entitled to know whether this is a missing feature, a deliberate choice, or
 * a thing they can fix in five minutes — and for both of these it is the third.
 */
fun Provider.setupInstructions(): String = when (this) {
    Provider.SPOTIFY ->
        "Spotify needs a client id that belongs to you. Open " +
            "developer.spotify.com/dashboard, create an app, add " +
            "symus://spotify-callback as a Redirect URI, tick Web API, and paste the " +
            "Client ID into Import settings. Then sign in. A new app starts in " +
            "development mode, which can only read data for accounts added under " +
            "Users and Access on that same page — including your own."

    Provider.APPLE ->
        "Apple Music needs a developer token: an ES256 JWT signed with a key from " +
            "a paid Apple Developer membership. Generate it on a machine you control " +
            "and paste the token — never the .p8 key itself, which this app refuses. " +
            "Public catalogue playlists work with the developer token alone; your own " +
            "library playlists additionally need a Music User Token from MusicKit."

    Provider.YOUTUBE -> "Nothing to set up. Paste a playlist link."
    Provider.FILE -> "Export a CSV, M3U, M3U8 or plain text file and open it here."
}

/**
 * Recognises a pasted link.
 *
 * Returns null for anything unrecognised rather than guessing, so a mistyped
 * URL fails at the paste rather than halfway through an import.
 */
fun parseSourceLink(input: String): SourceLink? {
    val text = input.trim().trim('<', '>')
    if (text.isEmpty()) return null

    Regex("""(?:music\.)?youtube\.com/playlist\?[^ ]*list=([\w-]+)""").find(text)?.let {
        return SourceLink(Provider.YOUTUBE, it.groupValues[1], normalise(text))
    }
    Regex("""(?:music\.)?youtube\.com/watch\?[^ ]*list=([\w-]+)""").find(text)?.let {
        val id = it.groupValues[1]
        // A mix (RD…) is generated per viewer and never ends; importing one
        // would produce a snapshot of something that is not a playlist.
        if (id.startsWith("RD")) return null
        return SourceLink(
            Provider.YOUTUBE,
            id,
            "https://www.youtube.com/playlist?list=$id"
        )
    }
    Regex("""open\.spotify\.com/playlist/([A-Za-z0-9]+)""").find(text)?.let {
        return SourceLink(Provider.SPOTIFY, it.groupValues[1], normalise(text))
    }
    Regex("""music\.apple\.com/[^ ]*playlist/[^/]*/(pl\.[A-Za-z0-9.-]+)""").find(text)?.let {
        return SourceLink(Provider.APPLE, it.groupValues[1], normalise(text))
    }
    return null
}

private fun normalise(text: String): String =
    if (text.startsWith("http://") || text.startsWith("https://")) text else "https://$text"

/** One line of a source playlist, before anything has been matched to it. */
data class SourceTrack(
    val position: Int,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val durationSec: Long = 0,
    val isrc: String? = null,
    val sourceTrackId: String? = null
)

/**
 * Reads an exported playlist file.
 *
 * Handles the three shapes people actually have:
 *
 * - **CSV** from the Spotify exporters everybody uses, which put the column
 *   names in the first row. Columns are found by name, not by position, because
 *   every exporter orders them differently.
 * - **M3U/M3U8**, where `#EXTINF:` carries "seconds,Artist - Title".
 * - **Plain text**, one "Artist - Title" per line.
 *
 * Returns an empty list rather than throwing on a file it cannot make sense
 * of; the caller reports that as "nothing recognisable in this file", which is
 * more useful than a parser exception.
 */
fun parsePlaylistFile(name: String, content: String): List<SourceTrack> {
    val lines = content.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
    if (lines.isEmpty()) return emptyList()

    return when {
        name.endsWith(".csv", ignoreCase = true) || looksLikeCsv(lines.first()) ->
            parseCsv(lines)
        name.endsWith(".m3u", ignoreCase = true) ||
            name.endsWith(".m3u8", ignoreCase = true) ||
            lines.first().startsWith("#EXTM3U") -> parseM3u(lines)
        else -> parsePlain(lines)
    }
}

private fun looksLikeCsv(header: String): Boolean {
    val lower = header.lowercase()
    return lower.contains(",") &&
        (lower.contains("track name") || lower.contains("title")) &&
        (lower.contains("artist") || lower.contains("artist name"))
}

private fun parseCsv(lines: List<String>): List<SourceTrack> {
    val header = splitCsv(lines.first()).map { it.trim().lowercase() }
    fun indexOf(vararg names: String) =
        names.firstNotNullOfOrNull { name -> header.indexOf(name).takeIf { it >= 0 } }

    val titleAt = indexOf("track name", "title", "name", "song") ?: return emptyList()
    val artistAt = indexOf("artist name(s)", "artist name", "artist", "artists")
    val albumAt = indexOf("album name", "album")
    val isrcAt = indexOf("isrc")
    val idAt = indexOf("track id", "spotify id", "id", "track uri")
    val msAt = indexOf("duration (ms)", "track duration (ms)", "duration_ms")
    val secAt = indexOf("duration (s)", "duration", "time")

    return lines.drop(1).mapIndexedNotNull { index, line ->
        val cells = splitCsv(line)
        val title = cells.getOrNull(titleAt)?.trim().orEmpty()
        if (title.isEmpty()) return@mapIndexedNotNull null

        val seconds = msAt?.let { cells.getOrNull(it)?.trim()?.toLongOrNull()?.div(1000) }
            ?: secAt?.let { cells.getOrNull(it)?.trim()?.let(::parseDuration) }
            ?: 0L

        SourceTrack(
            position = index,
            title = title,
            artist = artistAt?.let { cells.getOrNull(it)?.trim() }?.takeIf { it.isNotEmpty() },
            album = albumAt?.let { cells.getOrNull(it)?.trim() }?.takeIf { it.isNotEmpty() },
            durationSec = seconds,
            isrc = isrcAt?.let { cells.getOrNull(it)?.trim() }?.takeIf { it.isNotEmpty() },
            sourceTrackId = idAt?.let { cells.getOrNull(it)?.trim() }?.takeIf { it.isNotEmpty() }
        )
    }
}

/**
 * A CSV splitter that understands quotes.
 *
 * Track titles contain commas constantly — "Hello, Goodbye" — and splitting on
 * the comma alone shifts every later column by one, which silently imports the
 * album name as the artist.
 */
internal fun splitCsv(line: String): List<String> {
    val cells = mutableListOf<String>()
    val current = StringBuilder()
    var quoted = false
    var index = 0
    while (index < line.length) {
        val char = line[index]
        when {
            char == '"' && quoted && index + 1 < line.length && line[index + 1] == '"' -> {
                current.append('"')
                index++
            }
            char == '"' -> quoted = !quoted
            char == ',' && !quoted -> {
                cells += current.toString()
                current.clear()
            }
            else -> current.append(char)
        }
        index++
    }
    cells += current.toString()
    return cells
}

private fun parseM3u(lines: List<String>): List<SourceTrack> {
    val out = mutableListOf<SourceTrack>()
    var pending: Pair<Long, String>? = null
    for (line in lines) {
        when {
            line.startsWith("#EXTINF:") -> {
                val payload = line.removePrefix("#EXTINF:")
                val seconds = payload.substringBefore(',').trim().toLongOrNull() ?: 0L
                pending = seconds to payload.substringAfter(',', "").trim()
            }
            line.startsWith("#") -> Unit
            else -> {
                val (seconds, label) = pending ?: (0L to line.substringAfterLast('/'))
                val (artist, title) = splitArtistTitle(label)
                out += SourceTrack(
                    position = out.size,
                    title = title,
                    artist = artist,
                    durationSec = seconds.coerceAtLeast(0)
                )
                pending = null
            }
        }
    }
    return out
}

private fun parsePlain(lines: List<String>): List<SourceTrack> =
    lines.filterNot { it.startsWith("#") }.mapIndexed { index, line ->
        val (artist, title) = splitArtistTitle(line)
        SourceTrack(position = index, title = title, artist = artist)
    }

/** "Artist - Title" where that is what it is, otherwise the whole thing as a title. */
internal fun splitArtistTitle(text: String): Pair<String?, String> {
    val separator = listOf(" - ", " – ", " — ").firstOrNull { text.contains(it) }
        ?: return null to text.trim()
    val artist = text.substringBefore(separator).trim()
    val title = text.substringAfter(separator).trim()
    return if (artist.isEmpty() || title.isEmpty()) null to text.trim() else artist to title
}

/** "3:42" or "222" into seconds. */
private fun parseDuration(text: String): Long {
    if (!text.contains(':')) return text.toLongOrNull() ?: 0L
    return text.split(':')
        .mapNotNull { it.trim().toLongOrNull() }
        .fold(0L) { total, part -> total * 60 + part }
}
