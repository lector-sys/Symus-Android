package io.github.lectorsys.symus.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import io.github.lectorsys.symus.data.TrackEntity
import io.github.lectorsys.symus.data.movedTo
import io.github.lectorsys.symus.ui.SymusViewModel
import io.github.lectorsys.symus.ui.components.Artwork
import io.github.lectorsys.symus.ui.components.EmptyState
import io.github.lectorsys.symus.ui.components.PlaceholderRows
import io.github.lectorsys.symus.ui.components.TrackRow
import io.github.lectorsys.symus.ui.components.formatDuration
import io.github.lectorsys.symus.ui.sheets.LocalTrackSheet
import io.github.lectorsys.symus.ui.sheets.TrackSheetRequest
import io.github.lectorsys.symus.ui.theme.Space

/**
 * Every list of tracks in the app.
 *
 * Liked songs, a playlist, a genre, an album, an artist and the downloads all
 * differ in where their rows come from and in nothing else — so they share one
 * screen. A second implementation of the same header and the same row is a
 * second place for the offline badge to go missing.
 */
@UnstableApi
@Composable
fun CollectionScreen(
    title: String,
    subtitle: String,
    coverUrl: String?,
    seed: String,
    tracks: List<TrackEntity>,
    loading: Boolean,
    model: SymusViewModel,
    onBack: () -> Unit,
    emptyTitle: String,
    emptyMessage: String,
    modifier: Modifier = Modifier,
    playlistId: Long? = null,
    onReorder: ((List<String>) -> Unit)? = null,
    titleActions: @Composable (() -> Unit)? = null,
    headerExtras: @Composable (ColumnScope.() -> Unit)? = null
) {
    val liked by model.likedIds.collectAsStateWithLifecycle()
    val playback by model.playbackState.collectAsStateWithLifecycle()
    val openSheet = LocalTrackSheet.current

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = Space.section)
    ) {
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(start = 4.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Rounded.ArrowBack, contentDescription = "Back")
                }
                Spacer(Modifier.weight(1f))
                titleActions?.invoke()
            }
        }

        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = Space.page)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Artwork(
                        url = coverUrl,
                        seed = seed,
                        modifier = Modifier.width(132.dp).aspectRatio(1f),
                        shape = RoundedCornerShape(14.dp)
                    )
                    Spacer(Modifier.width(Space.page))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(Space.page))
                // Scrolls rather than wraps. Three labelled buttons do not fit
                // across a phone at the default text size — "Save offline" broke
                // onto a second line and made its pill taller than the other
                // two, which read as a mistake rather than as an option — and at
                // the largest accessibility font sizes nothing would fit at all.
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(Space.tight)
                ) {
                    Button(
                        onClick = { model.play(tracks) },
                        enabled = tracks.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null, Modifier.size(20.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Play", maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = { model.shufflePlay(tracks) },
                        enabled = tracks.size > 1
                    ) {
                        Icon(Icons.Rounded.Shuffle, contentDescription = null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Shuffle", maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = { model.downloadAll(tracks) },
                        enabled = tracks.any { it.cachedPath == null }
                    ) {
                        Icon(Icons.Rounded.Download, contentDescription = null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Save offline", maxLines = 1)
                    }
                }
                headerExtras?.let {
                    Spacer(Modifier.height(Space.row))
                    it()
                }
                Spacer(Modifier.height(Space.row))
            }
        }

        if (loading && tracks.isEmpty()) {
            item { PlaceholderRows(count = 6) }
            return@LazyColumn
        }

        if (tracks.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Rounded.MusicNote,
                    title = emptyTitle,
                    message = emptyMessage
                )
            }
            return@LazyColumn
        }

        itemsIndexed(tracks, key = { _, track -> track.videoId }) { index, track ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                TrackRow(
                    modifier = Modifier.weight(1f),
                    title = track.title,
                    subtitle = listOfNotNull(
                        track.artist,
                        formatDuration(track.duration),
                        track.genre
                    ).joinToString(" · "),
                    artworkUrl = track.thumbnailUrl,
                    seed = track.videoId,
                    liked = track.videoId in liked,
                    offline = track.cachedPath != null,
                    playing = playback.current?.videoId == track.videoId,
                    onClick = { model.play(tracks, index) },
                    onMore = {
                        openSheet(TrackSheetRequest(track, playlistId = playlistId))
                    }
                )
                if (onReorder != null) {
                    Column {
                        IconButton(
                            onClick = { onReorder(tracks.moved(index, index - 1)) },
                            enabled = index > 0,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                Icons.Rounded.KeyboardArrowUp,
                                contentDescription = "Move ${track.title} up",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(
                            onClick = { onReorder(tracks.moved(index, index + 1)) },
                            enabled = index < tracks.lastIndex,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                Icons.Rounded.KeyboardArrowDown,
                                contentDescription = "Move ${track.title} down",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(Modifier.width(4.dp))
                }
            }
        }
    }
}

/** The running order with one row nudged, as the ids the database renumbers from. */
private fun List<TrackEntity>.moved(from: Int, to: Int): List<String> =
    map { it.videoId }.movedTo(from, to)
