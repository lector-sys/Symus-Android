package io.github.lectorsys.symus.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * The library schema, mirroring the desktop app's Prisma models.
 *
 * One deliberate difference: the desktop rows carry an autoincrement id and a
 * unique videoId, and everything else foreign-keys the id. Here videoId *is*
 * the key. YouTube already assigned every track a stable unique id, and the
 * second one only existed to satisfy an ORM — carrying both across would mean
 * every join and every insert juggling two identities for one thing.
 */
@Entity(tableName = "tracks", indices = [Index("artist"), Index("album"), Index("genre")])
data class TrackEntity(
    @PrimaryKey val videoId: String,
    val title: String,
    val artist: String? = null,
    /** Grouped into album pages when present. YouTube rarely publishes it. */
    val album: String? = null,
    val thumbnailUrl: String? = null,
    /** Seconds. 0 means livestream or unstarted premiere — never playable. */
    val duration: Long = 0,
    /** Free-text tag: inferred on add, editable by hand, never overwritten once set. */
    val genre: String? = null,
    /**
     * "podcast" for talk uploads. Null is music, which is every row that existed
     * before the Podcasts tab did. Read by the recommenders, which is the only
     * reason the column earns its place.
     */
    val contentType: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
    /** Absolute path once the audio has been cached for offline use. */
    val cachedPath: String? = null,
    val cachedBytes: Long? = null
)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val coverUrl: String? = null,
    val genre: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "playlist_tracks",
    primaryKeys = ["playlistId", "videoId"],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["videoId"],
            childColumns = ["videoId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("videoId"), Index(value = ["playlistId", "position"])]
)
data class PlaylistTrackEntity(
    val playlistId: Long,
    val videoId: String,
    val position: Int
)

@Entity(
    tableName = "likes",
    foreignKeys = [ForeignKey(
        entity = TrackEntity::class,
        parentColumns = ["videoId"],
        childColumns = ["videoId"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class LikeEntity(
    @PrimaryKey val videoId: String,
    val likedAt: Long = System.currentTimeMillis()
)

/**
 * Thumbs-down, deliberately not "the absence of a like".
 *
 * It outweighs a like in the taste profile — getting an unwanted track again is
 * worse than missing a wanted one, and the user had to go out of their way to
 * say so — and a disliked track is never served by autoplay however well it
 * otherwise scores.
 */
@Entity(
    tableName = "dislikes",
    foreignKeys = [ForeignKey(
        entity = TrackEntity::class,
        parentColumns = ["videoId"],
        childColumns = ["videoId"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class DislikeEntity(
    @PrimaryKey val videoId: String,
    val dislikedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "history",
    foreignKeys = [ForeignKey(
        entity = TrackEntity::class,
        parentColumns = ["videoId"],
        childColumns = ["videoId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("videoId"), Index("playedAt")]
)
data class HistoryEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val videoId: String,
    val playedAt: Long = System.currentTimeMillis(),
    /** Set on the latest play when the user skips it. Counts against the profile. */
    val skipped: Boolean = false
)

@Entity(
    tableName = "lyrics",
    foreignKeys = [ForeignKey(
        entity = TrackEntity::class,
        parentColumns = ["videoId"],
        childColumns = ["videoId"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class LyricsEntity(
    @PrimaryKey val videoId: String,
    /** LRC text when the source had timings. */
    val synced: String? = null,
    val plain: String? = null,
    /** Both null means "looked and found nothing", which is cached too, but expires. */
    val fetchedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "search_queries")
data class SearchQueryEntity(
    @PrimaryKey val query: String,
    val at: Long = System.currentTimeMillis()
)

@Entity(tableName = "settings")
data class SettingEntity(
    @PrimaryKey val key: String,
    val value: String
)

/**
 * A YouTube channel followed for the Podcasts tab.
 *
 * Keyed by channel id rather than name: channels rename themselves, and two
 * channels can share a display name.
 */
@Entity(tableName = "followed_channels")
data class FollowedChannelEntity(
    @PrimaryKey val channelId: String,
    val name: String,
    val followedAt: Long = System.currentTimeMillis()
)
