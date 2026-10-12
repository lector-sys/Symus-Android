package io.github.lectorsys.symus.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.lectorsys.symus.media.PlaybackUiState
import io.github.lectorsys.symus.ui.components.ArtworkSquare
import io.github.lectorsys.symus.ui.theme.Space

/**
 * The bar that says what is playing, wherever you are in the app.
 *
 * Sits directly on top of the navigation bar — one surface, two rows — rather
 * than floating over the content, so nothing is ever hidden behind it and the
 * layout below can simply account for its height.
 */
@Composable
fun MiniPlayer(
    state: PlaybackUiState,
    accent: Color,
    onOpen: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier
) {
    val track = state.current
    AnimatedVisibility(
        visible = track != null,
        enter = slideInVertically { it },
        exit = slideOutVertically { it },
        modifier = modifier
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                // Progress as a hairline: readable at a glance, and it cannot be
                // mistaken for something draggable the way a slim slider can.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                ) {
                    val fraction = if (state.durationMs > 0) {
                        (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                    Box(
                        Modifier
                            .fillMaxWidth(fraction)
                            .height(2.dp)
                            .background(Brush.horizontalGradient(listOf(accent, MaterialTheme.colorScheme.primary)))
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // A minimum, not a fixed height: at a large font scale
                        // the title and artist together are taller than 66dp,
                        // and a fixed row clipped the artist line into the
                        // navigation bar.
                        .heightIn(min = 66.dp)
                        .clickable(onClick = onOpen)
                        .padding(horizontal = Space.row, vertical = Space.tight),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ArtworkSquare(track?.artworkUrl, track?.videoId ?: "", 44.dp)
                    Spacer(Modifier.width(Space.row))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = track?.title ?: "",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = when {
                                state.buffering -> "Loading…"
                                else -> track?.artist ?: "Unknown artist"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(
                        onClick = onTogglePlay,
                        modifier = Modifier
                            .size(48.dp)
                            .semantics {
                                contentDescription = if (state.playing) "Pause" else "Play"
                            }
                    ) {
                        Icon(
                            imageVector = if (state.playing) Icons.Rounded.Pause
                            else Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(28.dp),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    IconButton(
                        onClick = onNext,
                        enabled = state.hasNext,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.SkipNext,
                            contentDescription = "Next track",
                            modifier = Modifier.size(26.dp),
                            tint = if (state.hasNext) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
