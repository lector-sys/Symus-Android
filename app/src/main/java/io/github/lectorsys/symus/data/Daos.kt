package io.github.lectorsys.symus.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {

    /**
     * New rows only. Everything a user owns — genre, savedAt, resumeMs, the
     * offline path, the album, the original addedAt — lives on an existing row,
     * and a whole-row upsert would overwrite all of it with defaults built from
     * a fresh search result.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(tracks: List<TrackEntity>)

    /**
     * The only fields a re-sighting may change: the ones YouTube owns.
     *
     * Artist, artwork and duration are COALESCEd rather than assigned, so a
     * sparser sighting of a track — a channel listing with no duration, a search
     * row with no uploader — can never blank out what a richer one already
     * recorded.
     */
    @Query(
        """
        UPDATE tracks SET
          title = :title,
          artist = COALESCE(:artist, artist),
          thumbnailUrl = COALESCE(:thumbnailUrl, thumbnailUrl),
          duration = CASE WHEN :duration > 0 THEN :duration ELSE duration END
        WHERE videoId = :videoId
        """
    )
    suspend fun updateMetadata(
        videoId: String,
        title: String,
        artist: String?,
        thumbnailUrl: String?,
        duration: Long
    )

    /**
     * Fills in a genre only where there is none.
     *
     * An inferred guess must never overwrite a tag the user set by hand, and an
     * upsert of the whole row would do exactly that on the next search that
     * happens to return the same video.
     */
    @Query("UPDATE tracks SET genre = :genre WHERE videoId = :videoId AND genre IS NULL")
    suspend fun tagIfUntagged(videoId: String, genre: String)

    /**
     * Insert-or-merge for a batch of sightings. One transaction, so a search that
     * is cancelled halfway cannot leave half its rows updated.
     *
     * This is the single write path for anything arriving from YouTube. Genre on
     * the incoming row is treated as a suggestion — applied only where the stored
     * row has none.
     */
    @Transaction
    suspend fun merge(tracks: List<TrackEntity>) {
        if (tracks.isEmpty()) return
        insertIfAbsent(tracks)
        for (t in tracks) {
            updateMetadata(t.videoId, t.title, t.artist, t.thumbnailUrl, t.duration)
            t.genre?.let { tagIfUntagged(t.videoId, it) }
        }
    }

    /** Tagging one track tags the rest by that artist, which is how coverage grows. */
    @Query("UPDATE tracks SET genre = :genre WHERE artist = :artist AND genre IS NULL AND videoId != :exceptVideoId")
    suspend fun tagArtistUntagged(artist: String, genre: String, exceptVideoId: String): Int

    @Query("UPDATE tracks SET genre = :genre WHERE videoId = :videoId")
    suspend fun setGenre(videoId: String, genre: String?)

    @Query("UPDATE tracks SET album = :album WHERE videoId = :videoId")
    suspend fun setAlbum(videoId: String, album: String?)

    @Query("UPDATE tracks SET contentType = :contentType WHERE videoId IN (:videoIds)")
    suspend fun setContentType(videoIds: List<String>, contentType: String?)

    @Query("UPDATE tracks SET savedAt = :at WHERE videoId = :videoId AND savedAt IS NULL")
    suspend fun markSaved(videoId: String, at: Long = System.currentTimeMillis())

    @Query("UPDATE tracks SET savedAt = NULL WHERE videoId = :videoId")
    suspend fun unsave(videoId: String)

    @Query("UPDATE tracks SET resumeMs = :ms WHERE videoId = :videoId")
    suspend fun setResume(videoId: String, ms: Long)

    @Query("UPDATE tracks SET cachedPath = :path, cachedBytes = :bytes WHERE videoId = :videoId")
    suspend fun setCached(videoId: String, path: String?, bytes: Long?)

    @Query("SELECT * FROM tracks WHERE videoId = :videoId")
    suspend fun byId(videoId: String): TrackEntity?

    @Query("SELECT * FROM tracks WHERE videoId = :videoId")
    fun byIdFlow(videoId: String): Flow<TrackEntity?>

    @Query("SELECT * FROM tracks WHERE videoId IN (:videoIds)")
    suspend fun byIds(videoIds: List<String>): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE genre = :genre AND contentType IS NULL ORDER BY addedAt DESC LIMIT :limit")
    suspend fun byGenre(genre: String, limit: Int = 200): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE artist = :artist ORDER BY addedAt DESC LIMIT :limit")
    suspend fun byArtist(artist: String, limit: Int = 200): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE album = :album AND (:artist IS NULL OR artist = :artist) ORDER BY addedAt ASC")
    suspend fun byAlbum(album: String, artist: String?): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE album IS NOT NULL ORDER BY album ASC, addedAt ASC")
    suspend fun withAlbums(): List<TrackEntity>

    @Query("SELECT genre AS genre, COUNT(*) AS count FROM tracks WHERE genre IS NOT NULL AND contentType IS NULL GROUP BY genre ORDER BY count DESC")
    suspend fun genres(): List<GenreCount>

    @Query("SELECT genre AS genre, COUNT(*) AS count FROM tracks WHERE genre IS NOT NULL AND contentType IS NULL GROUP BY genre ORDER BY count DESC")
    fun genresFlow(): Flow<List<GenreCount>>

    /**
     * Artists with the most tracks known, which is a different question from most
     * played — see HistoryDao.mostPlayedArtists. Genre stays sparse until someone
     * tags something, but artist is always present, so this is what Home can
     * offer while the genre map fills in.
     */
    @Query(
        """
        SELECT artist AS artist, COUNT(*) AS count FROM tracks
        WHERE artist IS NOT NULL AND artist != '' AND contentType IS NULL
        GROUP BY artist ORDER BY count DESC LIMIT :limit
        """
    )
    suspend fun topArtists(limit: Int = 16): List<ArtistCount>

    @Query("SELECT COUNT(*) FROM tracks")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM tracks WHERE genre IS NOT NULL")
    suspend fun taggedCount(): Int

    // -- the smart-playlist queries ---------------------------------------
    //
    // These four are the whole of "smart playlists", and the same four answer
    // the assistant's library questions. One set of queries rather than two
    // implementations of the same idea that can drift apart - and because they
    // are plain SQL over local rows, the answers are computed, never generated.

    /** Downloaded for offline and never once played. */
    @Query(
        """
        SELECT * FROM tracks
        WHERE cachedPath IS NOT NULL
          AND videoId NOT IN (SELECT videoId FROM history)
        ORDER BY savedAt DESC, addedAt DESC LIMIT :limit
        """
    )
    suspend fun downloadedNeverPlayed(limit: Int = 100): List<TrackEntity>

    /** Liked, but not played since the given instant - including never. */
    @Query(
        """
        SELECT t.* FROM tracks t
        JOIN likes l ON l.videoId = t.videoId
        WHERE COALESCE((SELECT MAX(playedAt) FROM history h WHERE h.videoId = t.videoId), 0) < :since
        ORDER BY l.likedAt DESC LIMIT :limit
        """
    )
    suspend fun likedNotPlayedSince(since: Long, limit: Int = 100): List<TrackEntity>

    /**
     * Played before, but not lately - the rediscovery list.
     *
     * Requires at least [minPlays] plays so it offers things that were once
     * liked enough to repeat, rather than everything tried once and dropped.
     */
    @Query(
        """
        SELECT t.* FROM tracks t
        JOIN history h ON h.videoId = t.videoId
        GROUP BY t.videoId
        HAVING COUNT(*) >= :minPlays AND MAX(h.playedAt) < :since
        ORDER BY COUNT(*) DESC LIMIT :limit
        """
    )
    suspend fun rediscover(since: Long, minPlays: Int = 2, limit: Int = 100): List<TrackEntity>

    /** In the library, never played. */
    @Query(
        """
        SELECT * FROM tracks
        WHERE savedAt IS NOT NULL
          AND videoId NOT IN (SELECT videoId FROM history)
        ORDER BY savedAt DESC LIMIT :limit
        """
    )
    suspend fun savedNeverPlayed(limit: Int = 100): List<TrackEntity>

    @Query("SELECT COUNT(*) FROM tracks WHERE genre = :genre")
    suspend fun countInGenre(genre: String): Int

    /**
     * Every row, for an export. Unfiltered on purpose: recommendPool() hides
     * podcasts and disliked tracks, which is right for a recommender and wrong
     * for a backup — a playlist referencing a track the export dropped cannot
     * be restored at all, the foreign key refuses it.
     */
    @Query("SELECT * FROM tracks ORDER BY addedAt ASC")
    suspend fun allTracks(): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE savedAt IS NOT NULL ORDER BY savedAt DESC")
    fun savedFlow(): Flow<List<TrackEntity>>

    @Query("SELECT COUNT(*) FROM tracks WHERE savedAt IS NOT NULL")
    fun savedCountFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM tracks WHERE savedAt IS NOT NULL")
    suspend fun savedCount(): Int

    @Query("SELECT COUNT(*) FROM tracks WHERE cachedPath IS NOT NULL")
    suspend fun downloadedCount(): Int

    @Query("SELECT videoId FROM tracks WHERE savedAt IS NOT NULL")
    fun savedIdsFlow(): Flow<List<String>>

    /**
     * Newest download first, so the Downloads screen's "Recent" ordering means
     * what it says. Alphabetical is a sort the screen offers, not the only one
     * it can show.
     */
    @Query("SELECT * FROM tracks WHERE cachedPath IS NOT NULL ORDER BY savedAt DESC, title ASC")
    fun cachedFlow(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE cachedPath IS NOT NULL")
    suspend fun cached(): List<TrackEntity>

    @Query("SELECT COUNT(*) AS count, COALESCE(SUM(cachedBytes), 0) AS bytes FROM tracks WHERE cachedPath IS NOT NULL")
    fun cacheSummaryFlow(): Flow<CacheSummary>

    /**
     * Candidates for the recommender: music only, never something the user
     * disliked.
     *
     * Capped because the scorer runs in memory — a personal library is thousands
     * of rows, and the cap is what keeps that true if it ever is not.
     */
    @Query(
        """
        SELECT * FROM tracks
        WHERE contentType IS NULL
          AND videoId NOT IN (SELECT videoId FROM dislikes)
        ORDER BY addedAt DESC LIMIT :limit
        """
    )
    suspend fun recommendPool(limit: Int = 500): List<TrackEntity>

    @Query(
        """
        SELECT * FROM tracks
        WHERE (title LIKE '%' || :term || '%' OR artist LIKE '%' || :term || '%')
        ORDER BY savedAt IS NULL, addedAt DESC LIMIT :limit
        """
    )
    suspend fun localSearch(term: String, limit: Int = 30): List<TrackEntity>

    /**
     * Drops rows nobody refers to.
     *
     * Every search writes its results here, so without this the table is an
     * unbounded cache that only grows. A row survives if the user did anything
     * with it — saved, liked, disliked, played, downloaded, or filed in a
     * playlist — and otherwise ages out.
     */
    @Query(
        """
        DELETE FROM tracks WHERE savedAt IS NULL AND cachedPath IS NULL AND addedAt < :before
          AND videoId NOT IN (SELECT videoId FROM likes)
          AND videoId NOT IN (SELECT videoId FROM dislikes)
          AND videoId NOT IN (SELECT videoId FROM history)
          AND videoId NOT IN (SELECT videoId FROM playlist_tracks)
          AND videoId NOT IN (SELECT videoId FROM downloads)
        """
    )
    suspend fun pruneUnreferenced(before: Long): Int
}

data class GenreCount(val genre: String, val count: Int)

data class ArtistCount(val artist: String, val count: Int)

data class ArtistPlays(val artist: String, val plays: Int)

data class PlayedTrack(val videoId: String, val plays: Int)

data class CacheSummary(val count: Int, val bytes: Long)

@Dao
interface HistoryDao {
    /**
     * IGNORE rather than the default abort: the table is unique on
     * (videoId, playedAt), and a duplicate is a replayed backup or a double
     * report, neither of which should throw and neither of which should count
     * twice.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun record(entry: HistoryEntryEntity)

    /**
     * Marks the most recent play of a track as skipped.
     *
     * Against the latest play specifically: skipping a song you have played
     * forty times says something about this listen, not about all of them.
     */
    @Query(
        """
        UPDATE history SET skipped = 1
        WHERE id = (SELECT id FROM history WHERE videoId = :videoId ORDER BY playedAt DESC LIMIT 1)
        """
    )
    suspend fun markSkipped(videoId: String)

    /** Distinct tracks, most recently played first. */
    @Query(
        """
        SELECT t.* FROM tracks t
        JOIN (SELECT videoId, MAX(playedAt) AS lastPlayed FROM history GROUP BY videoId) h
          ON h.videoId = t.videoId
        ORDER BY h.lastPlayed DESC LIMIT :limit
        """
    )
    suspend fun recentlyPlayed(limit: Int = 20): List<TrackEntity>

    @Query(
        """
        SELECT t.* FROM tracks t
        JOIN (SELECT videoId, MAX(playedAt) AS lastPlayed FROM history GROUP BY videoId) h
          ON h.videoId = t.videoId
        ORDER BY h.lastPlayed DESC LIMIT :limit
        """
    )
    fun recentlyPlayedFlow(limit: Int = 20): Flow<List<TrackEntity>>

    @Query(
        """
        SELECT t.*, COUNT(*) AS plays FROM tracks t
        JOIN history h ON h.videoId = t.videoId
        GROUP BY t.videoId ORDER BY plays DESC LIMIT :limit
        """
    )
    suspend fun mostPlayed(limit: Int = 20): List<TrackWithPlays>

    /**
     * Artists ranked by plays, which is a different question from how many of
     * their tracks are in the library — that one puts a forty-track import above
     * the artist on repeat all week.
     */
    @Query(
        """
        SELECT t.artist AS artist, COUNT(*) AS plays FROM history h
        JOIN tracks t ON t.videoId = h.videoId
        WHERE t.artist IS NOT NULL
        GROUP BY t.artist ORDER BY plays DESC LIMIT :limit
        """
    )
    suspend fun mostPlayedArtists(limit: Int = 12): List<ArtistPlays>

    @Query("SELECT videoId, COUNT(*) AS plays FROM history GROUP BY videoId")
    suspend fun playCounts(): List<PlayedTrack>

    @Query("SELECT videoId, COUNT(*) AS plays FROM history WHERE skipped = 1 GROUP BY videoId")
    suspend fun skipCounts(): List<PlayedTrack>

    /** (videoId, skipped) tallies — the shape the taste profile weighs. */
    @Query(
        """
        SELECT videoId, skipped, COUNT(*) AS plays FROM history
        GROUP BY videoId, skipped ORDER BY plays DESC LIMIT :limit
        """
    )
    suspend fun playTallies(limit: Int = 300): List<PlayTally>

    /**
     * Adds measured listening time to the most recent play of a track.
     *
     * Targets the latest row for the same reason markSkipped does: the time
     * being reported was spent on this listen. Incremental rather than a single
     * write at the end, so a process death mid-song loses at most one tick
     * instead of the whole play.
     */
    @Query(
        """
        UPDATE history SET listenedMs = listenedMs + :ms
        WHERE id = (SELECT id FROM history WHERE videoId = :videoId ORDER BY playedAt DESC LIMIT 1)
        """
    )
    suspend fun addListened(videoId: String, ms: Long)

    // -- statistics, all bounded by an explicit window ---------------------
    //
    // Every one of these takes [from, to) rather than a "last 7 days" flag. The
    // assistant has to be able to state the range it answered for, and a query
    // that computes its own window from the clock cannot be quoted back to the
    // user or tested against a fixed instant.

    /** Every row, for an export. Ordered so a restore replays in the same order. */
    @Query("SELECT * FROM history ORDER BY playedAt")
    suspend fun allEntries(): List<HistoryEntryEntity>

    @Query("SELECT COALESCE(SUM(listenedMs), 0) FROM history WHERE playedAt >= :from AND playedAt < :to")
    suspend fun listenedMsBetween(from: Long, to: Long): Long

    @Query("SELECT COUNT(*) FROM history WHERE playedAt >= :from AND playedAt < :to")
    suspend fun playsBetween(from: Long, to: Long): Int

    /**
     * Plays in the window that carry no measurement.
     *
     * Reported alongside every duration figure. These are rows from before the
     * column existed, or plays cut short by a crash; counting them as zero would
     * quietly understate a month of listening, and guessing a value for them
     * would be worse.
     */
    @Query(
        """
        SELECT COUNT(*) FROM history
        WHERE playedAt >= :from AND playedAt < :to AND listenedMs = 0 AND skipped = 0
        """
    )
    suspend fun unmeasuredPlaysBetween(from: Long, to: Long): Int

    @Query(
        """
        SELECT t.artist AS artist, COUNT(*) AS plays, COALESCE(SUM(h.listenedMs), 0) AS listenedMs
        FROM history h JOIN tracks t ON t.videoId = h.videoId
        WHERE h.playedAt >= :from AND h.playedAt < :to AND t.artist IS NOT NULL
        GROUP BY t.artist ORDER BY listenedMs DESC, plays DESC LIMIT :limit
        """
    )
    suspend fun topArtistsBetween(from: Long, to: Long, limit: Int): List<ArtistListening>

    @Query(
        """
        SELECT t.*, COUNT(*) AS plays FROM tracks t
        JOIN history h ON h.videoId = t.videoId
        WHERE h.playedAt >= :from AND h.playedAt < :to
        GROUP BY t.videoId ORDER BY plays DESC, MAX(h.playedAt) DESC LIMIT :limit
        """
    )
    suspend fun topTracksBetween(from: Long, to: Long, limit: Int): List<TrackWithPlays>

    @Query(
        """
        SELECT t.genre AS genre, COUNT(*) AS count FROM history h
        JOIN tracks t ON t.videoId = h.videoId
        WHERE h.playedAt >= :from AND h.playedAt < :to AND t.genre IS NOT NULL
        GROUP BY t.genre ORDER BY count DESC LIMIT :limit
        """
    )
    suspend fun topGenresBetween(from: Long, to: Long, limit: Int): List<GenreCount>

    /** The oldest play on record, so "incomplete history" can be stated precisely. */
    @Query("SELECT MIN(playedAt) FROM history")
    suspend fun earliestPlay(): Long?

    @Query("SELECT COALESCE(SUM(listenedMs), 0) FROM history")
    fun listenedMsFlow(): Flow<Long>

    @Query("SELECT COUNT(*) FROM history WHERE videoId = :videoId")
    suspend fun playCount(videoId: String): Int

    @Query("SELECT playedAt FROM history WHERE videoId = :videoId ORDER BY playedAt DESC LIMIT 1")
    suspend fun lastPlayedAt(videoId: String): Long?

    @Query("SELECT COUNT(*) FROM history")
    fun totalPlaysFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM history WHERE skipped = 1")
    fun skippedPlaysFlow(): Flow<Int>

    @Query("SELECT COUNT(DISTINCT videoId) FROM history")
    fun distinctPlayedFlow(): Flow<Int>

    /** Total seconds listened, counting each completed play as the track's length. */
    @Query(
        """
        SELECT COALESCE(SUM(t.duration), 0) FROM history h
        JOIN tracks t ON t.videoId = h.videoId WHERE h.skipped = 0
        """
    )
    fun secondsListenedFlow(): Flow<Long>

    @Query(
        """
        SELECT t.genre AS genre, COUNT(*) AS count FROM history h
        JOIN tracks t ON t.videoId = h.videoId
        WHERE t.genre IS NOT NULL
        GROUP BY t.genre ORDER BY count DESC LIMIT :limit
        """
    )
    fun genrePlaysFlow(limit: Int = 8): Flow<List<GenreCount>>

    @Query("DELETE FROM history")
    suspend fun clear()
}

data class PlayTally(val videoId: String, val skipped: Boolean, val plays: Int)

/** An artist with both figures: how often, and for how long. */
data class ArtistListening(val artist: String, val plays: Int, val listenedMs: Long)

data class TrackWithPlays(
    val videoId: String,
    val title: String,
    val artist: String?,
    val album: String?,
    val thumbnailUrl: String?,
    val duration: Long,
    val genre: String?,
    val contentType: String?,
    val addedAt: Long,
    val savedAt: Long?,
    val resumeMs: Long,
    val cachedPath: String?,
    val cachedBytes: Long?,
    val plays: Int
) {
    fun track(): TrackEntity = TrackEntity(
        videoId, title, artist, album, thumbnailUrl, duration, genre, contentType,
        addedAt, savedAt, resumeMs, cachedPath, cachedBytes
    )
}

@Dao
interface FeedbackDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun like(like: LikeEntity)

    @Query("DELETE FROM likes WHERE videoId = :videoId")
    suspend fun unlike(videoId: String)

    @Query("SELECT t.* FROM tracks t JOIN likes l ON l.videoId = t.videoId ORDER BY l.likedAt DESC")
    fun liked(): Flow<List<TrackEntity>>

    @Query("SELECT t.* FROM tracks t JOIN likes l ON l.videoId = t.videoId ORDER BY l.likedAt DESC")
    suspend fun likedNow(): List<TrackEntity>

    @Query("SELECT videoId FROM likes")
    suspend fun likedIds(): List<String>

    @Query("SELECT videoId FROM likes")
    fun likedIdsFlow(): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun dislike(dislike: DislikeEntity)

    @Query("DELETE FROM dislikes WHERE videoId = :videoId")
    suspend fun undislike(videoId: String)

    @Query("SELECT videoId FROM dislikes")
    suspend fun dislikedIds(): List<String>

    @Query("SELECT videoId FROM dislikes")
    fun dislikedIdsFlow(): Flow<List<String>>

    @Query("SELECT t.* FROM tracks t JOIN dislikes d ON d.videoId = t.videoId ORDER BY d.dislikedAt DESC")
    suspend fun dislikedNow(): List<TrackEntity>
}

@Dao
interface PlaylistDao {
    @Insert
    suspend fun create(playlist: PlaylistEntity): Long

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE playlists SET title = :title WHERE id = :id")
    suspend fun rename(id: Long, title: String)

    @Query("UPDATE playlists SET genre = :genre WHERE id = :id")
    suspend fun setGenre(id: Long, genre: String?)

    @Query("UPDATE playlists SET coverUrl = :coverUrl WHERE id = :id AND coverUrl IS NULL")
    suspend fun setCoverIfMissing(id: Long, coverUrl: String?)

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun byId(id: Long): PlaylistEntity?

    @Query("SELECT * FROM playlists WHERE id = :id")
    fun byIdFlow(id: Long): Flow<PlaylistEntity?>

    @Query(
        """
        SELECT p.id, p.title, p.coverUrl, p.genre, p.createdAt,
          (SELECT COUNT(*) FROM playlist_tracks pt WHERE pt.playlistId = p.id) AS count
        FROM playlists p ORDER BY p.createdAt DESC
        """
    )
    fun summaries(): Flow<List<PlaylistSummary>>

    @Query(
        """
        SELECT p.id, p.title, p.coverUrl, p.genre, p.createdAt,
          (SELECT COUNT(*) FROM playlist_tracks pt WHERE pt.playlistId = p.id) AS count
        FROM playlists p ORDER BY p.createdAt DESC
        """
    )
    suspend fun summariesNow(): List<PlaylistSummary>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addRows(rows: List<PlaylistTrackEntity>)

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId AND videoId = :videoId")
    suspend fun removeTrack(playlistId: Long, videoId: String)

    @Query(
        """
        SELECT t.* FROM tracks t
        JOIN playlist_tracks pt ON pt.videoId = t.videoId
        WHERE pt.playlistId = :playlistId ORDER BY pt.position ASC
        """
    )
    suspend fun tracks(playlistId: Long): List<TrackEntity>

    @Query(
        """
        SELECT t.* FROM tracks t
        JOIN playlist_tracks pt ON pt.videoId = t.videoId
        WHERE pt.playlistId = :playlistId ORDER BY pt.position ASC
        """
    )
    fun tracksFlow(playlistId: Long): Flow<List<TrackEntity>>

    @Query("SELECT videoId FROM playlist_tracks WHERE playlistId = :playlistId ORDER BY position ASC")
    suspend fun orderedIds(playlistId: Long): List<String>

    @Query("SELECT COALESCE(MAX(position), -1) FROM playlist_tracks WHERE playlistId = :playlistId")
    suspend fun lastPosition(playlistId: Long): Int

    @Query("SELECT videoId FROM playlist_tracks WHERE playlistId = :playlistId AND videoId IN (:videoIds)")
    suspend fun presentIn(playlistId: Long, videoIds: List<String>): List<String>

    @Query("UPDATE playlist_tracks SET position = :position WHERE playlistId = :playlistId AND videoId = :videoId")
    suspend fun setPosition(playlistId: Long, videoId: String, position: Int)

    @Query("SELECT playlistId FROM playlist_tracks WHERE videoId = :videoId")
    suspend fun playlistsContaining(videoId: String): List<Long>

    /**
     * Appends, skipping anything already there. (playlistId, videoId) is the
     * primary key, so a re-add would otherwise be a constraint error.
     *
     * One transaction: the position counter is read and written inside it, so two
     * simultaneous adds cannot both claim the same slot.
     */
    @Transaction
    suspend fun append(playlistId: Long, videoIds: List<String>): Int {
        if (videoIds.isEmpty()) return 0
        val present = presentIn(playlistId, videoIds).toSet()
        val fresh = videoIds.distinct().filter { it !in present }
        if (fresh.isEmpty()) return 0
        var position = lastPosition(playlistId) + 1
        addRows(fresh.map { PlaylistTrackEntity(playlistId, it, position++) })
        return fresh.size
    }

    /**
     * Writes a whole new running order.
     *
     * Renumbering from the supplied list rather than swapping two rows: a drag
     * that lands three places down is one move to the user and three renumbers
     * to the table, and rebuilding the order from the list the UI is already
     * holding cannot drift out of step with it.
     */
    @Transaction
    suspend fun reorder(playlistId: Long, videoIdsInOrder: List<String>) {
        videoIdsInOrder.forEachIndexed { index, videoId ->
            setPosition(playlistId, videoId, index)
        }
    }
}

data class PlaylistSummary(
    val id: Long,
    val title: String,
    val coverUrl: String?,
    val genre: String?,
    val createdAt: Long,
    val count: Int
)

@Dao
interface SearchHistoryDao {
    @Query("UPDATE search_queries SET uses = uses + 1, at = :at WHERE `query` = :query")
    suspend fun bump(query: String, at: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: SearchQueryEntity)

    /** Repeat searches move to the top and count, rather than replacing the row. */
    @Transaction
    suspend fun record(query: String) {
        val now = System.currentTimeMillis()
        if (bump(query, now) == 0) insert(SearchQueryEntity(query = query, at = now))
    }

    @Query("SELECT `query` FROM search_queries ORDER BY at DESC LIMIT :limit")
    fun recentFlow(limit: Int = 10): Flow<List<String>>

    @Query("SELECT `query` FROM search_queries ORDER BY at DESC LIMIT :limit")
    suspend fun recent(limit: Int = 10): List<String>

    @Query("DELETE FROM search_queries WHERE `query` = :query")
    suspend fun remove(query: String)

    @Query("DELETE FROM search_queries")
    suspend fun clear()
}

@Dao
interface SettingsDao {
    @Upsert
    suspend fun put(setting: SettingEntity)

    @Query("SELECT value FROM settings WHERE key = :key")
    suspend fun get(key: String): String?

    @Query("SELECT * FROM settings")
    fun all(): Flow<List<SettingEntity>>

    @Query("SELECT * FROM settings")
    suspend fun allNow(): List<SettingEntity>

    @Query("DELETE FROM settings WHERE key = :key")
    suspend fun remove(key: String)
}

@Dao
interface FollowedChannelDao {
    @Upsert
    suspend fun follow(channel: FollowedChannelEntity)

    @Query("DELETE FROM followed_channels WHERE channelId = :channelId")
    suspend fun unfollow(channelId: String)

    @Query("SELECT * FROM followed_channels ORDER BY followedAt DESC")
    fun all(): Flow<List<FollowedChannelEntity>>

    @Query("SELECT * FROM followed_channels ORDER BY followedAt DESC")
    suspend fun allNow(): List<FollowedChannelEntity>

    @Query("SELECT COUNT(*) FROM followed_channels WHERE channelId = :channelId")
    suspend fun isFollowing(channelId: String): Int
}

@Dao
interface DownloadDao {
    @Upsert
    suspend fun put(download: DownloadEntity)

    @Query("DELETE FROM downloads WHERE videoId = :videoId")
    suspend fun remove(videoId: String)

    @Query("SELECT * FROM downloads ORDER BY requestedAt ASC")
    fun all(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE state != 'FAILED' ORDER BY requestedAt ASC")
    suspend fun pending(): List<DownloadEntity>

    @Query("SELECT * FROM downloads WHERE videoId = :videoId")
    suspend fun byId(videoId: String): DownloadEntity?

    @Query("UPDATE downloads SET state = :state, error = :error WHERE videoId = :videoId")
    suspend fun setState(videoId: String, state: DownloadState, error: String?)

    @Query("UPDATE downloads SET bytes = :bytes, total = :total WHERE videoId = :videoId")
    suspend fun setProgress(videoId: String, bytes: Long, total: Long)

    /** Anything left RUNNING was interrupted by a process death, not by a failure. */
    @Query("UPDATE downloads SET state = 'QUEUED' WHERE state = 'RUNNING'")
    suspend fun requeueInterrupted()

    @Query("DELETE FROM downloads")
    suspend fun clear()
}

@Dao
interface LyricsDao {
    @Upsert
    suspend fun put(lyrics: LyricsEntity)

    @Query("SELECT * FROM lyrics WHERE videoId = :videoId")
    suspend fun byId(videoId: String): LyricsEntity?

    @Query("DELETE FROM lyrics")
    suspend fun clear()
}

// -- playlist import -------------------------------------------------------

/**
 * The import tables.
 *
 * Deliberately blunt: upserts keyed on natural identity, and updates that name
 * the one column they touch. An import is a long job that gets interrupted, and
 * every method here has to be safe to run again over rows it already wrote.
 */
@Dao
interface ImportDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertJob(job: ImportJobEntity): Long

    @Query("SELECT * FROM import_jobs WHERE provider = :provider AND sourceId = :sourceId")
    suspend fun findJob(provider: String, sourceId: String): ImportJobEntity?

    @Query("SELECT * FROM import_jobs WHERE id = :id")
    suspend fun job(id: Long): ImportJobEntity?

    @Query("SELECT * FROM import_jobs WHERE id = :id")
    fun jobFlow(id: Long): Flow<ImportJobEntity?>

    @Query("SELECT * FROM import_jobs ORDER BY updatedAt DESC")
    fun jobsFlow(): Flow<List<ImportJobEntity>>

    @Query("SELECT * FROM import_jobs ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun recentJobs(limit: Int = 20): List<ImportJobEntity>

    @Query("DELETE FROM import_jobs WHERE id = :id")
    suspend fun deleteJob(id: Long)

    @Query("UPDATE import_jobs SET state = :state, error = :error, updatedAt = :at WHERE id = :id")
    suspend fun setJobState(
        id: Long,
        state: ImportState,
        error: String?,
        at: Long = System.currentTimeMillis()
    )

    @Query(
        """
        UPDATE import_jobs
        SET totalTracks = :total, fetchedTracks = :fetched, nextPageToken = :token, updatedAt = :at
        WHERE id = :id
        """
    )
    suspend fun setJobProgress(
        id: Long,
        total: Int,
        fetched: Int,
        token: String?,
        at: Long = System.currentTimeMillis()
    )

    @Query("UPDATE import_jobs SET title = :title, updatedAt = :at WHERE id = :id")
    suspend fun renameJob(id: Long, title: String, at: Long = System.currentTimeMillis())

    @Query("UPDATE import_jobs SET playlistId = :playlistId, updatedAt = :at WHERE id = :id")
    suspend fun setJobPlaylist(id: Long, playlistId: Long, at: Long = System.currentTimeMillis())

    /**
     * IGNORE rather than REPLACE, and the unique (jobId, position) index is what
     * makes it work: re-fetching a page that was already stored is a no-op
     * instead of wiping the match the user just corrected on those rows.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertItems(items: List<ImportItemEntity>)

    @Query("SELECT * FROM import_items WHERE jobId = :jobId ORDER BY position")
    suspend fun items(jobId: Long): List<ImportItemEntity>

    @Query("SELECT * FROM import_items WHERE jobId = :jobId ORDER BY position")
    fun itemsFlow(jobId: Long): Flow<List<ImportItemEntity>>

    @Query("SELECT COUNT(*) FROM import_items WHERE jobId = :jobId")
    suspend fun itemCount(jobId: Long): Int

    /** The work a resumed match run still has to do. */
    @Query(
        """
        SELECT * FROM import_items
        WHERE jobId = :jobId AND matchState = 'PENDING'
        ORDER BY position LIMIT :limit
        """
    )
    suspend fun pendingItems(jobId: Long, limit: Int = 50): List<ImportItemEntity>

    @Query("SELECT COUNT(*) FROM import_items WHERE jobId = :jobId AND matchState = :state")
    suspend fun countInState(jobId: Long, state: MatchState): Int

    /** Never touches a row the user has edited. */
    @Query(
        """
        UPDATE import_items
        SET matchedVideoId = :videoId, confidence = :confidence, matchState = :state
        WHERE id = :id AND userEdited = 0
        """
    )
    suspend fun setMatch(id: Long, videoId: String?, confidence: Double, state: MatchState)

    /** The user's own decision, which later automatic runs must respect. */
    @Query(
        """
        UPDATE import_items
        SET matchedVideoId = :videoId, confidence = :confidence, matchState = :state, userEdited = 1
        WHERE id = :id
        """
    )
    suspend fun setUserMatch(id: Long, videoId: String?, confidence: Double, state: MatchState)

    /** Puts unresolved rows back to PENDING so a retry re-matches them. */
    @Query(
        """
        UPDATE import_items SET matchState = 'PENDING'
        WHERE jobId = :jobId AND userEdited = 0 AND matchState = 'UNRESOLVED'
        """
    )
    suspend fun requeueUnresolved(jobId: Long)
}

// -- generated themes ------------------------------------------------------

@Dao
interface ThemeDao {
    @Insert
    suspend fun insert(theme: ThemeEntity): Long

    @Update
    suspend fun update(theme: ThemeEntity)

    @Query("SELECT * FROM themes ORDER BY createdAt DESC")
    fun allFlow(): Flow<List<ThemeEntity>>

    @Query("SELECT * FROM themes ORDER BY createdAt DESC")
    suspend fun all(): List<ThemeEntity>

    @Query("SELECT * FROM themes WHERE id = :id")
    suspend fun byId(id: Long): ThemeEntity?

    @Query("SELECT * FROM themes WHERE id = :id")
    fun byIdFlow(id: Long): Flow<ThemeEntity?>

    @Query("UPDATE themes SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("DELETE FROM themes WHERE id = :id")
    suspend fun delete(id: Long)
}
