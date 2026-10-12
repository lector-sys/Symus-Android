package io.github.lectorsys.symus.ui.explore

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import io.github.lectorsys.symus.ui.SymusViewModel
import io.github.lectorsys.symus.ui.components.EmptyState
import io.github.lectorsys.symus.ui.components.ErrorState
import io.github.lectorsys.symus.ui.components.MediaCard
import io.github.lectorsys.symus.ui.components.PlaceholderRows
import io.github.lectorsys.symus.ui.components.SectionHeader
import io.github.lectorsys.symus.ui.components.Shelf
import io.github.lectorsys.symus.ui.components.TrackRow
import io.github.lectorsys.symus.ui.components.formatDuration
import io.github.lectorsys.symus.ui.components.gradientFor
import io.github.lectorsys.symus.ui.sheets.LocalTrackSheet
import io.github.lectorsys.symus.ui.sheets.TrackSheetRequest
import io.github.lectorsys.symus.ui.components.tracksLabel
import io.github.lectorsys.symus.ui.theme.Space
import java.net.URLEncoder

/**
 * Moods and genres.
 *
 * Every tile is browsable, including the ones with nothing behind them yet: an
 * empty genre is the entry point to "find more", which is the only way a genre
 * with no inference rule — Devotional, Punjabi — ever gets a first track.
 */
@UnstableApi
@Composable
fun MoodsScreen(
    model: SymusViewModel,
    onBack: () -> Unit,
    onNavigate: (String) -> Unit,
    viewModel: MoodsViewModel = hiltViewModel()
) {
    val tiles by viewModel.tiles.collectAsStateWithLifecycle()
    val working by viewModel.working.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.load() }
    LaunchedEffect(message) {
        message?.let {
            model.say(it)
            viewModel.clearMessage()
        }
    }

    Column(Modifier.fillMaxWidth()) {
        SubScreenHeader(
            title = "Moods and genres",
            subtitle = "Tap to browse · plus to pull more in from YouTube",
            onBack = onBack
        )
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 164.dp),
            contentPadding = PaddingValues(Space.page),
            horizontalArrangement = Arrangement.spacedBy(Space.row),
            verticalArrangement = Arrangement.spacedBy(Space.row)
        ) {
            items(tiles, key = { it.genre }) { tile ->
                Surface(
                    onClick = { onNavigate("genre/${URLEncoder.encode(tile.genre, "UTF-8")}") },
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // A colour per genre, from the name: a grid of identical
                        // tiles is much harder to scan than a grid of different ones.
                        Box(Modifier.size(54.dp).background(gradientFor(tile.genre)))
                        Spacer(Modifier.width(Space.row))
                        Column(Modifier.weight(1f).padding(vertical = Space.tight)) {
                            Text(
                                text = tile.genre,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = if (tile.count == 0) "Nothing yet"
                                else tracksLabel(tile.count),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (working == tile.genre) {
                            TileSpinner(Modifier.padding(end = Space.row))
                        } else {
                            IconButton(
                                onClick = { viewModel.findMore(tile.genre) },
                                enabled = working == null
                            ) {
                                Icon(
                                    Icons.Rounded.Add,
                                    contentDescription = "Find more ${tile.genre} on YouTube",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Charts.
 *
 * This library's own play counts, and nothing else. There is no global chart
 * behind any of it and no API here that could provide one, which the subtitle
 * states rather than implying otherwise by calling it "trending".
 */
@UnstableApi
@Composable
fun ChartsScreen(
    model: SymusViewModel,
    onBack: () -> Unit,
    onNavigate: (String) -> Unit,
    viewModel: ChartsViewModel = hiltViewModel()
) {
    val charts by viewModel.charts.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val liked by model.likedIds.collectAsStateWithLifecycle()
    val playback by model.playbackState.collectAsStateWithLifecycle()
    val openSheet = LocalTrackSheet.current

    LaunchedEffect(Unit) { viewModel.load() }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = Space.section)
    ) {
        item {
            SubScreenHeader(
                title = "Your charts",
                subtitle = "Counted on this device, from what you played",
                onBack = onBack
            )
        }

        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Space.page, vertical = Space.tight),
                horizontalArrangement = Arrangement.spacedBy(Space.tight)
            ) {
                StatBox("Plays", stats.plays.toString(), Modifier.weight(1f))
                StatBox("Tracks", stats.distinct.toString(), Modifier.weight(1f))
                StatBox(
                    label = "Listened",
                    value = if (stats.secondsListened < 3600) {
                        "${stats.secondsListened / 60}m"
                    } else {
                        "${stats.secondsListened / 3600}h"
                    },
                    modifier = Modifier.weight(1f)
                )
            }
            if (stats.topGenre != null || stats.skipped > 0) {
                Text(
                    text = listOfNotNull(
                        stats.topGenre?.let { "Most played genre: $it" },
                        stats.skipped.takeIf { it > 0 }?.let { "$it skips counted against" }
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Space.page)
                )
            }
            Spacer(Modifier.height(Space.row))
        }

        val data = charts
        if (data == null) {
            item { PlaceholderRows(count = 5) }
            return@LazyColumn
        }

        if (data.tracks.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Rounded.BarChart,
                    title = "Nothing counted yet",
                    message = "Play a few tracks and this fills in. Nothing here comes from " +
                        "anywhere but this device."
                )
            }
            return@LazyColumn
        }

        if (data.sparse) {
            item {
                Text(
                    text = "Under eight played tracks this ranking is mostly accident — " +
                        "it will settle down as you listen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Space.page, vertical = 4.dp)
                )
            }
        }

        item { SectionHeader("Most played tracks") }
        items(data.tracks.size, key = { data.tracks[it].videoId }) { index ->
            val row = data.tracks[index]
            val track = row.track()
            TrackRow(
                title = track.title,
                subtitle = listOfNotNull(
                    track.artist,
                    "${row.plays} plays",
                    formatDuration(track.duration)
                ).joinToString(" · "),
                artworkUrl = track.thumbnailUrl,
                seed = track.videoId,
                liked = track.videoId in liked,
                offline = track.cachedPath != null,
                playing = playback.current?.videoId == track.videoId,
                onClick = { model.play(data.tracks.map { it.track() }, index) },
                onMore = { openSheet(TrackSheetRequest(track)) }
            )
        }

        if (data.artists.isNotEmpty()) {
            item {
                SectionHeader("Most played artists")
                Shelf {
                    items(data.artists, key = { it.artist }) { artist ->
                        MediaCard(
                            title = artist.artist,
                            subtitle = "${artist.plays} plays",
                            artworkUrl = null,
                            seed = artist.artist,
                            circular = true,
                            initial = artist.artist,
                            width = 116.dp,
                            onClick = {
                                onNavigate("artist/${URLEncoder.encode(artist.artist, "UTF-8")}")
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatBox(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier
    ) {
        Column(Modifier.padding(Space.row)) {
            Text(value, style = MaterialTheme.typography.headlineSmall)
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Podcasts.
 *
 * There is no podcast directory behind this: it is the same YouTube search the
 * rest of the app uses, pointed at talk-shaped queries, and the row says which
 * query produced it. What makes it a podcasts screen is that everything here is
 * tagged as talk so it stays out of the music shelves, and that channels can be
 * followed.
 */
@UnstableApi
@Composable
fun PodcastsScreen(
    model: SymusViewModel,
    onBack: () -> Unit,
    viewModel: PodcastsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val following by viewModel.following.collectAsStateWithLifecycle()
    val shelves by viewModel.channelShelves.collectAsStateWithLifecycle()
    val playback by model.playbackState.collectAsStateWithLifecycle()
    val openSheet = LocalTrackSheet.current

    LaunchedEffect(Unit) { viewModel.load() }
    LaunchedEffect(following) { following.forEach { viewModel.loadChannel(it.channelId) } }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = Space.section)
    ) {
        item {
            SubScreenHeader(
                title = "Podcasts and talk",
                subtitle = "YouTube search, pointed at talk. Not a podcast directory.",
                onBack = onBack
            )
        }

        if (following.isNotEmpty()) {
            item { SectionHeader("Following") }
            items(following, key = { it.channelId }) { channel ->
                Column {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = Space.page, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = channel.name,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        IconButton(onClick = { viewModel.unfollow(channel.channelId) }) {
                            Icon(
                                Icons.Rounded.Check,
                                contentDescription = "Unfollow ${channel.name}",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    val episodes = shelves[channel.channelId].orEmpty()
                    if (episodes.isEmpty()) {
                        Text(
                            text = "No recent uploads found.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = Space.page)
                        )
                    } else {
                        Shelf {
                            items(episodes.size, key = { episodes[it].videoId }) { index ->
                                val episode = episodes[index]
                                MediaCard(
                                    title = episode.title,
                                    subtitle = formatDuration(episode.duration),
                                    artworkUrl = episode.thumbnailUrl,
                                    seed = episode.videoId,
                                    width = 168.dp,
                                    onClick = { model.play(episodes, index) }
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(Space.row))
                }
            }
        }

        if (state.loading) {
            item { PlaceholderRows(count = 6) }
            return@LazyColumn
        }

        state.error?.let { message ->
            item {
                ErrorState(
                    message = message,
                    onRetry = if (state.retryable) ({ viewModel.load(force = true) }) else null
                )
            }
            return@LazyColumn
        }

        if (state.channels.isNotEmpty()) {
            item {
                SectionHeader("Channels in these results")
                Shelf {
                    items(state.channels, key = { it.channelId }) { channel ->
                        val followed = following.any { it.channelId == channel.channelId }
                        Surface(
                            onClick = {
                                if (followed) viewModel.unfollow(channel.channelId)
                                else viewModel.follow(channel)
                            },
                            shape = MaterialTheme.shapes.large,
                            color = if (followed) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceContainerHigh
                        ) {
                            Row(
                                Modifier.padding(horizontal = Space.row, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    if (followed) Icons.Rounded.Check else Icons.Rounded.Add,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = channel.name,
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(Space.row))
            }
        }

        if (state.episodes.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Rounded.Mic,
                    title = "Nothing came back",
                    message = "YouTube returned no talk uploads for today's query."
                )
            }
            return@LazyColumn
        }

        item {
            SectionHeader("Today's shelf")
            Text(
                text = "From the search \"${state.query}\", rotated daily.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Space.page, vertical = 4.dp)
            )
        }

        items(state.episodes.size, key = { state.episodes[it].videoId }) { index ->
            val episode = state.episodes[index]
            TrackRow(
                title = episode.title,
                subtitle = listOfNotNull(
                    episode.artist,
                    formatDuration(episode.duration)
                ).joinToString(" · "),
                artworkUrl = episode.thumbnailUrl,
                seed = episode.videoId,
                offline = episode.cachedPath != null,
                playing = playback.current?.videoId == episode.videoId,
                onClick = { model.play(state.episodes, index) },
                onMore = { openSheet(TrackSheetRequest(episode)) }
            )
        }
    }
}
