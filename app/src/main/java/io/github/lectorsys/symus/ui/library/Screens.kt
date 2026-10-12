package io.github.lectorsys.symus.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import io.github.lectorsys.symus.data.DownloadState
import io.github.lectorsys.symus.data.TrackEntity
import io.github.lectorsys.symus.ui.SymusViewModel
import io.github.lectorsys.symus.ui.components.MediaCard
import io.github.lectorsys.symus.ui.components.Pill
import io.github.lectorsys.symus.ui.components.formatBytes
import io.github.lectorsys.symus.ui.sheets.ConfirmDialog
import io.github.lectorsys.symus.ui.sheets.NamePlaylistDialog
import io.github.lectorsys.symus.ui.components.tracksLabel
import io.github.lectorsys.symus.ui.theme.Space
import java.net.URLEncoder

@UnstableApi
@Composable
fun LikedScreen(
    model: SymusViewModel,
    onBack: () -> Unit,
    viewModel: CollectionViewModel = hiltViewModel()
) {
    LaunchedEffect(Unit) { viewModel.liked() }
    val tracks by viewModel.tracks.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    var sort by remember { mutableStateOf(Sort.RECENT) }
    val sorted = remember(tracks, sort) { tracks.inOrder(sort) }

    CollectionScreen(
        title = "Liked songs",
        subtitle = tracksLabel(tracks.size),
        coverUrl = sorted.firstOrNull()?.thumbnailUrl,
        seed = "liked",
        tracks = sorted,
        loading = loading,
        model = model,
        onBack = onBack,
        emptyTitle = "No liked songs yet",
        emptyMessage = "Tap the heart on anything you want to keep. Likes also steer " +
            "what turns up in Made for you.",
        headerExtras = { SortRow(current = sort, onPick = { sort = it }) }
    )
}

@UnstableApi
@Composable
fun SavedScreen(
    model: SymusViewModel,
    onBack: () -> Unit,
    viewModel: CollectionViewModel = hiltViewModel()
) {
    LaunchedEffect(Unit) { viewModel.saved() }
    val tracks by viewModel.tracks.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    var sort by remember { mutableStateOf(Sort.RECENT) }
    val sorted = remember(tracks, sort) { tracks.inOrder(sort) }

    CollectionScreen(
        title = "Saved tracks",
        subtitle = tracksLabel(tracks.size),
        coverUrl = sorted.firstOrNull()?.thumbnailUrl,
        seed = "saved",
        tracks = sorted,
        loading = loading,
        model = model,
        onBack = onBack,
        emptyTitle = "Nothing saved yet",
        emptyMessage = "Saving is explicit here: liking a track, adding it to a playlist " +
            "or downloading it puts it in your library.",
        headerExtras = {
            SortRow(current = sort, onPick = { sort = it })
        }
    )
}

private enum class Sort(val label: String) {
    RECENT("Recent"),
    TITLE("Title"),
    ARTIST("Artist"),
    LONGEST("Longest")
}

/**
 * The list in the chosen order.
 *
 * RECENT is the list untouched, because every query behind these screens
 * already returns it newest-first — re-sorting by a column the row happens to
 * carry would quietly disagree with the one the database ordered on (a liked
 * track has a like date, not a save date).
 */
private fun List<TrackEntity>.inOrder(sort: Sort): List<TrackEntity> = when (sort) {
    Sort.RECENT -> this
    Sort.TITLE -> sortedBy { it.title.lowercase() }
    // Unknown artists sort last rather than first: they are the least useful
    // rows to land on at the top of an alphabetical list.
    Sort.ARTIST -> sortedBy { (it.artist?.takeIf { name -> name.isNotBlank() } ?: "￿").lowercase() }
    Sort.LONGEST -> sortedByDescending { it.duration }
}

@Composable
private fun SortRow(current: Sort, onPick: (Sort) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(Sort.entries.size) { index ->
            val sort = Sort.entries[index]
            Pill(
                label = sort.label,
                selected = sort == current,
                onClick = { onPick(sort) }
            )
        }
    }
}

@UnstableApi
@Composable
fun HistoryScreen(
    model: SymusViewModel,
    onBack: () -> Unit,
    viewModel: CollectionViewModel = hiltViewModel()
) {
    LaunchedEffect(Unit) { viewModel.history() }
    val tracks by viewModel.tracks.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    var confirming by remember { mutableStateOf(false) }

    CollectionScreen(
        title = "Listening history",
        subtitle = "${tracksLabel(tracks.size)} · most recent first",
        coverUrl = tracks.firstOrNull()?.thumbnailUrl,
        seed = "history",
        tracks = tracks,
        loading = loading,
        model = model,
        onBack = onBack,
        emptyTitle = "Nothing played yet",
        emptyMessage = "What you listen to shows up here, and shapes what the app suggests.",
        titleActions = {
            IconButton(onClick = { confirming = true }) {
                Icon(Icons.Rounded.Delete, contentDescription = "Clear listening history")
            }
        }
    )

    if (confirming) {
        ConfirmDialog(
            title = "Clear history?",
            message = "This deletes every play and skip. Recommendations and your stats " +
                "are built from them, so both will reset.",
            confirmLabel = "Clear",
            onDismiss = { confirming = false },
            onConfirm = {
                model.clearHistory()
                confirming = false
            }
        )
    }
}

@UnstableApi
@Composable
fun GenreScreen(
    model: SymusViewModel,
    genre: String,
    onBack: () -> Unit,
    viewModel: CollectionViewModel = hiltViewModel()
) {
    LaunchedEffect(genre) { viewModel.genre(genre) }
    val tracks by viewModel.tracks.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()

    CollectionScreen(
        title = genre,
        subtitle = "${tracksLabel(tracks.size)} tagged $genre",
        coverUrl = tracks.firstOrNull()?.thumbnailUrl,
        seed = "genre-$genre",
        tracks = tracks,
        loading = loading,
        model = model,
        onBack = onBack,
        emptyTitle = "Nothing tagged $genre",
        emptyMessage = "Tag a track from its menu, or use Find more on the Moods screen " +
            "to pull some in from YouTube."
    )
}

@UnstableApi
@Composable
fun ArtistScreen(
    model: SymusViewModel,
    artist: String,
    onBack: () -> Unit,
    onNavigate: (String) -> Unit,
    viewModel: CollectionViewModel = hiltViewModel()
) {
    LaunchedEffect(artist) { viewModel.artist(artist) }
    val tracks by viewModel.tracks.collectAsStateWithLifecycle()
    val albums by viewModel.albums.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()

    CollectionScreen(
        title = artist,
        subtitle = buildString {
            append(tracksLabel(tracks.size))
            if (albums.isNotEmpty()) append(" · ${albums.size} releases")
        },
        coverUrl = tracks.firstOrNull()?.thumbnailUrl,
        seed = artist,
        tracks = tracks,
        loading = loading,
        model = model,
        onBack = onBack,
        emptyTitle = "Nothing by $artist yet",
        emptyMessage = "Search for them and play something — what you play is kept.",
        headerExtras = {
            if (albums.isNotEmpty()) {
                Text(
                    text = "Releases",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(Space.tight))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(Space.row)) {
                    items(albums.size) { index ->
                        val album = albums[index]
                        MediaCard(
                            title = album.name,
                            subtitle = tracksLabel(album.count),
                            artworkUrl = album.coverUrl,
                            seed = album.name,
                            width = 124.dp,
                            onClick = {
                                onNavigate(
                                    "album/${URLEncoder.encode(album.name, "UTF-8")}" +
                                        "?artist=${URLEncoder.encode(artist, "UTF-8")}"
                                )
                            }
                        )
                    }
                }
            } else {
                // Said plainly rather than left as an empty section: the absence
                // is a property of the source, not of this library.
                Text(
                    text = "No release names for this artist. YouTube only publishes an " +
                        "album name on YouTube Music release pages — open one from the " +
                        "Albums tab in search and its tracks get filed here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    )
}

@UnstableApi
@Composable
fun AlbumScreen(
    model: SymusViewModel,
    album: String,
    artist: String?,
    onBack: () -> Unit,
    viewModel: CollectionViewModel = hiltViewModel()
) {
    LaunchedEffect(album, artist) { viewModel.album(album, artist?.takeIf { it.isNotBlank() }) }
    val tracks by viewModel.tracks.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()

    CollectionScreen(
        title = album,
        subtitle = listOfNotNull(artist?.takeIf { it.isNotBlank() }, tracksLabel(tracks.size))
            .joinToString(" · "),
        coverUrl = tracks.firstOrNull()?.thumbnailUrl,
        seed = album,
        tracks = tracks,
        loading = loading,
        model = model,
        onBack = onBack,
        emptyTitle = "Nothing from $album",
        emptyMessage = "Open the release from the Albums tab in search to file its tracks here."
    )
}

@UnstableApi
@Composable
fun PlaylistScreen(
    model: SymusViewModel,
    playlistId: Long,
    onBack: () -> Unit,
    viewModel: CollectionViewModel = hiltViewModel()
) {
    LaunchedEffect(playlistId) { viewModel.playlist(playlistId) }
    val tracks by viewModel.tracks.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val playlist by viewModel.playlist.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    val title = playlist?.title ?: "Playlist"

    CollectionScreen(
        title = title,
        subtitle = buildString {
            append(tracksLabel(tracks.size))
            playlist?.genre?.let { append(" · $it") }
        },
        coverUrl = playlist?.coverUrl ?: tracks.firstOrNull()?.thumbnailUrl,
        seed = "playlist-$playlistId",
        tracks = tracks,
        loading = loading,
        model = model,
        onBack = onBack,
        playlistId = playlistId,
        onReorder = { order -> model.reorderPlaylist(playlistId, order) },
        emptyTitle = "This playlist is empty",
        emptyMessage = "Add tracks from any list with the menu on a row.",
        titleActions = {
            IconButton(onClick = { renaming = true }) {
                Icon(
                    Icons.Rounded.DriveFileRenameOutline,
                    contentDescription = "Rename this playlist"
                )
            }
            IconButton(onClick = { deleting = true }) {
                Icon(Icons.Rounded.Delete, contentDescription = "Delete this playlist")
            }
        }
    )

    if (renaming) {
        NamePlaylistDialog(
            initial = title,
            title = "Rename playlist",
            confirmLabel = "Rename",
            onDismiss = { renaming = false },
            onConfirm = {
                model.renamePlaylist(playlistId, it)
                renaming = false
            }
        )
    }

    if (deleting) {
        ConfirmDialog(
            title = "Delete \"$title\"?",
            message = "The playlist goes; the tracks stay in your library.",
            onDismiss = { deleting = false },
            onConfirm = {
                model.deletePlaylist(playlistId, title)
                deleting = false
                onBack()
            }
        )
    }
}

/**
 * Downloads.
 *
 * Two lists in one screen on purpose: what is already on the device, and what is
 * still being fetched. Keeping the queue visible is the difference between "the
 * download is stuck" and "the download is third in line".
 */
@UnstableApi
@Composable
fun DownloadsScreen(
    model: SymusViewModel,
    onBack: () -> Unit,
    viewModel: CollectionViewModel = hiltViewModel()
) {
    LaunchedEffect(Unit) { viewModel.offline() }
    val tracks by viewModel.tracks.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val queue by model.downloadQueue.collectAsStateWithLifecycle()
    val progress by model.downloadProgress.collectAsStateWithLifecycle()
    val titles by viewModel.titles.collectAsStateWithLifecycle()
    var clearing by remember { mutableStateOf(false) }
    var sort by remember { mutableStateOf(Sort.RECENT) }

    LaunchedEffect(queue.keys) { viewModel.resolveTitles(queue.keys.toList()) }

    val bytes = tracks.sumOf { it.cachedBytes ?: 0L }
    val sorted = remember(tracks, sort) { tracks.inOrder(sort) }

    CollectionScreen(
        title = "Downloads",
        subtitle = "${tracksLabel(tracks.size)} · ${formatBytes(bytes)} on this device",
        coverUrl = sorted.firstOrNull()?.thumbnailUrl,
        seed = "downloads",
        tracks = sorted,
        loading = loading,
        model = model,
        onBack = onBack,
        emptyTitle = "Nothing downloaded",
        emptyMessage = "Download a track from its menu and it plays with no connection " +
            "at all — and without re-resolving a stream.",
        titleActions = {
            if (tracks.isNotEmpty()) {
                IconButton(onClick = { clearing = true }) {
                    Icon(Icons.Rounded.Delete, contentDescription = "Remove all downloads")
                }
            }
        },
        headerExtras = {
            if (tracks.size > 1) {
                SortRow(current = sort, onPick = { sort = it })
                Spacer(Modifier.height(Space.row))
            }
            val live = queue.values.sortedBy { it.requestedAt }
            if (live.isNotEmpty()) {
                Text(
                    text = "In the queue",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(Space.tight))
                live.forEach { row ->
                    val at = progress[row.videoId]
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
                    ) {
                        Row(
                            Modifier.padding(horizontal = Space.row, vertical = Space.tight),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = titles[row.videoId] ?: "Preparing…",
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.height(4.dp))
                                when (row.state) {
                                    DownloadState.FAILED -> Text(
                                        text = row.error ?: "Failed",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                    DownloadState.RUNNING -> {
                                        val fraction = at?.fraction ?: 0f
                                        if (fraction > 0f) {
                                            LinearProgressIndicator(
                                                progress = { fraction },
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        } else {
                                            LinearProgressIndicator(Modifier.fillMaxWidth())
                                        }
                                    }
                                    DownloadState.QUEUED -> Text(
                                        text = row.error ?: "Waiting",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            if (row.state == DownloadState.FAILED) {
                                IconButton(onClick = { model.retryDownload(row.videoId) }) {
                                    Icon(
                                        Icons.Rounded.Refresh,
                                        contentDescription = "Retry this download"
                                    )
                                }
                            }
                            IconButton(onClick = { model.cancelDownload(row.videoId) }) {
                                Icon(
                                    Icons.Rounded.Delete,
                                    contentDescription = "Cancel this download"
                                )
                            }
                        }
                    }
                }
            }
        }
    )

    if (clearing) {
        ConfirmDialog(
            title = "Remove all downloads?",
            message = "Frees ${formatBytes(bytes)}. The tracks stay in your library and " +
                "stream as usual.",
            confirmLabel = "Remove",
            onDismiss = { clearing = false },
            onConfirm = {
                model.removeAllDownloads()
                clearing = false
            }
        )
    }
}
