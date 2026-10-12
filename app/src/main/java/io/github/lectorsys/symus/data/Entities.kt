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
@Entity(
    tableName = "tracks",
    indices = [Index("artist"), Index("album"), Index("genre"), Index("savedAt")]
)
data class TrackEntity(
    @PrimaryKey val videoId: String,
    val title: String,
    val artist: String? = null,
    /** Grouped into album pages when present. Only YouTube Music releases carry it. */
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
    /**
     * When the user put this in their library, or null for a row that only exists
     * because it came back from a search.
     *
     * The desktop app has no such column: there, every row a search touched was
     * "in the library", which made Library and "everything we have ever seen" the
     * same list. Search on a phone fills that table fast, so saving is explicit
     * here — liking, adding to a playlist, downloading, or the Save action all set
     * it, and nothing else does.
     */
    val savedAt: Long? = null,
    /**
     * Where to pick this track up again, in milliseconds. 0 means "from the start".
     *
     * Only written for something worth resuming — far enough in, and with enough
     * left to be worth returning to — so a track that merely started does not come
     * back mid-phrase.
     */
    val resumeMs: Long = 0,
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
    val createdAt: Long = System.currentTimeMillis(),
    /**
     * Where this playlist came from, kept separate from the local ids.
     *
     * A playlist imported from Spotify is still a local playlist: its tracks are
     * SYmuS videoIds and the user may edit them freely. These three columns only
     * record the provenance, so a re-import can find the same job again without
     * ever treating the provider's identity as the playback identity.
     */
    val sourceProvider: String? = null,
    val sourceId: String? = null,
    val sourceUrl: String? = null
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
    // Unique on (videoId, playedAt): one track cannot be played twice in the
    // same millisecond, and without that a restored backup counts every play
    // again. See MIGRATION_3_4.
    indices = [
        Index("videoId"),
        Index("playedAt"),
        Index(value = ["videoId", "playedAt"], unique = true)
    ]
)
data class HistoryEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val videoId: String,
    val playedAt: Long = System.currentTimeMillis(),
    /** Set on the latest play when the user skips it. Counts against the profile. */
    val skipped: Boolean = false,
    /**
     * Time the audio was actually audible, in milliseconds.
     *
     * Accumulated from wall-clock while the player reports isPlaying, never from
     * the playback position — dragging the scrubber across a ten-minute track
     * advances the position by ten minutes and this by nothing, which is the
     * whole point. Rows written before this column existed carry 0 and are
     * reported as "not measured" rather than as zero listening.
     */
    val listenedMs: Long = 0
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
    /** Repeat searches bump this, so the suggestion list favours what is used. */
    val uses: Int = 1,
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

/** QUEUED and RUNNING are live work; FAILED is kept so the UI can offer a retry. */
enum class DownloadState { QUEUED, RUNNING, FAILED }

/**
 * A download the user asked for but that has not finished.
 *
 * Completion is recorded on the track itself (cachedPath/cachedBytes), so a
 * finished download deletes its row rather than keeping a second source of
 * truth. The table exists for what a process death would otherwise lose: the
 * queue survives a restart and resumes from the bytes already on disk.
 */
@Entity(
    tableName = "downloads",
    foreignKeys = [ForeignKey(
        entity = TrackEntity::class,
        parentColumns = ["videoId"],
        childColumns = ["videoId"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class DownloadEntity(
    @PrimaryKey val videoId: String,
    val state: DownloadState = DownloadState.QUEUED,
    val bytes: Long = 0,
    /** Total size once the CDN has told us, else 0. */
    val total: Long = 0,
    val error: String? = null,
    val requestedAt: Long = System.currentTimeMillis()
)

// -- playlist import -------------------------------------------------------

/**
 * How far a match got, in the order the review screen groups them.
 *
 * The three middle values are deliberately distinct. An ISRC hit and a
 * title-that-looks-about-right are both "matched" to a naive importer, and
 * collapsing them is how a live bootleg ends up in a playlist where the studio
 * cut belongs. Nothing above UNCERTAIN is ever imported without the user
 * looking at it.
 */
enum class MatchState {
    /** Not looked up yet. Resumable work. */
    PENDING,
    /** Same recording, established by a shared ISRC. */
    EXACT,
    /** Title, artist and duration all agree closely. */
    LIKELY,
    /** Something plausible, but the version or the duration disagrees. */
    UNCERTAIN,
    /** Searched and found nothing worth offering. Kept, never discarded. */
    UNRESOLVED,
    /** The user said no to the suggestion. */
    REJECTED
}

/** The lifecycle of one import, persisted so it survives process death. */
enum class ImportState { PENDING, FETCHING, MATCHING, REVIEW, IMPORTING, DONE, FAILED, CANCELLED }

/**
 * One import of one source playlist.
 *
 * Persisted rather than held in memory because every interesting failure is a
 * slow one: a thousand-track playlist, a rate limit halfway through, the
 * process dying while the screen is off. `nextPageToken` and the per-item
 * states together are what make a retry resume instead of restart.
 *
 * Unique on (provider, sourceId) so re-importing the same playlist finds this
 * row again rather than creating a second one — which is what makes the whole
 * thing idempotent.
 */
@Entity(
    tableName = "import_jobs",
    indices = [Index(value = ["provider", "sourceId"], unique = true)]
)
data class ImportJobEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val provider: String,
    val sourceId: String,
    val sourceUrl: String? = null,
    val title: String,
    val owner: String? = null,
    val artworkUrl: String? = null,
    val state: ImportState = ImportState.PENDING,
    /** What the provider says the playlist holds; 0 until it has told us. */
    val totalTracks: Int = 0,
    /** How many have actually been pulled down. Resume reads this. */
    val fetchedTracks: Int = 0,
    /** Provider cursor for the next page, null once fully fetched. */
    val nextPageToken: String? = null,
    /** The local playlist this produced, once the user committed it. */
    val playlistId: Long? = null,
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * One track as the source described it, plus whatever SYmuS could match it to.
 *
 * The source's own identifiers live here and nowhere else — `sourceTrackId` and
 * `isrc` never travel into the tracks table, which is keyed by videoId and
 * knows nothing about Spotify. Keeping them apart is what lets an unresolved
 * entry stay repairable for months without polluting the library.
 *
 * `position` is the source's running order including legitimate repeats, so a
 * playlist that genuinely lists the same song twice imports it twice.
 */
@Entity(
    tableName = "import_items",
    foreignKeys = [ForeignKey(
        entity = ImportJobEntity::class,
        parentColumns = ["id"],
        childColumns = ["jobId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("jobId"), Index(value = ["jobId", "position"], unique = true)]
)
data class ImportItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val jobId: Long,
    val position: Int,
    val sourceTrackId: String? = null,
    /** The one identifier that means "the same recording" across platforms. */
    val isrc: String? = null,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val durationSec: Long = 0,
    /** live / remix / instrumental / acoustic / karaoke, or null for a studio cut. */
    val version: String? = null,
    val explicit: Boolean? = null,
    val matchedVideoId: String? = null,
    /** 0..1. Drives which bucket the review screen files this under. */
    val confidence: Double = 0.0,
    val matchState: MatchState = MatchState.PENDING,
    /**
     * The user chose this match, or cleared it, by hand.
     *
     * Re-importing re-fetches the source but leaves these alone: a correction
     * made once should not have to be made again every time the playlist is
     * refreshed.
     */
    val userEdited: Boolean = false
)

// -- generated themes ------------------------------------------------------

/**
 * A palette the user made from one of their own images.
 *
 * The image is copied into app storage and referenced by path; `seed` is the
 * colour the palette was derived from, kept so a theme can be regenerated
 * without re-reading the bitmap. Deleting the row deletes the file with it.
 */
@Entity(tableName = "themes")
data class ThemeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val imagePath: String? = null,
    /** ARGB of the extracted seed colour. */
    val seed: Int,
    /** Manual accent override, ARGB, or null to use the seed's. */
    val accent: Int? = null,
    /** Draw the image behind the app, rather than using it only for colour. */
    val useWallpaper: Boolean = false,
    /** 0..1 scrim over the wallpaper, so text stays readable on a busy photo. */
    val dim: Float = 0.55f,
    /** 0..1 blur radius fraction. Ignored below API 31, which has no RenderEffect. */
    val blur: Float = 0.35f,
    /** Where the crop was centred, 0..1 in image space. */
    val focusX: Float = 0.5f,
    val focusY: Float = 0.5f,
    val createdAt: Long = System.currentTimeMillis()
)
