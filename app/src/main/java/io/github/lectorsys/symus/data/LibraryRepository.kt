package io.github.lectorsys.symus.data

import io.github.lectorsys.symus.library.genreFor
import io.github.lectorsys.symus.library.inferGenre
import io.github.lectorsys.symus.media.Extractor
import io.github.lectorsys.symus.media.SearchHit
import io.github.lectorsys.symus.media.SearchKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ln
import kotlin.math.max

/**
 * The library, and the one place anything is written to it.
 *
 * A port of the desktop app's library.ts, including its scoring: the same
 * weights, the same exclusions, the same rule that an inferred genre never wins
 * over one the user typed. Where the two differ it is noted at the method.
 *
 * Everything here is suspending and runs on the IO dispatcher. Nothing in this
 * file touches the main thread.
 */
@Singleton
class LibraryRepository @Inject constructor(
    private val tracks: TrackDao,
    private val history: HistoryDao,
    private val feedback: FeedbackDao,
    private val playlists: PlaylistDao,
    private val searchHistory: SearchHistoryDao,
    private val channels: FollowedChannelDao,
    private val extractor: Extractor
) {

    // -- writes from YouTube ----------------------------------------------

    /**
     * Records what a search or a shelf found, creating rows for what is new and
     * refreshing only YouTube's own fields on what is not.
     *
     * `hint` is the search query or playlist name the tracks arrived with —
     * extra text for the genre guess, and only a fallback: the track's own title
     * and artist decide first. Blending the two lets a "jazz essentials" search
     * tag a metal track Jazz, because the winner becomes whichever rule sits
     * higher in the table rather than which text actually matched.
     *
     * This does not put anything in the user's library. See [save].
     */
    suspend fun remember(hits: List<SearchHit>, hint: String? = null): List<TrackEntity> =
        withContext(Dispatchers.IO) {
            if (hits.isEmpty()) return@withContext emptyList()
            val rows = hits.map { hit ->
                hit.track.copy(genre = genreFor(hit.track.title, hit.track.artist, hint))
            }
            tracks.merge(rows)
            // The stored rows, not the ones just built: an existing row keeps the
            // genre, savedAt and offline path it already had, and callers that
            // render this list must show what the library actually holds.
            val stored = tracks.byIds(rows.map { it.videoId }).associateBy { it.videoId }
            rows.mapNotNull { stored[it.videoId] }
        }

    /** Files tracks under a content type — what keeps talk uploads out of the music shelves. */
    suspend fun tagContentType(videoIds: List<String>, contentType: String?) =
        withContext(Dispatchers.IO) {
            if (videoIds.isNotEmpty()) tracks.setContentType(videoIds, contentType)
        }

    /** Names the release a track belongs to. Only YouTube Music album pages know this. */
    suspend fun tagAlbum(videoId: String, album: String?) = withContext(Dispatchers.IO) {
        tracks.setAlbum(videoId, album?.trim()?.takeIf { it.isNotEmpty() })
    }

    // -- the library itself ------------------------------------------------

    /**
     * Puts a track in the library, deliberately as its own step.
     *
     * The desktop app has no such distinction: a search there wrote rows that
     * were, by definition, the library. On a phone a week of searching would
     * bury what the user actually chose, so saving is an action — taken by this
     * method, by liking, by adding to a playlist, and by downloading.
     */
    suspend fun save(videoId: String) = withContext(Dispatchers.IO) {
        tracks.markSaved(videoId, System.currentTimeMillis())
    }

    suspend fun saveAll(videoIds: List<String>) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        videoIds.forEach { tracks.markSaved(it, now) }
    }

    /** Removes from the library without touching plays, likes or playlists. */
    suspend fun unsave(videoId: String) = withContext(Dispatchers.IO) { tracks.unsave(videoId) }

    fun savedTracks(): Flow<List<TrackEntity>> = tracks.savedFlow()
    fun savedIds(): Flow<List<String>> = tracks.savedIdsFlow()
    fun savedCount(): Flow<Int> = tracks.savedCountFlow()

    suspend fun byId(videoId: String): TrackEntity? = withContext(Dispatchers.IO) {
        tracks.byId(videoId)
    }

    suspend fun byIds(videoIds: List<String>): List<TrackEntity> = withContext(Dispatchers.IO) {
        val found = tracks.byIds(videoIds).associateBy { it.videoId }
        videoIds.mapNotNull { found[it] }
    }

    suspend fun localSearch(term: String, limit: Int = 30): List<TrackEntity> =
        withContext(Dispatchers.IO) {
            if (term.isBlank()) emptyList() else tracks.localSearch(term.trim(), limit)
        }

    /**
     * Ages out rows nobody refers to.
     *
     * Search results are written to the same table the library lives in, so
     * without this the database is an ever-growing cache of everything ever
     * scrolled past. Called once at startup, which is often enough for a table
     * that grows by a few dozen rows a session.
     */
    suspend fun pruneCache(keepForDays: Int = 14): Int = withContext(Dispatchers.IO) {
        tracks.pruneUnreferenced(System.currentTimeMillis() - keepForDays * 86_400_000L)
    }

    // -- plays and feedback -----------------------------------------------

    /**
     * Records a play, and saves nothing.
     *
     * Guarded against double counting: the player reports a start from more than
     * one place — a media-item transition, a resumed session, a controller
     * reconnecting — and two of those arriving together must not become two
     * plays of the same track.
     */
    suspend fun recordPlay(videoId: String): Boolean = withContext(Dispatchers.IO) {
        val last = history.lastPlayedAt(videoId)
        if (last != null && System.currentTimeMillis() - last < PLAY_DEBOUNCE_MS) return@withContext false
        if (tracks.byId(videoId) == null) return@withContext false
        history.record(HistoryEntryEntity(videoId = videoId))
        true
    }

    /**
     * Credits measured listening time to the latest play of a track.
     *
     * The caller measures; this only stores. See PlaybackService for why the
     * measurement is wall-clock and not playback position.
     */
    suspend fun addListened(videoId: String, ms: Long) = withContext(Dispatchers.IO) {
        if (ms > 0) history.addListened(videoId, ms)
    }

    /** Marks the latest play of a track as skipped — a mild negative signal. */
    suspend fun markSkipped(videoId: String) = withContext(Dispatchers.IO) {
        history.markSkipped(videoId)
    }

    /** Where to pick a track up again. Cleared once it is close enough to the end. */
    suspend fun setResume(videoId: String, positionMs: Long, durationMs: Long) =
        withContext(Dispatchers.IO) {
            val worthKeeping = positionMs > RESUME_MIN_MS &&
                (durationMs <= 0 || durationMs - positionMs > RESUME_TAIL_MS)
            tracks.setResume(videoId, if (worthKeeping) positionMs else 0)
        }

    /**
     * Liking also saves: a heart is the shortest way anyone says "keep this",
     * and a liked track that is not in the library would be a surprise.
     *
     * Holding both a like and a dislike is meaningless, so each clears the other.
     */
    suspend fun setLiked(videoId: String, liked: Boolean) = withContext(Dispatchers.IO) {
        if (liked) {
            feedback.like(LikeEntity(videoId))
            feedback.undislike(videoId)
            tracks.markSaved(videoId, System.currentTimeMillis())
        } else {
            feedback.unlike(videoId)
        }
    }

    suspend fun setDisliked(videoId: String, disliked: Boolean) = withContext(Dispatchers.IO) {
        if (disliked) {
            feedback.dislike(DislikeEntity(videoId))
            feedback.unlike(videoId)
        } else {
            feedback.undislike(videoId)
        }
    }

    fun likedTracks(): Flow<List<TrackEntity>> = feedback.liked()
    fun likedIds(): Flow<List<String>> = feedback.likedIdsFlow()
    fun dislikedIds(): Flow<List<String>> = feedback.dislikedIdsFlow()

    suspend fun dislikedTracks(): List<TrackEntity> = withContext(Dispatchers.IO) {
        feedback.dislikedNow()
    }

    suspend fun recentlyPlayed(limit: Int = 20): List<TrackEntity> = withContext(Dispatchers.IO) {
        history.recentlyPlayed(limit)
    }

    fun recentlyPlayedFlow(limit: Int = 20): Flow<List<TrackEntity>> =
        history.recentlyPlayedFlow(limit)

    suspend fun mostPlayed(limit: Int = 20): List<TrackWithPlays> = withContext(Dispatchers.IO) {
        history.mostPlayed(limit)
    }

    suspend fun mostPlayedArtists(limit: Int = 12): List<ArtistPlays> = withContext(Dispatchers.IO) {
        history.mostPlayedArtists(limit)
    }

    suspend fun playCount(videoId: String): Int = withContext(Dispatchers.IO) {
        history.playCount(videoId)
    }

    suspend fun clearHistory() = withContext(Dispatchers.IO) { history.clear() }

    // -- listening statistics ---------------------------------------------

    fun totalPlays(): Flow<Int> = history.totalPlaysFlow()
    fun skippedPlays(): Flow<Int> = history.skippedPlaysFlow()
    fun distinctPlayed(): Flow<Int> = history.distinctPlayedFlow()
    fun secondsListened(): Flow<Long> = history.secondsListenedFlow()
    fun genrePlays(limit: Int = 8): Flow<List<GenreCount>> = history.genrePlaysFlow(limit)

    // -- genres and artists ------------------------------------------------

    /**
     * Tags a track, and carries the tag across to that artist's other *untagged*
     * tracks.
     *
     * Real titles read "Artist - Song (Official Video)" and real searches read
     * "blinding lights", so neither contains a genre word and automatic
     * inference tags almost nothing. Propagating by artist is what makes manual
     * tagging worth the tap: one tag on a Weeknd track classifies every Weeknd
     * track already in the library.
     *
     * Only nulls are filled, so a tag set by hand is never overwritten. Returns
     * how many other tracks were tagged, so the UI can say so.
     */
    suspend fun setGenre(videoId: String, genre: String?): Int = withContext(Dispatchers.IO) {
        val clean = genre?.trim()?.takeIf { it.isNotEmpty() }
        val artist = tracks.byId(videoId)?.artist
        tracks.setGenre(videoId, clean)
        if (clean == null || artist.isNullOrBlank()) 0
        else tracks.tagArtistUntagged(artist, clean, videoId)
    }

    fun genresFlow(): Flow<List<GenreCount>> = tracks.genresFlow()

    suspend fun genres(): List<GenreCount> = withContext(Dispatchers.IO) { tracks.genres() }

    suspend fun tracksByGenre(genre: String, limit: Int = 200): List<TrackEntity> =
        withContext(Dispatchers.IO) { tracks.byGenre(genre, limit) }

    suspend fun countInGenre(genre: String): Int = withContext(Dispatchers.IO) {
        tracks.countInGenre(genre)
    }

    /** How much of the library carries a genre. Drives the Home fallback. */
    suspend fun genreCoverage(): Pair<Int, Int> = withContext(Dispatchers.IO) {
        tracks.taggedCount() to tracks.count()
    }

    suspend fun topArtists(limit: Int = 16): List<ArtistCount> = withContext(Dispatchers.IO) {
        tracks.topArtists(limit)
    }

    suspend fun tracksByArtist(artist: String, limit: Int = 200): List<TrackEntity> =
        withContext(Dispatchers.IO) { tracks.byArtist(artist, limit) }

    /**
     * An artist's releases, from whatever album names the library holds.
     *
     * Album is null for almost every row, because the only place YouTube exposes
     * one is a YouTube Music release page — so this is a bonus section when the
     * data exists rather than the spine of the artist page.
     */
    suspend fun albumsOf(artist: String): List<AlbumSummary> = withContext(Dispatchers.IO) {
        tracks.byArtist(artist, 500)
            .filter { !it.album.isNullOrBlank() }
            .groupBy { it.album!! }
            .map { (name, list) ->
                AlbumSummary(name, artist, list.size, list.firstNotNullOfOrNull { it.thumbnailUrl })
            }
            .sortedByDescending { it.count }
    }

    suspend fun albums(): List<AlbumSummary> = withContext(Dispatchers.IO) {
        tracks.withAlbums()
            .groupBy { it.album!! to it.artist }
            .map { (key, list) ->
                AlbumSummary(key.first, key.second, list.size, list.firstNotNullOfOrNull { it.thumbnailUrl })
            }
            .sortedBy { it.name.lowercase() }
    }

    suspend fun albumTracks(album: String, artist: String?): List<TrackEntity> =
        withContext(Dispatchers.IO) { tracks.byAlbum(album, artist) }

    // -- playlists ---------------------------------------------------------

    fun playlistSummaries(): Flow<List<PlaylistSummary>> = playlists.summaries()

    fun playlistTracks(id: Long): Flow<List<TrackEntity>> = playlists.tracksFlow(id)

    fun playlist(id: Long): Flow<PlaylistEntity?> = playlists.byIdFlow(id)

    /**
     * Which of [videoIds] have a track row, so a caller can avoid referencing
     * one that does not. Chunked because SQLite caps the variables in an
     * `IN (...)` at 999 and an import may carry more ids than that.
     */
    suspend fun knownTrackIds(videoIds: List<String>): List<String> =
        withContext(Dispatchers.IO) {
            videoIds.chunked(500).flatMap { chunk -> tracks.byIds(chunk).map { it.videoId } }
        }

    suspend fun createPlaylist(
        title: String,
        videoIds: List<String> = emptyList(),
        /**
         * Where this playlist came from, when it came from somewhere.
         *
         * Carried rather than discarded because it is what makes a re-import
         * update the same playlist instead of making a second one, and what
         * lets a restored backup still say which Spotify or YouTube playlist a
         * row started life as. An import that forgets its origin is a copy, not
         * an import — and that is exactly what this used to produce.
         */
        sourceProvider: String? = null,
        sourceId: String? = null,
        sourceUrl: String? = null
    ): Long =
        withContext(Dispatchers.IO) {
            val cover = videoIds.firstOrNull()?.let { tracks.byId(it)?.thumbnailUrl }
            val id = playlists.create(
                PlaylistEntity(
                    title = title.trim(),
                    genre = inferGenre(title),
                    coverUrl = cover,
                    sourceProvider = sourceProvider,
                    sourceId = sourceId,
                    sourceUrl = sourceUrl
                )
            )
            if (videoIds.isNotEmpty()) addToPlaylist(id, videoIds)
            id
        }

    /**
     * Appends to a playlist, skipping what is already there.
     *
     * Anything filed in a playlist is in the library by definition, so this saves
     * as well — and the playlist takes its cover from the first track if it has
     * none yet, the way the desktop app does.
     */
    suspend fun addToPlaylist(playlistId: Long, videoIds: List<String>): Int =
        withContext(Dispatchers.IO) {
            if (videoIds.isEmpty()) return@withContext 0
            val added = playlists.append(playlistId, videoIds)
            saveAll(videoIds)
            val playlistTitle = playlists.byId(playlistId)?.title
            if (playlistTitle != null) {
                // Same hint rule as a search: the playlist's name is a fallback
                // for a track that says nothing about its own genre.
                inferGenre(playlistTitle)?.let { hint ->
                    videoIds.forEach { tracks.tagIfUntagged(it, hint) }
                }
            }
            videoIds.firstOrNull()?.let { first ->
                tracks.byId(first)?.thumbnailUrl?.let { playlists.setCoverIfMissing(playlistId, it) }
            }
            added
        }

    suspend fun removeFromPlaylist(playlistId: Long, videoId: String) = withContext(Dispatchers.IO) {
        playlists.removeTrack(playlistId, videoId)
    }

    suspend fun renamePlaylist(id: Long, title: String) = withContext(Dispatchers.IO) {
        playlists.rename(id, title.trim())
    }

    suspend fun deletePlaylist(id: Long) = withContext(Dispatchers.IO) { playlists.delete(id) }

    suspend fun setPlaylistGenre(id: Long, genre: String?) = withContext(Dispatchers.IO) {
        playlists.setGenre(id, genre?.trim()?.takeIf { it.isNotEmpty() })
    }

    suspend fun reorderPlaylist(id: Long, videoIdsInOrder: List<String>) =
        withContext(Dispatchers.IO) { playlists.reorder(id, videoIdsInOrder) }

    suspend fun playlistTracksNow(id: Long): List<TrackEntity> = withContext(Dispatchers.IO) {
        playlists.tracks(id)
    }

    suspend fun playlistsContaining(videoId: String): List<Long> = withContext(Dispatchers.IO) {
        playlists.playlistsContaining(videoId)
    }

    // -- search history ----------------------------------------------------

    // -- what the assistant is allowed to read -----------------------------
    //
    // A deliberately small, named surface. The assistant reaches the library
    // through these and nothing else, so "what can it see" has an answer that
    // can be read off one screen rather than inferred from a class with fifty
    // methods on it.

    /**
     * The totals the assistant is allowed to quote, in one read.
     *
     * A map rather than a data class because these are figures to be recited
     * back, not fields to be computed on — a caller that wants to do arithmetic
     * with them is doing something this surface deliberately does not offer.
     */
    suspend fun counts(): Map<String, Int> = withContext(Dispatchers.IO) {
        mapOf(
            "tracks" to tracks.count(),
            "playlists" to playlists.summariesNow().size,
            "liked" to feedback.likedIds().size,
            "saved" to tracks.savedCount(),
            "downloaded" to tracks.downloadedCount()
        )
    }

    /** Playlists as they stand, for a question rather than for a screen. */
    suspend fun playlistsNow(): List<PlaylistSummary> = withContext(Dispatchers.IO) {
        playlists.summariesNow()
    }

    /** Liked tracks that have not been played in the last month. */
    suspend fun likedNotPlayedRecently(limit: Int = 10): List<TrackEntity> =
        withContext(Dispatchers.IO) {
            tracks.likedNotPlayedSince(System.currentTimeMillis() - THIRTY_DAYS_MS, limit)
        }

    /** Tracks played more than once, but not lately. */
    suspend fun rediscover(limit: Int = 10): List<TrackEntity> = withContext(Dispatchers.IO) {
        tracks.rediscover(System.currentTimeMillis() - THIRTY_DAYS_MS, limit = limit)
    }

    /** Everything downloaded for offline playback, right now. */
    suspend fun cachedNow(): List<TrackEntity> = withContext(Dispatchers.IO) { tracks.cached() }

    /** The pool the recommender scores over. */
    suspend fun recommendPool(limit: Int = 500): List<TrackEntity> =
        withContext(Dispatchers.IO) { tracks.recommendPool(limit) }

    /** Artist names the library actually knows, for matching a question against. */
    suspend fun knownArtists(limit: Int = 200): List<String> = withContext(Dispatchers.IO) {
        tracks.topArtists(limit).map { it.artist }
    }

    /** Tracks by one artist. */
    suspend fun byArtist(artist: String, limit: Int = 20): List<TrackEntity> =
        withContext(Dispatchers.IO) { tracks.byArtist(artist, limit) }

    suspend fun recordSearch(query: String) = withContext(Dispatchers.IO) {
        query.trim().takeIf { it.isNotEmpty() }?.let { searchHistory.record(it) }
    }

    fun recentSearches(limit: Int = 10): Flow<List<String>> = searchHistory.recentFlow(limit)

    suspend fun removeSearch(query: String) = withContext(Dispatchers.IO) {
        searchHistory.remove(query)
    }

    suspend fun clearSearches() = withContext(Dispatchers.IO) { searchHistory.clear() }

    // -- followed channels -------------------------------------------------

    fun followedChannels(): Flow<List<FollowedChannelEntity>> = channels.all()

    suspend fun follow(channelId: String, name: String) = withContext(Dispatchers.IO) {
        channels.follow(FollowedChannelEntity(channelId, name))
    }

    suspend fun unfollow(channelId: String) = withContext(Dispatchers.IO) {
        channels.unfollow(channelId)
    }

    // -- taste and recommendations ----------------------------------------

    /**
     * The user's taste as weights over genres and artists, from likes, plays,
     * skips and dislikes.
     *
     * One profile, shared by [recommended] and [similarTo] — previously each
     * built its own, and a dislike would have had to be taught to both.
     */
    suspend fun taste(): Taste = withContext(Dispatchers.IO) {
        val liked = feedback.likedNow()
        val disliked = feedback.dislikedNow()
        val tallies = history.playTallies(300)

        val genre = HashMap<String, Double>()
        val artist = HashMap<String, Double>()
        val exclude = HashSet<String>()
        val dislikedIds = HashSet<String>()

        fun bump(map: HashMap<String, Double>, key: String?, by: Double) {
            if (!key.isNullOrBlank()) map[key] = (map[key] ?: 0.0) + by
        }

        for (t in liked) {
            exclude += t.videoId
            bump(genre, t.genre, 2.0)
            bump(artist, t.artist, 2.0)
        }

        // A dislike outweighs a like: getting an unwanted track again is worse
        // than missing a wanted one, and the user had to go out of their way to
        // say so.
        for (t in disliked) {
            exclude += t.videoId
            dislikedIds += t.videoId
            bump(genre, t.genre, -3.0)
            bump(artist, t.artist, -3.0)
        }

        if (tallies.isNotEmpty()) {
            val rows = tracks.byIds(tallies.map { it.videoId }.distinct()).associateBy { it.videoId }
            for (tally in tallies) {
                val row = rows[tally.videoId] ?: continue
                // Log-damped so 40 plays of one song cannot drown out the rest of
                // the profile. Skips count against, at half the weight of a play.
                val weight = log2(1.0 + tally.plays) * (if (tally.skipped) -0.5 else 1.0)
                bump(genre, row.genre, weight)
                bump(artist, row.artist, weight)
            }
            tallies.take(10).forEach { exclude += it.videoId }
        }

        Taste(genre, artist, exclude, dislikedIds)
    }

    /**
     * "Made for you": library tracks weighted by how much the user likes their
     * genre and artist.
     *
     * Excludes the tracks that built the profile — a row that lists your five
     * favourite songs back at you is a mirror, not a suggestion — and anything
     * scoring at or below zero, so a disliked genre cannot be padded back in
     * because nothing better was left.
     */
    suspend fun recommended(limit: Int = 20): List<TrackEntity> = withContext(Dispatchers.IO) {
        val profile = taste()
        if (profile.genre.isEmpty() && profile.artist.isEmpty()) return@withContext emptyList()

        val wantedGenres = profile.genre.filterValues { it > 0 }.keys
        val wantedArtists = profile.artist.filterValues { it > 0 }.keys
        if (wantedGenres.isEmpty() && wantedArtists.isEmpty()) return@withContext emptyList()

        tracks.recommendPool(500)
            .asSequence()
            .filter { it.videoId !in profile.exclude && it.videoId !in profile.disliked }
            .filter { it.genre in wantedGenres || it.artist in wantedArtists }
            .map { it to score(it, profile) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
            .toList()
    }

    private fun score(track: TrackEntity, profile: Taste): Double =
        (profile.genre[track.genre] ?: 0.0) + (profile.artist[track.artist] ?: 0.0)

    /**
     * What to play after a track, for autoplay, radio and the "More like this" pane.
     *
     * When the seed has a genre, every pick shares it: the library first (same
     * artist only breaks ties), then YouTube's related list filtered to the
     * genre, then a plain genre search. Without a genre it falls back to the
     * artist and the general taste profile, as before.
     */
    suspend fun similarTo(
        seed: TrackEntity,
        exclude: Set<String> = emptySet(),
        limit: Int = 10
    ): List<TrackEntity> = withContext(Dispatchers.IO) {
        val stored = tracks.byId(seed.videoId)
        val genre = stored?.genre ?: seed.genre ?: inferGenre(seed.title, seed.artist)
        val skip = HashSet(exclude).apply { add(seed.videoId) }
        val profile = taste()
        val plays = history.playCounts().associate { it.videoId to it.plays }

        val picked = LinkedHashMap<String, TrackEntity>()
        fun take(candidates: List<TrackEntity>) {
            for (t in candidates) {
                if (picked.size >= limit) return
                if (t.videoId in skip || t.videoId in picked || t.videoId in profile.disliked) continue
                if (genre != null && t.genre != genre) continue
                picked[t.videoId] = t
            }
        }

        if (genre != null || !seed.artist.isNullOrBlank()) {
            tracks.recommendPool(500)
                .asSequence()
                .filter { it.videoId !in skip && it.videoId !in profile.disliked }
                .filter { if (genre != null) it.genre == genre else it.artist == seed.artist }
                .map { candidate ->
                    candidate to
                        (if (candidate.artist != null && candidate.artist == seed.artist) 2.0 else 0.0) +
                        log2(1.0 + (plays[candidate.videoId] ?: 0)) +
                        max(-3.0, (profile.genre[candidate.genre] ?: 0.0) * 0.4) +
                        max(-3.0, (profile.artist[candidate.artist] ?: 0.0) * 0.4)
                }
                // Same genre is already the bar; only a net dislike drops a candidate.
                .filter { it.second >= 0 }
                .sortedByDescending { it.second }
                .take(limit)
                .forEach { picked[it.first.videoId] = it.first }
        }

        // No genre: the general profile is the best guess left.
        if (genre == null && picked.size < limit) take(recommended(limit * 2))
        if (picked.size >= limit) return@withContext picked.values.take(limit)

        // Nothing local left that fits. Ask YouTube what follows this track,
        // keeping only what the tagger files under the same genre.
        val related = runCatching { extractor.related(seed.videoId, limit * 3) }
            .getOrDefault(emptyList())
        if (related.isNotEmpty()) take(remember(related, genre))

        // Still short: search the genre itself, the way "find more" does — and
        // tag what comes back untagged, since the search asked for this genre.
        if (genre != null && picked.size < limit) {
            val found = runCatching { extractor.search("$genre music", SearchKind.SONGS, limit * 2).hits }
                .getOrDefault(emptyList())
            if (found.isNotEmpty()) {
                val saved = remember(found, genre).map { t ->
                    if (t.genre == null) { tracks.tagIfUntagged(t.videoId, genre); t.copy(genre = genre) } else t
                }
                take(saved)
            }
        }
        picked.values.take(limit)
    }

    companion object {
        /**
         * Two reports of the same track starting inside this window are the same
         * play. Long enough to cover a controller reconnecting and re-announcing
         * the current item, short enough that a genuine replay still counts.
         */
        private const val PLAY_DEBOUNCE_MS = 20_000L

        /** Far enough in to be worth resuming. */
        private const val RESUME_MIN_MS = 60_000L

        /** Close enough to the end that resuming would be pointless. */
        private const val RESUME_TAIL_MS = 30_000L

        /** What "not lately" means for the assistant's questions. */
        private const val THIRTY_DAYS_MS = 30L * 24 * 60 * 60 * 1000

        private fun log2(value: Double): Double = ln(value) / ln(2.0)
    }
}

data class AlbumSummary(
    val name: String,
    val artist: String?,
    val count: Int,
    val coverUrl: String?
)

/**
 * Weights over genres and artists, plus the ids that must not be recommended.
 *
 * [exclude] is what formed the profile — recommending it back is a mirror.
 * [disliked] is never served at all, however well it otherwise scores.
 */
data class Taste(
    val genre: Map<String, Double>,
    val artist: Map<String, Double>,
    val exclude: Set<String>,
    val disliked: Set<String>
)
