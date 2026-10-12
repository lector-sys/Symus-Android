package io.github.lectorsys.symus.library

import io.github.lectorsys.symus.data.ArtistPlays
import io.github.lectorsys.symus.data.LibraryRepository
import io.github.lectorsys.symus.data.TrackEntity
import io.github.lectorsys.symus.data.TrackWithPlays
import io.github.lectorsys.symus.media.Extractor
import io.github.lectorsys.symus.media.SearchHit
import io.github.lectorsys.symus.media.SearchKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One row on Explore: a title, the search behind it, and the genre to file what
 * it finds under.
 *
 * The query is part of the model on purpose. Explore is best-effort discovery
 * built from ordinary YouTube searches — there is no trending API behind any of
 * it — and a shelf that cannot say where its rows came from is one that invites
 * being read as a chart.
 */
data class ExploreShelf(
    val id: String,
    val title: String,
    val query: String,
    val hint: String?
)

/** A genre offered as a tile. [count] is how many tracks already carry it. */
data class GenreTile(val genre: String, val count: Int)

/**
 * What the Charts screen shows: this library's own play counts.
 *
 * There is no global chart behind any of this and no API here that could
 * provide one. [sparse] means there has been too little listening for the
 * ranking to say anything, which the screen states rather than papering over.
 */
data class Charts(
    val tracks: List<TrackWithPlays>,
    val artists: List<ArtistPlays>,
    val sparse: Boolean
)

@Singleton
class ExploreRepository @Inject constructor(
    private val extractor: Extractor,
    private val library: LibraryRepository
) {
    private data class Cached(val tracks: List<TrackEntity>, val fetchedAt: Long)

    private val cache = HashMap<String, Cached>()
    private val cacheLock = Mutex()
    private val inflight = HashSet<String>()

    /**
     * Where a stale-shelf refresh runs.
     *
     * Deliberately not the caller's scope: the point of the refresh is that the
     * caller does not wait for it, and a screen that closes mid-refresh should
     * not abandon a result the cache is about to want.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Which shelves to show, from what the user actually listens to.
     *
     * Cheap — a couple of grouped queries against SQLite, no network — so the
     * screen can lay out its rows immediately and fill them in as they arrive.
     */
    suspend fun planShelves(): List<ExploreShelf> = withContext(Dispatchers.IO) {
        val byGenre = library.genres()
        val byArtist = library.topArtists(6)
        val year = Calendar.getInstance().get(Calendar.YEAR)
        val shelves = mutableListOf<ExploreShelf>()

        // First, so it survives the cap below and the screen opens on it.
        //
        // YouTube search does not expose release type, so this is the same
        // best-effort search every other shelf runs, seeded with the genre the
        // library leans on hardest. The title says albums and singles because
        // that is what the query asks for, not because the source can tell them
        // apart.
        val lead = byGenre.firstOrNull()?.genre
        shelves += ExploreShelf(
            id = "new-albums-singles",
            title = "New albums and singles",
            query = if (lead != null) "new $lead albums and singles $year"
            else "new albums and singles $year",
            hint = lead
        )

        byGenre.take(3).forEach {
            shelves += ExploreShelf(
                "genre-new-${it.genre}",
                "New in ${it.genre}",
                "new ${it.genre} music $year",
                it.genre
            )
        }
        byGenre.take(2).forEach {
            shelves += ExploreShelf(
                "genre-best-${it.genre}",
                "Essential ${it.genre}",
                "best ${it.genre} songs",
                it.genre
            )
        }
        byArtist.take(2).forEach {
            shelves += ExploreShelf(
                "artist-${it.artist}",
                "More like ${it.artist}",
                "${it.artist} similar artists songs",
                null
            )
        }

        // Nothing in the library yet, or too little to fill the screen. A fixed
        // set at least gives a new listener something to press.
        val generic = listOf(
            ExploreShelf("g-new", "Fresh this year", "new music $year", null),
            ExploreShelf("g-chill", "Something calm", "chill music mix", "Lo-fi"),
            ExploreShelf("g-focus", "For focus", "focus instrumental music", "Ambient"),
            ExploreShelf("g-energy", "Pick up the pace", "upbeat energetic songs", null)
        )
        for (shelf in generic) {
            if (shelves.size >= SHELF_LIMIT) break
            shelves += shelf
        }
        shelves.take(SHELF_LIMIT)
    }

    /**
     * Cached shelf contents.
     *
     * A stale shelf is served immediately and refreshed behind the request, so a
     * revisit after the TTL is instant rather than a wait for rows already seen.
     */
    suspend fun shelf(query: String, hint: String?): ShelfContents {
        val hit = cacheLock.withLock { cache[query] }
        if (hit != null) {
            val stale = System.currentTimeMillis() - hit.fetchedAt > TTL_MS
            // Launched, not awaited. Awaiting it here made a revisit after the
            // TTL wait for the network and *then* hand back the stale rows
            // anyway — the slow path and the stale answer at the same time.
            if (stale) scope.launch { refreshInBackground(query, hint) }
            return ShelfContents(hit.tracks, stale)
        }
        return ShelfContents(fetch(query, hint), false)
    }

    private suspend fun refreshInBackground(query: String, hint: String?) {
        val claimed = cacheLock.withLock { inflight.add(query) }
        if (!claimed) return
        runCatching { fetch(query, hint) }
        cacheLock.withLock { inflight.remove(query) }
    }

    private suspend fun fetch(query: String, hint: String?): List<TrackEntity> {
        val page = extractor.search(query, SearchKind.SONGS, PER_SHELF)
        // Saved the same way a manual search saves, so Explore grows the library's
        // knowledge rather than showing rows that vanish when the screen closes.
        // It does not put anything in the user's saved list.
        val tracks = library.remember(page.hits, hint ?: query)
        cacheLock.withLock { cache[query] = Cached(tracks, System.currentTimeMillis()) }
        return tracks
    }

    suspend fun clearCache() = cacheLock.withLock { cache.clear() }

    /**
     * Tiles for the moods and genres grid: what the library has, biggest first,
     * then the common genres it does not have yet.
     *
     * Sparse genres are deliberately not hidden. A tile with nothing behind it is
     * the entry point to "find more", not a dead end — and the way a genre gets
     * its first track is someone pressing its tile.
     */
    suspend fun genreTiles(): List<GenreTile> = withContext(Dispatchers.IO) {
        val tagged = library.genres()
        val have = tagged.map { it.genre.lowercase() }.toSet()
        tagged.map { GenreTile(it.genre, it.count) } +
            COMMON_GENRES.filter { it.lowercase() !in have }.map { GenreTile(it, 0) }
    }

    /**
     * Searches YouTube for a genre and files the results under it.
     *
     * The tagger reads each title, but a genre with no inference rule —
     * Devotional, Punjabi — would leave every result untagged and the screen the
     * user is standing on would not gain a row. So anything that came back
     * without a tag gets the genre that was asked for; rows that already carry
     * one keep it, since a specific guess beats the search term.
     */
    suspend fun findMoreInGenre(genre: String, limit: Int = 20): Int = withContext(Dispatchers.IO) {
        val page = extractor.search("$genre music", SearchKind.SONGS, limit)
        if (page.hits.isEmpty()) return@withContext library.countInGenre(genre)
        val saved = library.remember(page.hits, genre)
        saved.filter { it.genre == null }.forEach { library.setGenre(it.videoId, genre) }
        cacheLock.withLock { cache.remove("$genre music") }
        library.countInGenre(genre)
    }

    suspend fun charts(): Charts = withContext(Dispatchers.IO) {
        val tracks = library.mostPlayed(12)
        val artists = library.mostPlayedArtists(12)
        // Under eight played tracks the ranking is noise — one afternoon of
        // listening ordered by accident. The screen still shows it, and says so.
        Charts(tracks, artists, sparse = tracks.size < 8)
    }

    private companion object {
        /** Six shelves is enough to fill a screen and few enough not to be rate limited. */
        const val SHELF_LIMIT = 6
        const val PER_SHELF = 12

        /**
         * Six hours. Search results for "new lo-fi 2026" do not meaningfully
         * change within a day, and a shorter window would re-run every shelf on
         * every second visit for the same rows.
         */
        const val TTL_MS = 6L * 60 * 60 * 1000

        /**
         * Genres offered as tiles even when nothing is tagged with them yet.
         *
         * Most match a rule in GenreRules and fill themselves in over time; the
         * ones that do not — Devotional, Punjabi, Tamil — only ever arrive
         * through "find more", which is why that path tags what it saves.
         */
        val COMMON_GENRES = listOf(
            "Rock", "Pop", "Hip-hop", "Electronic", "Classical", "Jazz", "Lo-fi", "R&B",
            "Metal", "Folk", "Country", "Latin", "Reggae", "Ambient", "Soundtrack",
            "Bollywood", "Indian classical", "Devotional", "Punjabi", "Tamil"
        )
    }
}

/** A shelf's rows, and whether a refresh is running behind them. */
data class ShelfContents(val tracks: List<TrackEntity>, val refreshing: Boolean)

/**
 * The Podcasts tab.
 *
 * There is no podcast directory behind this. It is the same YouTube search the
 * rest of the app uses, pointed at talk-shaped queries, and what comes back is
 * whatever YouTube ranks for them — which the screen says. What makes it a
 * podcasts tab rather than a search box is that results are tagged
 * `contentType: "podcast"` so they stay out of the music shelves, and that
 * channels can be followed.
 */
@Singleton
class PodcastRepository @Inject constructor(
    private val extractor: Extractor,
    private val library: LibraryRepository
) {
    private data class Cached(val tracks: List<TrackEntity>, val fetchedAt: Long)

    private val cache = HashMap<String, Cached>()
    private val lock = Mutex()

    /** videoId -> the channel that uploaded it, learned from search results. */
    private val channelIndex = HashMap<String, Pair<String, String>>()

    val following = library.followedChannels()

    /** One shelf of talk uploads, plus the channels seen in it. */
    suspend fun discover(query: String = seedForDay()): PodcastShelf = withContext(Dispatchers.IO) {
        val hit = lock.withLock { cache[query] }
        if (hit != null && System.currentTimeMillis() - hit.fetchedAt < TTL_MS) {
            return@withContext PodcastShelf(query, hit.tracks, channelsOf(hit.tracks))
        }

        // The music filter would throw away exactly what this tab is looking for.
        val page = extractor.search(query, SearchKind.VIDEOS, PER_SHELF)
        for (hitItem in page.hits) {
            val channelId = hitItem.channelId
            val channelName = hitItem.channelName
            if (channelId != null && channelName != null) {
                channelIndex[hitItem.track.videoId] = channelId to channelName
            }
        }
        val tracks = savePodcasts(page.hits)
        lock.withLock { cache[query] = Cached(tracks, System.currentTimeMillis()) }
        PodcastShelf(query, tracks, channelsOf(tracks))
    }

    /** Recent uploads from one followed channel, cached on the same terms. */
    suspend fun channelShelf(channelId: String): List<TrackEntity> = withContext(Dispatchers.IO) {
        val key = "channel:$channelId"
        val hit = lock.withLock { cache[key] }
        if (hit != null && System.currentTimeMillis() - hit.fetchedAt < TTL_MS) return@withContext hit.tracks

        val hits = extractor.channelUploads(channelId, PER_SHELF)
        val saved = library.remember(hits, null)
        library.tagContentType(saved.map { it.videoId }, "podcast")
        val tracks = library.byIds(saved.map { it.videoId })
        lock.withLock { cache[key] = Cached(tracks, System.currentTimeMillis()) }
        tracks
    }

    suspend fun follow(channelId: String, name: String) = library.follow(channelId, name)

    suspend fun unfollow(channelId: String) = library.unfollow(channelId)

    /**
     * Files tracks as podcasts.
     *
     * The library's merge knows nothing about content types, so the tag goes on
     * afterwards by videoId. Everything this tab surfaces goes through here,
     * which is what keeps talk uploads out of the music shelves without a second
     * table.
     */
    private suspend fun savePodcasts(hits: List<SearchHit>): List<TrackEntity> {
        if (hits.isEmpty()) return emptyList()
        // No genre hint: a talk-shaped query would tag every episode with
        // whatever genre word happened to be in it.
        val saved = library.remember(hits, null)
        library.tagContentType(saved.map { it.videoId }, "podcast")
        return library.byIds(saved.map { it.videoId })
    }

    private fun channelsOf(tracks: List<TrackEntity>): List<PodcastChannel> {
        val seen = LinkedHashMap<String, String>()
        for (track in tracks) {
            channelIndex[track.videoId]?.let { (id, name) -> seen[id] = name }
        }
        return seen.map { PodcastChannel(it.key, it.value) }
    }

    companion object {
        /** Talk-shaped seeds. Music words are avoided; "episode" and "interview" carry it. */
        val SEEDS = listOf(
            "podcast full episode",
            "long form interview podcast",
            "tech podcast episode",
            "history podcast episode",
            "science podcast full episode",
            "comedy podcast episode"
        )

        private const val PER_SHELF = 12
        private const val TTL_MS = 6L * 60 * 60 * 1000

        /**
         * The seed for today, rotated by day of year so the tab is not the same
         * six rows forever — and not reshuffled while someone is reading it.
         */
        fun seedForDay(millis: Long = System.currentTimeMillis()): String {
            val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            calendar.timeInMillis = millis
            val day = calendar.get(Calendar.DAY_OF_YEAR)
            return SEEDS[day % SEEDS.size]
        }
    }
}

data class PodcastChannel(val channelId: String, val name: String)

data class PodcastShelf(
    val query: String,
    val tracks: List<TrackEntity>,
    val channels: List<PodcastChannel>
)
