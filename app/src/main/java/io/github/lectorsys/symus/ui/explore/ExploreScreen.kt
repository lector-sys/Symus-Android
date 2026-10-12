package io.github.lectorsys.symus.ui.explore

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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Mood
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import io.github.lectorsys.symus.ui.SymusViewModel
import io.github.lectorsys.symus.ui.components.ErrorState
import io.github.lectorsys.symus.ui.components.MediaCard
import io.github.lectorsys.symus.ui.components.PlaceholderShelf
import io.github.lectorsys.symus.ui.components.SectionHeader
import io.github.lectorsys.symus.ui.components.Shelf
import io.github.lectorsys.symus.ui.theme.Space

/**
 * Explore.
 *
 * Shelves built from ordinary YouTube searches, seeded by what the library
 * already leans on. There is no trending API behind any of it and the screen
 * says so once, at the top — a row of search results presented as a chart would
 * be a lie about where it came from.
 */
@UnstableApi
@Composable
fun ExploreScreen(
    model: SymusViewModel,
    onNavigate: (String) -> Unit,
    viewModel: ExploreViewModel = hiltViewModel()
) {
    val shelves by viewModel.shelves.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.load() }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = Space.section)
    ) {
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(start = Space.page, end = 4.dp, top = Space.tight),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Explore", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        text = "Searches seeded by what you listen to, not a chart",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { viewModel.load(force = true) }) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "Refresh these shelves")
                }
            }
            Spacer(Modifier.height(Space.row))
        }

        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Space.page),
                horizontalArrangement = Arrangement.spacedBy(Space.row)
            ) {
                DestinationTile(
                    icon = Icons.Rounded.Mood,
                    label = "Moods and genres",
                    modifier = Modifier.weight(1f),
                    onClick = { onNavigate("moods") }
                )
                DestinationTile(
                    icon = Icons.Rounded.BarChart,
                    label = "Your charts",
                    modifier = Modifier.weight(1f),
                    onClick = { onNavigate("charts") }
                )
            }
            Spacer(Modifier.height(Space.row))
            Row(Modifier.fillMaxWidth().padding(horizontal = Space.page)) {
                DestinationTile(
                    icon = Icons.Rounded.Mic,
                    label = "Podcasts and talk",
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onNavigate("podcasts") }
                )
            }
            Spacer(Modifier.height(Space.tight))
        }

        if (shelves.isEmpty()) {
            item {
                SectionHeader("Finding something for you")
                PlaceholderShelf()
            }
        }

        items(shelves, key = { it.shelf.id }) { state ->
            SectionHeader(state.shelf.title)
            when {
                state.loading -> PlaceholderShelf()
                state.error != null -> ErrorState(
                    message = state.error,
                    onRetry = if (state.retryable) ({ viewModel.retry(state.shelf) }) else null
                )
                state.tracks.isEmpty() -> Text(
                    text = "YouTube returned nothing for \"${state.shelf.query}\".",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Space.page)
                )
                else -> Shelf {
                    items(state.tracks.size, key = { state.tracks[it].videoId }) { index ->
                        val track = state.tracks[index]
                        MediaCard(
                            title = track.title,
                            subtitle = track.artist ?: "Unknown artist",
                            artworkUrl = track.thumbnailUrl,
                            seed = track.videoId,
                            onClick = { model.play(state.tracks, index) }
                        )
                    }
                }
            }
            Spacer(Modifier.height(Space.row))
        }
    }
}

@Composable
private fun DestinationTile(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier
    ) {
        Row(
            Modifier.padding(Space.row),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(Space.tight))
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** A back arrow and a title, for the screens that hang off a tab. */
@Composable
fun SubScreenHeader(title: String, subtitle: String?, onBack: () -> Unit, action: @Composable (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 4.dp, end = 4.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.Rounded.ArrowBack, contentDescription = "Back")
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        action?.invoke()
    }
}

/** A small round spinner, used where a tile is doing work. */
@Composable
fun TileSpinner(modifier: Modifier = Modifier) {
    Box(modifier.size(18.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(strokeWidth = 2.dp)
    }
}
