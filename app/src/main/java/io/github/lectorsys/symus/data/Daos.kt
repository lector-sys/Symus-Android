package io.github.lectorsys.symus.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {
    @Upsert
    suspend fun upsert(tracks: List<TrackEntity>)

    /**
     * Fills in a genre only where there is none.
     *
     * An inferred guess must never overwrite a tag the user set by hand, and an
     * upsert of the whole row would do exactly that on the next search that
     * happens to return the same video.
     */
    @Query("UPDATE tracks SET genre = :genre WHERE videoId = :videoId AND genre IS NULL")
    suspend fun tagIfUntagged(videoId: String, genre: String)

    /** Tagging one track tags the rest by that artist, which is how coverage grows. */
    @Query("UPDATE tracks SET genre = :genre WHERE artist = :artist AND genre IS NULL AND videoId != :exceptVideoId")
    suspend fun tagArtistUntagged(artist: String, genre: String, exceptVideoId: String)

    @Query("UPDATE tracks SET genre = :genre WHERE videoId = :videoId")
    suspend fun setGenre(videoId: String, genre: String?)

    @Query("SELECT * FROM tracks WHERE videoId = :videoId")
    suspend fun byId(videoId: String): TrackEntity?

    @Query("SELECT * FROM tracks WHERE genre = :genre AND contentType IS NULL ORDER BY addedAt DESC LIMIT :limit")
    suspend fun byGenre(genre: String, limit: Int = 200): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE artist = :artist ORDER BY addedAt DESC LIMIT :limit")
    suspend fun byArtist(artist: String, limit: Int = 60): List<TrackEntity>

    @Query("SELECT genre AS genre, COUNT(*) AS count FROM tracks WHERE genre IS NOT NULL GROUP BY genre ORDER BY count DESC")
    suspend fun genres(): List<GenreCount>

    @Query("SELECT COUNT(*) FROM tracks")
    suspend fun count(): Int

    @Query("SELECT * FROM tracks ORDER BY addedAt DESC LIMIT :limit")
    fun recentlyAdded(limit: Int = 50): Flow<List<TrackEntity>>
}

data class GenreCount(val genre: String, val count: Int)

data class ArtistPlays(val artist: String, val plays: Int)

data class PlayedTrack(val videoId: String, val plays: Int)

@Dao
interface HistoryDao {
    @Insert
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

    @Query("DELETE FROM history")
    suspend fun clear()
}

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
    val cachedPath: String?,
    val cachedBytes: Long?,
    val plays: Int
)

@Dao
interface FeedbackDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun like(like: LikeEntity)

    @Query("DELETE FROM likes WHERE videoId = :videoId")
    suspend fun unlike(videoId: String)

    @Query("SELECT t.* FROM tracks t JOIN likes l ON l.videoId = t.videoId ORDER BY l.likedAt DESC")
    fun liked(): Flow<List<TrackEntity>>

    @Query("SELECT videoId FROM likes")
    suspend fun likedIds(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun dislike(dislike: DislikeEntity)

    @Query("DELETE FROM dislikes WHERE videoId = :videoId")
    suspend fun undislike(videoId: String)

    @Query("SELECT videoId FROM dislikes")
    suspend fun dislikedIds(): List<String>
}

@Dao
interface PlaylistDao {
    @Insert
    suspend fun create(playlist: PlaylistEntity): Long

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE playlists SET title = :title WHERE id = :id")
    suspend fun rename(id: Long, title: String)

    @Query("SELECT * FROM playlists ORDER BY createdAt DESC")
    fun all(): Flow<List<PlaylistEntity>>

    @Upsert
    suspend fun addTracks(rows: List<PlaylistTrackEntity>)

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

    @Query("SELECT COALESCE(MAX(position), -1) FROM playlist_tracks WHERE playlistId = :playlistId")
    suspend fun lastPosition(playlistId: Long): Int
}

@Dao
interface SearchHistoryDao {
    @Upsert
    suspend fun record(query: SearchQueryEntity)

    @Query("SELECT query FROM search_queries ORDER BY at DESC LIMIT :limit")
    suspend fun recent(limit: Int = 10): List<String>

    @Query("DELETE FROM search_queries")
    suspend fun clear()
}

@Dao
interface SettingsDao {
    @Upsert
    suspend fun put(setting: SettingEntity)

    @Query("SELECT value FROM settings WHERE key = :key")
    suspend fun get(key: String): String?
}

@Dao
interface FollowedChannelDao {
    @Upsert
    suspend fun follow(channel: FollowedChannelEntity)

    @Query("DELETE FROM followed_channels WHERE channelId = :channelId")
    suspend fun unfollow(channelId: String)

    @Query("SELECT * FROM followed_channels ORDER BY followedAt DESC")
    fun all(): Flow<List<FollowedChannelEntity>>
}
