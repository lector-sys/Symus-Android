package io.github.lectorsys.symus.ui.sheets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.BookmarkRemove
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Sell
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material.icons.rounded.ThumbDownOffAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import io.github.lectorsys.symus.data.DownloadState
import io.github.lectorsys.symus.data.TrackEntity
import io.github.lectorsys.symus.library.KNOWN_GENRES
import io.github.lectorsys.symus.ui.SymusViewModel
import io.github.lectorsys.symus.ui.components.ArtworkSquare
import io.github.lectorsys.symus.ui.components.Pill
import io.github.lectorsys.symus.ui.theme.Space

/**
 * What the sheet was opened about.
 *
 * The context matters: a track opened from inside a playlist can be removed
 * from it, and one opened from the queue can be taken out of the queue. Neither
 * action should appear anywhere else, because neither would have anything to
 * act on.
 */
data class TrackSheetRequest(
    val track: TrackEntity,
    val playlistId: Long? = null,
    val queueIndex: Int? = null
)

/** Any screen can open the track sheet; only one screen has to host it. */
val LocalTrackSheet = staticCompositionLocalOf<(TrackSheetRequest) -> Unit> { {} }

/**
 * Hosts the track sheet, the playlist picker and the two dialogs they open.
 *
 * Kept in one place so a screen never has to hold sheet state, and so the
 * "add to playlist" flow can open a second sheet over the first without every
 * caller reimplementing it.
 */
@UnstableApi
@Composable
fun TrackSheetHost(
    model: SymusViewModel,
    onNavigate: (String) -> Unit,
    onRemoveFromPlaylist: (Long, TrackEntity) -> Unit,
    content: @Composable () -> Unit
) {
    var request by remember { mutableStateOf<TrackSheetRequest?>(null) }
    var pickPlaylistFor by remember { mutableStateOf<TrackEntity?>(null) }
    var taggingGenre by remember { mutableStateOf<TrackEntity?>(null) }

    CompositionLocalProvider(
        LocalTrackSheet provides { request = it }
    ) {
        content()
    }

    request?.let { open ->
        TrackActionsSheet(
            request = open,
            model = model,
            onDismiss = { request = null },
            onAddToPlaylist = {
                pickPlaylistFor = open.track
                request = null
            },
            onSetGenre = {
                taggingGenre = open.track
                request = null
            },
            onNavigate = {
                request = null
                onNavigate(it)
            },
            onRemoveFromPlaylist = {
                open.playlistId?.let { id -> onRemoveFromPlaylist(id, open.track) }
                request = null
            }
        )
    }

    pickPlaylistFor?.let { track ->
        PlaylistPickerSheet(
            model = model,
            tracks = listOf(track),
            onDismiss = { pickPlaylistFor = null }
        )
    }

    taggingGenre?.let { track ->
        GenreDialog(
            track = track,
            onDismiss = { taggingGenre = null },
            onChoose = { genre ->
                model.setGenre(track, genre)
                taggingGenre = null
            }
        )
    }
}

@UnstableApi
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrackActionsSheet(
    request: TrackSheetRequest,
    model: SymusViewModel,
    onDismiss: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onSetGenre: () -> Unit,
    onNavigate: (String) -> Unit,
    onRemoveFromPlaylist: () -> Unit
) {
    val track = request.track
    val liked by model.likedIds.collectAsStateWithLifecycle()
    val disliked by model.dislikedIds.collectAsStateWithLifecycle()
    val saved by model.savedIds.collectAsStateWithLifecycle()
    val queue by model.downloadQueue.collectAsStateWithLifecycle()

    val isLiked = track.videoId in liked
    val isDisliked = track.videoId in disliked
    val isSaved = track.videoId in saved
    val download = queue[track.videoId]
    val offline = track.cachedPath != null

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(Modifier.navigationBarsPadding()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Space.page)
                    .padding(bottom = Space.page),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ArtworkSquare(track.thumbnailUrl, track.videoId, 56.dp)
                Spacer(Modifier.width(Space.row))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = listOfNotNull(track.artist, track.genre).joinToString(" · ")
                            .ifBlank { "Unknown artist" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Column(Modifier.verticalScroll(rememberScrollState())) {
                SheetAction(Icons.Rounded.PlaylistPlay, "Play next") {
                    model.playNext(track); onDismiss()
                }
                SheetAction(Icons.Rounded.PlaylistAdd, "Add to queue") {
                    model.addToQueue(listOf(track)); onDismiss()
                }
                SheetAction(Icons.Rounded.Radio, "Start radio") {
                    model.startRadio(track); onDismiss()
                }
                SheetAction(
                    if (isLiked) Icons.Filled.Favorite else Icons.Rounded.FavoriteBorder,
                    if (isLiked) "Remove from liked songs" else "Like",
                    tinted = isLiked
                ) { model.toggleLike(track); onDismiss() }
                SheetAction(
                    if (isSaved) Icons.Rounded.BookmarkRemove else Icons.Rounded.BookmarkAdd,
                    if (isSaved) "Remove from library" else "Save to library"
                ) { model.toggleSaved(track); onDismiss() }
                SheetAction(Icons.Rounded.LibraryMusic, "Add to playlist", onClick = onAddToPlaylist)

                when {
                    offline -> SheetAction(Icons.Rounded.Delete, "Remove offline copy") {
                        model.removeDownload(track); onDismiss()
                    }
                    download?.state == DownloadState.FAILED -> {
                        SheetAction(Icons.Rounded.Download, "Retry download") {
                            model.retryDownload(track.videoId); onDismiss()
                        }
                        SheetAction(Icons.Rounded.Delete, "Cancel download") {
                            model.cancelDownload(track.videoId); onDismiss()
                        }
                    }
                    download != null -> SheetAction(Icons.Rounded.Delete, "Cancel download") {
                        model.cancelDownload(track.videoId); onDismiss()
                    }
                    else -> SheetAction(Icons.Rounded.Download, "Download for offline") {
                        model.download(track); onDismiss()
                    }
                }

                SheetAction(Icons.Rounded.Sell, track.genre?.let { "Genre: $it" } ?: "Set genre",
                    onClick = onSetGenre)

                if (!track.artist.isNullOrBlank()) {
                    SheetAction(Icons.Rounded.Person, "Go to ${track.artist}") {
                        onNavigate("artist/" + encode(track.artist))
                    }
                }
                if (!track.album.isNullOrBlank()) {
                    SheetAction(Icons.Rounded.Album, "Go to ${track.album}") {
                        onNavigate("album/" + encode(track.album) + "?artist=" + encode(track.artist))
                    }
                }

                SheetAction(
                    if (isDisliked) Icons.Rounded.ThumbDown else Icons.Rounded.ThumbDownOffAlt,
                    if (isDisliked) "Undo not interested" else "Not interested",
                    tinted = isDisliked
                ) { model.toggleDislike(track); onDismiss() }

                if (request.playlistId != null) {
                    SheetAction(
                        Icons.Rounded.Delete,
                        "Remove from this playlist",
                        onClick = onRemoveFromPlaylist
                    )
                }
                if (request.queueIndex != null) {
                    SheetAction(Icons.Rounded.Delete, "Remove from queue") {
                        model.playback.removeAt(request.queueIndex)
                        onDismiss()
                    }
                }
                Spacer(Modifier.height(Space.page))
            }
        }
    }
}

@Composable
fun SheetAction(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    tinted: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = Space.page),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (tinted) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(Space.page))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** Picks a playlist, or makes one, for a set of tracks. */
@UnstableApi
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistPickerSheet(
    model: SymusViewModel,
    tracks: List<TrackEntity>,
    onDismiss: () -> Unit
) {
    val playlists by model.playlists.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(Modifier.navigationBarsPadding()) {
            Text(
                text = if (tracks.size == 1) "Add to playlist"
                else "Add ${tracks.size} tracks to playlist",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = Space.page, vertical = Space.tight)
            )
            SheetAction(Icons.Rounded.PlaylistAdd, "New playlist") { creating = true }
            LazyColumn(Modifier.heightIn(max = 380.dp)) {
                items(playlists, key = { it.id }) { playlist ->
                    SheetAction(Icons.Rounded.LibraryMusic, playlist.title) {
                        model.addToPlaylist(playlist.id, tracks)
                        onDismiss()
                    }
                }
            }
            Spacer(Modifier.height(Space.page))
        }
    }

    if (creating) {
        NamePlaylistDialog(
            onDismiss = { creating = false },
            onConfirm = { title ->
                model.createPlaylist(title, tracks)
                creating = false
                onDismiss()
            }
        )
    }
}

@Composable
fun NamePlaylistDialog(
    initial: String = "",
    title: String = "New playlist",
    confirmLabel: String = "Create",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(80) },
                singleLine = true,
                label = { Text("Name") },
                keyboardOptions = KeyboardOptions.Default,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name.trim()) },
                enabled = name.isNotBlank()
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * Sets a track's genre.
 *
 * The known genres are offered as pills and the field takes anything, because
 * the tagger's list is not the limit of what music is — and tagging one track
 * carries the tag to that artist's other untagged tracks, which is what makes
 * the tap worth making.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GenreDialog(
    track: TrackEntity,
    onDismiss: () -> Unit,
    onChoose: (String?) -> Unit
) {
    var typed by remember { mutableStateOf(track.genre.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Genre") },
        text = {
            Column {
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it.take(60) },
                    singleLine = true,
                    label = { Text("Genre") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(Space.row))
                Text(
                    text = "Tagging this also tags other untagged tracks by " +
                        (track.artist ?: "this artist") + ".",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(Space.row))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    KNOWN_GENRES.forEach { genre ->
                        Pill(
                            label = genre,
                            selected = typed.equals(genre, ignoreCase = true),
                            onClick = { typed = genre }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onChoose(typed.trim().ifBlank { null }) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = { onChoose(null) }) { Text("Clear") }
        }
    )
}

/** Confirmation for anything that destroys something the user made. */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String = "Delete",
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                )
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Route segments carry names with spaces and slashes in them. */
internal fun encode(value: String?): String =
    java.net.URLEncoder.encode(value.orEmpty(), "UTF-8")
