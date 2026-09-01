package io.github.lectorsys.symus.library

/**
 * Keyword -> genre, first match wins.
 *
 * Ported from the desktop app, rules and order intact. Order matters: the
 * specific patterns sit above the general ones, so "lofi hip hop radio" is
 * Lo-fi rather than Hip-hop.
 *
 * This is a lookup table, not a classifier. It is wrong sometimes and cheap
 * always, and a track can be retagged by hand.
 */
private val GENRE_RULES: List<Pair<String, Regex>> = listOf(
    "Lo-fi" to Regex("""\blo-?fi\b|chillhop|study beats""", RegexOption.IGNORE_CASE),
    "Classical" to Regex(
        """\bclassical\b|symphony|concerto|sonata|orchestra|beethoven|mozart|chopin|bach\b""",
        RegexOption.IGNORE_CASE
    ),
    "Jazz" to Regex("""\bjazz\b|bebop|swing band|saxophone|blue note""", RegexOption.IGNORE_CASE),
    "Metal" to Regex("""\bmetal\b|metalcore|deathcore|thrash""", RegexOption.IGNORE_CASE),
    // `punk` alone matched the band name in "ft. Daft Punk" and tagged The Weeknd
    // as Rock. Genre words that are also common in band and song names must carry
    // their qualifier.
    "Rock" to Regex(
        """\brock\b|punk rock|pop punk|grunge|alt(ernative)?[- ]rock|indie rock|guitar solo""",
        RegexOption.IGNORE_CASE
    ),
    "Hip-hop" to Regex("""\bhip[- ]?hop\b|\brap\b|\btrap\b|drill\b|freestyle""", RegexOption.IGNORE_CASE),
    // Bare `soul` caught "Hey Soul Sister" and "Heart and Soul".
    "R&B" to Regex(
        """\br ?& ?b\b|rhythm and blues|neo[- ]soul|soul music|northern soul""",
        RegexOption.IGNORE_CASE
    ),
    // Bare `house` caught "Full House Theme Song".
    "Electronic" to Regex(
        """\bedm\b|house music|deep house|tech house|progressive house|techno|trance|dubstep|drum ?& ?bass|\bdnb\b|synthwave|electro\b""",
        RegexOption.IGNORE_CASE
    ),
    "Pop" to Regex("""\bpop\b|top ?40|k-?pop|j-?pop""", RegexOption.IGNORE_CASE),
    "Bollywood" to Regex("""bollywood|hindi song|arijit|shreya ghoshal|\bfilmi\b""", RegexOption.IGNORE_CASE),
    "Indian classical" to Regex(
        """carnatic|hindustani|raag|raga\b|bhajan|ghazal|qawwali""",
        RegexOption.IGNORE_CASE
    ),
    "Country" to Regex("""\bcountry\b|bluegrass|honky tonk""", RegexOption.IGNORE_CASE),
    "Folk" to Regex("""\bfolk\b|acoustic|singer[- ]songwriter""", RegexOption.IGNORE_CASE),
    "Latin" to Regex("""reggaeton|salsa|bachata|latin\b|cumbia""", RegexOption.IGNORE_CASE),
    "Reggae" to Regex("""\breggae\b|dancehall|ska\b""", RegexOption.IGNORE_CASE),
    "Ambient" to Regex("""\bambient\b|meditation|white noise|sleep music|rain sounds""", RegexOption.IGNORE_CASE),
    "Soundtrack" to Regex("""soundtrack|\bost\b|original score|theme song|\bbgm\b""", RegexOption.IGNORE_CASE)
)

/** Every genre the tagger can produce, in rule order. */
val KNOWN_GENRES: List<String> = GENRE_RULES.map { it.first }

/** Best-effort genre from whatever text there is. Null when nothing matches. */
fun inferGenre(vararg text: String?): String? {
    val blob = text.filterNotNull().joinToString(" ")
    if (blob.isBlank()) return null
    return GENRE_RULES.firstOrNull { (_, pattern) -> pattern.containsMatchIn(blob) }?.first
}

/**
 * The genre to file a track under when it is saved.
 *
 * The track's own text decides first; the search or playlist name it arrived
 * with is only a fallback. They are deliberately never blended: with both in
 * one blob, a "jazz essentials" search would tag a metal track Jazz, because
 * the winner becomes whichever rule sits higher in the table rather than which
 * text actually matched.
 */
fun genreFor(title: String?, artist: String?, hint: String? = null): String? =
    inferGenre(title, artist) ?: inferGenre(hint)
