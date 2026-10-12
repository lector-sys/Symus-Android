package io.github.lectorsys.symus.media

import android.net.Uri
import androidx.core.net.toUri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import io.github.lectorsys.symus.data.TrackEntity

/**
 * The URI scheme the player queues.
 *
 * A queued item cannot carry a googlevideo URL: those expire in hours, and a
 * restored queue full of them would be a queue of 403s. So the player holds
 * `symus://<videoId>` and the real URL is resolved at the moment the bytes are
 * needed — see [SymusResolver]. It also means a track that was downloaded after
 * being queued plays from disk without the queue knowing anything changed.
 */
const val SYMUS_SCHEME = "symus"

private const val EXTRA_DURATION = "symus.durationSec"
private const val EXTRA_GENRE = "symus.genre"
private const val EXTRA_OFFLINE = "symus.offline"
private const val EXTRA_PODCAST = "symus.podcast"

fun streamUri(videoId: String): Uri = "$SYMUS_SCHEME://$videoId".toUri()

/**
 * The player's view of a track.
 *
 * Title, artist and artwork go in the metadata because that is what the media
 * notification and the lock screen read. Duration and the rest ride in extras:
 * the queue shows a running time before the player has loaded anything, and
 * asking the database per row while scrolling would not.
 */
fun TrackEntity.toMediaItem(): MediaItem = MediaItem.Builder()
    .setMediaId(videoId)
    .setUri(streamUri(videoId))
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist ?: "Unknown artist")
            .setAlbumTitle(album)
            .setArtworkUri(thumbnailUrl?.toUri())
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .setExtras(
                Bundle().apply {
                    putLong(EXTRA_DURATION, duration)
                    putString(EXTRA_GENRE, genre)
                    putBoolean(EXTRA_OFFLINE, cachedPath != null)
                    putBoolean(EXTRA_PODCAST, contentType == "podcast")
                }
            )
            .build()
    )
    .build()

/** One row of the queue, as the UI needs it, with no database round trip. */
data class QueueItem(
    val videoId: String,
    val title: String,
    val artist: String?,
    val artworkUrl: String?,
    val durationSec: Long,
    val genre: String?,
    val offline: Boolean,
    val podcast: Boolean
)

fun MediaItem.toQueueItem(): QueueItem {
    val extras = mediaMetadata.extras
    return QueueItem(
        videoId = mediaId,
        title = mediaMetadata.title?.toString() ?: "Unknown title",
        artist = mediaMetadata.artist?.toString(),
        artworkUrl = mediaMetadata.artworkUri?.toString(),
        durationSec = extras?.getLong(EXTRA_DURATION) ?: 0L,
        genre = extras?.getString(EXTRA_GENRE),
        offline = extras?.getBoolean(EXTRA_OFFLINE) ?: false,
        podcast = extras?.getBoolean(EXTRA_PODCAST) ?: false
    )
}

/** The videoId a queued item stands for, or null if it is not one of ours. */
fun MediaItem.videoId(): String? = mediaId.takeIf { it.isNotEmpty() }
