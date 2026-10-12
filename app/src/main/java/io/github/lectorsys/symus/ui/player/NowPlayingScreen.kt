package io.github.lectorsys.symus.ui.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.ClearAll
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import io.github.lectorsys.symus.data.DownloadEntity
import io.github.lectorsys.symus.data.DownloadState
import io.github.lectorsys.symus.data.TrackEntity
import io.github.lectorsys.symus.library.LyricsResult
import io.github.lectorsys.symus.media.DownloadProgress
import io.github.lectorsys.symus.media.PlaybackUiState
import io.github.lectorsys.symus.media.QueueItem
import io.github.lectorsys.symus.ui.SymusViewModel
import io.github.lectorsys.symus.ui.components.Artwork
import io.github.lectorsys.symus.ui.components.ArtworkSquare
import io.github.lectorsys.symus.ui.components.EmptyState
import io.github.lectorsys.symus.ui.components.ErrorState
import io.github.lectorsys.symus.ui.components.Pill
import io.github.lectorsys.symus.ui.components.TrackRow
import io.github.lectorsys.symus.ui.components.formatMillis
import io.github.lectorsys.symus.ui.sheets.LocalTrackSheet
import io.github.lectorsys.symus.ui.sheets.TrackSheetRequest
import io.github.lectorsys.symus.ui.theme.Space

private enum class Pane { NONE, QUEUE, LYRICS, SIMILAR }

/**
 * The full player.
 *
 * Artwork first and large, because that is the only part of a music screen
 * anybody looks at twice; everything else arranges itself under it in one
 * column. The queue and the lyrics share the space below the controls — both
 * are about the track that is playing, and showing either one at a time keeps
 * the artwork on screen rather than scrolling it away.
 */
@UnstableApi
@Composable
fun NowPlayingScreen(
    model: SymusViewModel,
    onClose: () -> Unit,
    onNavigate: (String) -> Unit,
    viewModel: NowPlayingViewModel = hiltViewModel()
) {
    val state by model.playbackState.collectAsStateWithLifecycle()
    val settings by model.settings.collectAsStateWithLifecycle()
    val track by viewModel.track.collectAsStateWithLifecycle()
    val liked by model.likedIds.collectAsStateWithLifecycle()
    val queueRows by model.downloadQueue.collectAsStateWithLifecycle()
    val progress by model.downloadProgress.collectAsStateWithLifecycle()
    val lyricsState by viewModel.lyricsState.collectAsStateWithLifecycle()
    val similar by viewModel.similar.collectAsStateWithLifecycle()

    val current = state.current
    val accent = rememberArtworkAccent(current?.artworkUrl, settings.artworkColors)
    val backdrop by animateColorAsState(accent, tween(600), label = "backdrop")
    var pane by remember { mutableStateOf(Pane.NONE) }
    var sleepOpen by remember { mutableStateOf(false) }
    val openSheet = LocalTrackSheet.current

    if (current == null) {
        // Reachable by pressing the mini player as the last track ends.
        Column(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .statusBarsPadding()
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Close player")
            }
            EmptyState(
                icon = Icons.Rounded.QueueMusic,
                title = "Nothing is playing",
                message = "Pick something from your library, or search for a song."
            )
        }
        return
    }

    LaunchedEffect(pane, current.videoId) {
        if (pane == Pane.LYRICS) viewModel.loadLyrics()
        // Whatever is already queued would only be suggested back.
        if (pane == Pane.SIMILAR) viewModel.loadSimilar(state.queue.map { it.videoId }.toSet())
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    // The artwork colour only at the top, fading into the app's
                    // own background: a full-bleed tint fights every control on
                    // the screen, and the gradient keeps contrast predictable
                    // where the text and buttons actually are.
                    listOf(
                        backdrop.copy(alpha = 0.95f),
                        backdrop.copy(alpha = 0.35f),
                        MaterialTheme.colorScheme.background
                    )
                )
            )
    ) {
        BoxWithConstraints {
            val wide = maxWidth > maxHeight
            val artworkEdge = if (wide) maxHeight * 0.62f else maxWidth - 96.dp
            // Measured once here: the constraints are not in scope further in.
            val fullArtwork = (maxWidth - 96.dp).coerceAtMost(420.dp)
            val compactArtwork = maxWidth * 0.34f

            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    // Applied to the content, not to the Box above it, so the
                    // backdrop still reaches the edge of the screen while the
                    // controls stay clear of a landscape camera cutout.
                    .windowInsetsPadding(
                        WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal)
                    )
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onClose) {
                        Icon(
                            Icons.Rounded.ArrowDropDown,
                            contentDescription = "Close player",
                            modifier = Modifier.size(30.dp)
                        )
                    }
                    Text(
                        text = if (state.shuffle) "Shuffling" else "Now playing",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center
                    )
                    IconButton(onClick = { sleepOpen = true }) {
                        Icon(
                            Icons.Rounded.Bedtime,
                            contentDescription = "Sleep timer",
                            tint = if (state.sleepAtMs != null || state.sleepAtTrackEnd) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                    IconButton(
                        onClick = { track?.let { openSheet(TrackSheetRequest(it)) } },
                        enabled = track != null
                    ) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = "Track actions")
                    }
                }

                if (wide) {
                    Row(Modifier.fillMaxSize()) {
                        Column(
                            modifier = Modifier.weight(1f).padding(horizontal = Space.page),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Artwork(
                                url = current.artworkUrl,
                                seed = current.videoId,
                                modifier = Modifier.size(artworkEdge),
                                shape = RoundedCornerShape(18.dp)
                            )
                        }
                        Column(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .padding(end = Space.page)
                        ) {
                            TrackHeading(
                                title = current.title,
                                artist = current.artist,
                                onArtist = {
                                    current.artist?.let {
                                        onNavigate("artist/${java.net.URLEncoder.encode(it, "UTF-8")}")
                                    }
                                }
                            )
                            SeekRow(
                                positionMs = state.positionMs,
                                durationMs = state.durationMs,
                                onSeek = model.playback::seekTo
                            )
                            Transport(state = state, model = model)
                            TrackActions(
                                current = current,
                                track = track,
                                model = model,
                                liked = liked,
                                downloads = queueRows,
                                progress = progress,
                                pane = pane,
                                onPane = { pane = it }
                            )
                            when (pane) {
                                Pane.NONE -> Spacer(Modifier.weight(1f))
                                Pane.QUEUE -> QueuePane(model, Modifier.weight(1f))
                                Pane.LYRICS -> LyricsPane(
                                    state = lyricsState,
                                    positionMs = state.positionMs,
                                    onRetry = { viewModel.loadLyrics(force = true) },
                                    modifier = Modifier.weight(1f)
                                )
                                Pane.SIMILAR -> SimilarPane(similar, track?.genre, model, Modifier.weight(1f))
                            }
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // The artwork gives up room the moment a pane opens: a
                        // queue squeezed into one visible row is not a queue.
                        val artwork = if (pane == Pane.NONE) fullArtwork else compactArtwork
                        if (pane == Pane.NONE) Spacer(Modifier.weight(0.4f))
                        Artwork(
                            url = current.artworkUrl,
                            seed = current.videoId,
                            modifier = Modifier.size(artwork),
                            shape = RoundedCornerShape(20.dp)
                        )
                        Spacer(
                            Modifier.height(if (pane == Pane.NONE) Space.section else Space.row)
                        )
                        TrackHeading(
                            title = current.title,
                            artist = current.artist,
                            onArtist = {
                                current.artist?.let {
                                    onNavigate("artist/${java.net.URLEncoder.encode(it, "UTF-8")}")
                                }
                            }
                        )

                        TrackActions(
                            current = current,
                            track = track,
                            model = model,
                            liked = liked,
                            downloads = queueRows,
                            progress = progress,
                            pane = pane,
                            onPane = { pane = it }
                        )

                        SeekRow(
                            positionMs = state.positionMs,
                            durationMs = state.durationMs,
                            onSeek = model.playback::seekTo
                        )
                        Transport(state = state, model = model)

                        when (pane) {
                            Pane.NONE -> Spacer(Modifier.weight(0.6f))
                            Pane.QUEUE -> QueuePane(
                                model = model,
                                modifier = Modifier.weight(1f, fill = true)
                            )
                            Pane.LYRICS -> LyricsPane(
                                state = lyricsState,
                                positionMs = state.positionMs,
                                onRetry = { viewModel.loadLyrics(force = true) },
                                modifier = Modifier.weight(1f, fill = true)
                            )
                            Pane.SIMILAR -> SimilarPane(
                                tracks = similar,
                                genre = track?.genre,
                                model = model,
                                modifier = Modifier.weight(1f, fill = true)
                            )
                        }
                    }
                }
            }
        }
    }

    if (sleepOpen) {
        SleepTimerSheet(
            activeUntil = state.sleepAtMs,
            atTrackEnd = state.sleepAtTrackEnd,
            onPick = { minutes ->
                model.playback.setSleepTimer(minutes)
                sleepOpen = false
            },
            onTrackEnd = {
                model.playback.setSleepAtTrackEnd(true)
                sleepOpen = false
            },
            onCancel = {
                model.playback.setSleepTimer(null)
                sleepOpen = false
            },
            onDismiss = { sleepOpen = false }
        )
    }
}

/**
 * Like, download, lyrics and queue.
 *
 * Shared by both orientations rather than written twice: a landscape player
 * that cannot like or download a track is the sort of gap nobody notices until
 * they are holding the phone sideways.
 */
@UnstableApi
@Composable
private fun TrackActions(
    current: QueueItem,
    track: TrackEntity?,
    model: SymusViewModel,
    liked: Set<String>,
    downloads: Map<String, DownloadEntity>,
    progress: Map<String, DownloadProgress>,
    pane: Pane,
    onPane: (Pane) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Space.page),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val isLiked = current.videoId in liked
        IconButton(
            onClick = { track?.let(model::toggleLike) },
            enabled = track != null
        ) {
            Icon(
                imageVector = if (isLiked) Icons.Filled.Favorite
                else Icons.Rounded.FavoriteBorder,
                contentDescription = if (isLiked) "Remove from liked songs"
                else "Like this track",
                tint = if (isLiked) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        val download = downloads[current.videoId]
        val downloading = download != null &&
            download.state != DownloadState.FAILED
        IconButton(
            onClick = {
                val row = track ?: return@IconButton
                when {
                    row.cachedPath != null -> model.removeDownload(row)
                    downloading -> model.cancelDownload(row.videoId)
                    else -> model.download(row)
                }
            },
            enabled = track != null
        ) {
            if (downloading) {
                val fraction = progress[current.videoId]?.fraction ?: 0f
                if (fraction > 0f) {
                    CircularProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                }
            } else {
                Icon(
                    imageVector = if (current.offline) Icons.Rounded.DownloadDone
                    else Icons.Rounded.Download,
                    contentDescription = if (current.offline) {
                        "Remove offline copy"
                    } else {
                        "Download for offline"
                    },
                    tint = if (current.offline) MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.weight(1f))
        PaneToggle(
            icon = Icons.Rounded.AutoAwesome,
            label = "More like this",
            selected = pane == Pane.SIMILAR,
            onClick = {
                onPane(if (pane == Pane.SIMILAR) Pane.NONE else Pane.SIMILAR)
            }
        )
        PaneToggle(
            icon = Icons.Rounded.Lyrics,
            label = "Lyrics",
            selected = pane == Pane.LYRICS,
            onClick = {
                onPane(if (pane == Pane.LYRICS) Pane.NONE else Pane.LYRICS)
            }
        )
        PaneToggle(
            icon = Icons.Rounded.QueueMusic,
            label = "Queue",
            selected = pane == Pane.QUEUE,
            onClick = {
                onPane(if (pane == Pane.QUEUE) Pane.NONE else Pane.QUEUE)
            }
        )
    }
}

@Composable
private fun TrackHeading(title: String, artist: String?, onArtist: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Space.page, vertical = Space.tight)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(4.dp))
        Surface(
            onClick = onArtist,
            enabled = !artist.isNullOrBlank(),
            color = Color.Transparent,
            shape = MaterialTheme.shapes.small
        ) {
            Text(
                text = artist ?: "Unknown artist",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(vertical = 6.dp)
            )
        }
    }
}

/**
 * The seek bar.
 *
 * Dragging is tracked locally and only committed on release: reading the
 * position back from the player while a finger is down makes the thumb fight
 * the user.
 */
@Composable
private fun SeekRow(
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit
) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val fraction = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val shown = if (dragging) dragValue else fraction

    Column(Modifier.fillMaxWidth().padding(horizontal = Space.page)) {
        Slider(
            value = shown,
            onValueChange = {
                dragging = true
                dragValue = it
            },
            onValueChangeFinished = {
                if (durationMs > 0) onSeek((dragValue * durationMs).toLong())
                dragging = false
            },
            enabled = durationMs > 0,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest
            ),
            modifier = Modifier
                .fillMaxWidth()
                // Named and given its position in words. A screen reader
                // otherwise announced an unlabelled slider and a percentage,
                // leaving the times beside it — which it reads as separate,
                // unrelated labels — as the only clue to what it controls.
                .semantics {
                    contentDescription = "Seek position"
                    stateDescription = if (durationMs > 0) {
                        "${formatMillis(positionMs)} of ${formatMillis(durationMs)}"
                    } else {
                        "Not available yet"
                    }
                }
        )
        Row(Modifier.fillMaxWidth()) {
            Text(
                text = formatMillis(if (dragging) (dragValue * durationMs).toLong() else positionMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = formatMillis(durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(4.dp))
    }
}

@UnstableApi
@Composable
private fun Transport(
    state: PlaybackUiState,
    model: SymusViewModel
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Space.page, vertical = Space.tight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        IconButton(onClick = model.playback::toggleShuffle, modifier = Modifier.size(48.dp)) {
            Icon(
                Icons.Rounded.Shuffle,
                contentDescription = if (state.shuffle) "Shuffle on" else "Shuffle off",
                tint = if (state.shuffle) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(
            onClick = model.playback::previous,
            modifier = Modifier.size(56.dp)
        ) {
            Icon(
                Icons.Rounded.SkipPrevious,
                contentDescription = "Previous track",
                modifier = Modifier.size(34.dp),
                tint = MaterialTheme.colorScheme.onSurface
            )
        }
        Surface(
            onClick = model.playback::togglePlay,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(68.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (state.buffering) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(26.dp),
                        strokeWidth = 2.5.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Icon(
                        imageVector = if (state.playing) Icons.Rounded.Pause
                        else Icons.Rounded.PlayArrow,
                        contentDescription = if (state.playing) "Pause" else "Play",
                        modifier = Modifier.size(36.dp)
                    )
                }
            }
        }
        IconButton(
            onClick = model.playback::next,
            enabled = state.hasNext,
            modifier = Modifier.size(56.dp)
        ) {
            Icon(
                Icons.Rounded.SkipNext,
                contentDescription = "Next track",
                modifier = Modifier.size(34.dp),
                tint = if (state.hasNext) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = model.playback::cycleRepeat, modifier = Modifier.size(48.dp)) {
            Icon(
                imageVector = if (state.repeat == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne
                else Icons.Rounded.Repeat,
                contentDescription = when (state.repeat) {
                    Player.REPEAT_MODE_ONE -> "Repeating this track"
                    Player.REPEAT_MODE_ALL -> "Repeating the queue"
                    else -> "Repeat off"
                },
                tint = if (state.repeat == Player.REPEAT_MODE_OFF) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.primary
                }
            )
        }
    }
}

@Composable
private fun PaneToggle(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * What is coming up.
 *
 * Reordering is by the two arrows on each row rather than a drag handle. A drag
 * is the obvious gesture, but it is also the one that cannot be performed with a
 * screen reader or a switch — and once the buttons exist for that reason, a
 * separate drag implementation is a second way to get the same thing wrong.
 */
@UnstableApi
@Composable
private fun QueuePane(model: SymusViewModel, modifier: Modifier = Modifier) {
    val state by model.playbackState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val openSheet = LocalTrackSheet.current

    LaunchedEffect(state.index) {
        if (state.index >= 0) {
            runCatching { listState.animateScrollToItem(state.index) }
        }
    }

    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().padding(start = Space.page, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Up next",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "${state.queue.size} tracks",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            IconButton(onClick = model.playback::clearUpcoming) {
                Icon(
                    Icons.Rounded.ClearAll,
                    contentDescription = "Clear everything after this track",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            // So the last row clears the gesture bar instead of sitting on it.
            contentPadding = PaddingValues(bottom = Space.row)
        ) {
            itemsIndexed(state.queue, key = { index, item -> "$index-${item.videoId}" }) { index, item ->
                val playing = index == state.index
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clip(MaterialTheme.shapes.small)
                            .padding(start = Space.page, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            onClick = { model.playback.jumpTo(index) },
                            color = Color.Transparent,
                            modifier = Modifier.weight(1f)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                ArtworkSquare(item.artworkUrl, item.videoId, 40.dp)
                                Spacer(Modifier.width(Space.row))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = item.title,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = if (playing) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = item.artist ?: "Unknown artist",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                    IconButton(
                        onClick = { model.playback.move(index, index - 1) },
                        enabled = index > 0,
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(
                            Icons.Rounded.KeyboardArrowUp,
                            contentDescription = "Move ${item.title} up",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(
                        onClick = { model.playback.move(index, index + 1) },
                        enabled = index < state.queue.lastIndex,
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(
                            Icons.Rounded.KeyboardArrowDown,
                            contentDescription = "Move ${item.title} down",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(
                        onClick = { model.playback.removeAt(index) },
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = "Remove ${item.title} from the queue",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/**
 * Tracks like the one playing, genre first. Tapping one plays it next rather
 * than replacing the queue — a suggestion should not cost you what you queued.
 */
@UnstableApi
@Composable
private fun SimilarPane(
    tracks: List<TrackEntity>?,
    genre: String?,
    model: SymusViewModel,
    modifier: Modifier = Modifier
) {
    val openSheet = LocalTrackSheet.current
    Column(modifier) {
        Text(
            text = if (genre != null) "More $genre" else "More like this",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = Space.page, vertical = 8.dp)
        )
        when {
            tracks == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            tracks.isEmpty() -> EmptyState(
                icon = Icons.Rounded.AutoAwesome,
                title = "Nothing similar yet",
                message = "Play or like a few more songs and suggestions will show up here."
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = Space.row)
            ) {
                items(tracks, key = { it.videoId }) { t ->
                    TrackRow(
                        title = t.title,
                        subtitle = listOfNotNull(t.artist, t.genre).joinToString(" · "),
                        artworkUrl = t.thumbnailUrl,
                        seed = t.videoId,
                        onClick = { model.playNext(t) },
                        onMore = { openSheet(TrackSheetRequest(t)) }
                    )
                }
            }
        }
    }
}

/**
 * The words, scrolling themselves when the source had timings.
 *
 * Unsynced lyrics are shown as a block rather than faked into lines: guessing at
 * timings puts the wrong line under the wrong moment, which is worse than not
 * highlighting anything.
 */
@Composable
private fun LyricsPane(
    state: LyricsState,
    positionMs: Long,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxWidth()) {
        when (state) {
            LyricsState.Idle, LyricsState.Loading -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            }

            is LyricsState.Ready -> when (val result = state.result) {
                is LyricsResult.Found -> {
                    val lines = result.lines
                    if (lines != null) {
                        val active = lines.indexOfLast { it.timeSec * 1000 <= positionMs }
                        val listState = rememberLazyListState()
                        LaunchedEffect(active) {
                            if (active >= 0) {
                                runCatching {
                                    listState.animateScrollToItem(maxOf(0, active - 2))
                                }
                            }
                        }
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                horizontal = Space.page,
                                vertical = Space.row
                            )
                        ) {
                            itemsIndexed(lines) { index, line ->
                                Text(
                                    text = line.text.ifBlank { "·" },
                                    style = MaterialTheme.typography.titleMedium,
                                    color = if (index == active) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.padding(vertical = 6.dp)
                                )
                            }
                        }
                    } else {
                        Column(
                            Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = Space.page)
                        ) {
                            Text(
                                text = "No timings for this one — showing the plain words.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(Space.tight))
                            Text(
                                text = result.plain.orEmpty(),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.height(Space.section))
                        }
                    }
                }

                LyricsResult.None -> EmptyState(
                    icon = Icons.Rounded.Lyrics,
                    title = "No lyrics",
                    message = "LRCLIB has nothing for this track. Instrumentals and live " +
                        "uploads usually do not."
                )

                is LyricsResult.Failed -> ErrorState(message = result.message, onRetry = onRetry)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun SleepTimerSheet(
    activeUntil: Long?,
    atTrackEnd: Boolean,
    onPick: (Int) -> Unit,
    onTrackEnd: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            Modifier
                .navigationBarsPadding()
                .padding(horizontal = Space.page)
                .padding(bottom = Space.page)
        ) {
            Text("Sleep timer", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                text = when {
                    atTrackEnd -> "Stopping at the end of this track."
                    activeUntil != null -> {
                        val left = ((activeUntil - System.currentTimeMillis()) / 60_000)
                            .coerceAtLeast(0)
                        "Stopping in about $left minutes."
                    }
                    else -> "Playback stops on its own."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(Space.page))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(15, 30, 45, 60, 90).forEach { minutes ->
                    Pill(
                        label = "$minutes min",
                        selected = false,
                        onClick = { onPick(minutes) }
                    )
                }
                Pill(label = "End of track", selected = atTrackEnd, onClick = onTrackEnd)
                if (activeUntil != null || atTrackEnd) {
                    Pill(label = "Off", selected = false, onClick = onCancel)
                }
            }
        }
    }
}
