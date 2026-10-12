package io.github.lectorsys.symus.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import io.github.lectorsys.symus.library.ListeningSummary
import io.github.lectorsys.symus.library.formatListening
import io.github.lectorsys.symus.ui.SymusViewModel
import io.github.lectorsys.symus.ui.components.TrackRow
import io.github.lectorsys.symus.ui.theme.Space

/**
 * What you actually listened to, counted rather than estimated.
 *
 * Every figure on this screen is a sum or a count over rows written while audio
 * was playing. Where a figure is incomplete — plays that predate measurement,
 * or were cut short by a process death — the screen says so next to the number
 * instead of quietly presenting a floor as a total.
 */
@UnstableApi
@Composable
fun ListeningScreen(
    model: SymusViewModel,
    onBack: () -> Unit,
    viewModel: ListeningViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = Space.section)
    ) {
        item {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(start = 4.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Rounded.ArrowBack, contentDescription = "Back")
                }
                Text("Listening", style = MaterialTheme.typography.headlineSmall)
            }
        }

        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Space.page),
                horizontalArrangement = Arrangement.spacedBy(Space.tight)
            ) {
                viewModel.ranges.forEach { range ->
                    RangeChip(
                        label = range.label,
                        selected = state.rangeIndex == viewModel.ranges.indexOf(range),
                        onClick = { viewModel.setRange(viewModel.ranges.indexOf(range)) }
                    )
                }
            }
            Spacer(Modifier.height(Space.row))
        }

        val summary = state.summary
        if (summary == null) {
            item { Loading() }
            return@LazyColumn
        }

        if (summary.isEmpty) {
            item { Nothing(summary) }
            return@LazyColumn
        }

        item { Headline(summary) }

        if (summary.topArtists.isNotEmpty()) {
            item { SectionTitle("Artists") }
            items(summary.topArtists.size) { index ->
                val row = summary.topArtists[index]
                Bar(
                    label = row.artist,
                    detail = "${row.plays} ${if (row.plays == 1) "play" else "plays"}",
                    fraction = row.listenedMs.toFloat() /
                        (summary.topArtists.first().listenedMs.takeIf { it > 0 } ?: 1L).toFloat()
                )
            }
        }

        if (summary.topGenres.isNotEmpty()) {
            item { SectionTitle("Genres") }
            items(summary.topGenres.size) { index ->
                val row = summary.topGenres[index]
                val top = summary.topGenres.first().count.coerceAtLeast(1)
                Bar(
                    label = row.genre,
                    detail = "${row.count}",
                    fraction = row.count.toFloat() / top.toFloat()
                )
            }
        }

        if (summary.topTracks.isNotEmpty()) {
            item { SectionTitle("Tracks") }
            items(summary.topTracks.size) { index ->
                val row = summary.topTracks[index]
                TrackRow(
                    title = row.title,
                    subtitle = row.artist,
                    artworkUrl = row.thumbnailUrl,
                    seed = row.videoId,
                    onClick = {
                        model.play(summary.topTracks.map { it.track() }, index)
                    },
                    trailing = {
                        Text(
                            "${row.plays}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun Headline(summary: ListeningSummary) {
    Column(Modifier.padding(horizontal = Space.page)) {
        Text(
            text = formatListening(summary.listenedMs),
            style = MaterialTheme.typography.displaySmall
        )
        Text(
            text = "${summary.plays} ${if (summary.plays == 1) "play" else "plays"} in " +
                summary.window.label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (summary.unmeasuredPlays > 0) {
            Spacer(Modifier.height(Space.tight))
            Text(
                // Stated wherever the total is, at the strength the gap
                // deserves: a quarter of the window unmeasured is a different
                // claim from one stray row.
                text = if (summary.measurementIncomplete) {
                    "${summary.unmeasuredPlays} plays carry no measured time, so the " +
                        "real total is meaningfully higher than this."
                } else {
                    "${summary.unmeasuredPlays} plays carry no measured time and are " +
                        "not counted in the total."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(Space.row))
    }
}

@Composable
private fun Nothing(summary: ListeningSummary) {
    Column(Modifier.padding(horizontal = Space.page)) {
        Text("Nothing recorded", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(Space.tight))
        Text(
            text = "No plays in ${summary.window.label}. This fills in on its own as " +
                "you listen — nothing is estimated and nothing is imported.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun Loading() {
    Text(
        "Counting…",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Space.page)
    )
}

@Composable
private fun SectionTitle(text: String) {
    Spacer(Modifier.height(Space.row))
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(horizontal = Space.page, vertical = Space.tight)
    )
}

/**
 * One row of the ranking, as a bar rather than a number alone.
 *
 * The bar is proportional to the leader, which is the comparison people
 * actually make — "twice as much as the next one" is visible at a glance and
 * invisible in a column of minutes.
 */
@Composable
private fun Bar(label: String, detail: String, fraction: Float) {
    Column(Modifier.padding(horizontal = Space.page, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(Space.tight))
            Text(
                text = detail,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction.coerceIn(0.02f, 1f))
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
    }
}

@Composable
private fun RangeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceContainer
                }
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurface
            }
        )
    }
}
