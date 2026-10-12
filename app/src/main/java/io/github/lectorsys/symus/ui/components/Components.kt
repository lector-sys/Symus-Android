package io.github.lectorsys.symus.ui.components

import android.provider.Settings
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.lectorsys.symus.R
import io.github.lectorsys.symus.ui.theme.Space
import kotlin.math.abs

/**
 * Whether to animate at all.
 *
 * Someone who has turned animations off in accessibility settings meant it, and
 * a shimmer is the exact kind of decoration they turned off.
 */
@Composable
fun animationsEnabled(): Boolean {
    val context = LocalContext.current
    return remember {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }.getOrDefault(1f) > 0f
    }
}

/**
 * Artwork, with a fallback that is not a grey square.
 *
 * Plenty of uploads have no usable thumbnail, and every one of those would
 * otherwise be an identical blank. The fallback is a gradient derived from the
 * track's own id, so a library of them still looks like a library of different
 * things — and the app mark sits on top so it reads as deliberate.
 */
@Composable
fun Artwork(
    url: String?,
    seed: String,
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(10.dp),
    initial: String? = null
) {
    Box(
        modifier = modifier.clip(shape).background(gradientFor(seed)),
        contentAlignment = Alignment.Center
    ) {
        when {
            !url.isNullOrBlank() -> AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            // YouTube publishes no artist images, so an artist tile has nothing
            // to show. A letter on the artist's own gradient is scannable where
            // a row of identical blank discs is not.
            !initial.isNullOrBlank() -> Text(
                text = initial.trim().take(1).uppercase(),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
            )
            // A track with no artwork at all. A URL that merely fails to load
            // keeps the gradient on its own: the mark would be cropped to fill
            // this box and read as a broken image.
            else -> Image(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier.fillMaxSize().alpha(0.55f)
            )
        }
    }
}

/** Square artwork at a fixed edge, the shape used by every list row. */
@Composable
fun ArtworkSquare(
    url: String?,
    seed: String,
    size: Dp = 52.dp,
    shape: RoundedCornerShape = RoundedCornerShape(8.dp)
) = Artwork(url, seed, Modifier.size(size), shape)

/** Two warm tones picked from the id, so the same track always looks the same. */
fun gradientFor(seed: String): Brush {
    val hash = abs(seed.hashCode())
    val hue = (hash % 360).toFloat()
    val top = Color.hsl(hue, 0.28f, 0.30f)
    val bottom = Color.hsl((hue + 28f) % 360f, 0.32f, 0.16f)
    return Brush.linearGradient(listOf(top, bottom))
}

/** A section title, with an optional action on the right. */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Space.page, vertical = Space.tight),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

/** A horizontally scrolling row of cards, with the app's standard gutters. */
@Composable
fun Shelf(
    modifier: Modifier = Modifier,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = Space.page),
        horizontalArrangement = Arrangement.spacedBy(Space.row),
        content = content
    )
}

/** A card in a shelf: artwork over two lines of text. */
@Composable
fun MediaCard(
    title: String,
    subtitle: String?,
    artworkUrl: String?,
    seed: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 150.dp,
    circular: Boolean = false,
    initial: String? = null
) {
    Column(
        modifier = modifier
            .width(width)
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(bottom = 4.dp)
    ) {
        Artwork(
            url = artworkUrl,
            seed = seed,
            modifier = Modifier.width(width).aspectRatio(1f),
            shape = if (circular) RoundedCornerShape(percent = 50) else RoundedCornerShape(12.dp),
            initial = initial
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * One track in a list.
 *
 * The whole row is the play target and the trailing button is the menu, both at
 * least 48dp, because the two are pressed constantly and a mis-hit either way is
 * the most annoying thing a music app can do.
 */
@Composable
fun TrackRow(
    title: String,
    subtitle: String?,
    artworkUrl: String?,
    seed: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    liked: Boolean = false,
    offline: Boolean = false,
    playing: Boolean = false,
    trailing: @Composable (() -> Unit)? = null,
    onMore: (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(onClick = onClick)
            .padding(start = Space.page, end = if (onMore != null) 4.dp else Space.page),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ArtworkSquare(artworkUrl, seed)
        Spacer(Modifier.width(Space.row))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = if (playing) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (liked) {
                    Icon(
                        imageVector = Icons.Filled.Favorite,
                        contentDescription = "Liked",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                }
                if (offline) {
                    Icon(
                        imageVector = Icons.Rounded.DownloadDone,
                        contentDescription = "Available offline",
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                }
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        trailing?.invoke()
        if (onMore != null) {
            IconButton(onClick = onMore, modifier = Modifier.size(48.dp)) {
                Icon(
                    imageVector = Icons.Rounded.MoreVert,
                    contentDescription = "More actions for $title",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * A selectable pill, for genres, filters and result tabs.
 *
 * The selected one is announced as selected, not merely coloured differently.
 * Colour alone told a screen reader nothing — every pill read as an identical
 * button — and it is the only signal for which theme, quality or sort order is
 * currently in force.
 */
@Composable
fun Pill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Whether this pill is one of a set of choices.
     *
     * False for the genre chips on Home and Library, which look the same but
     * are links to another screen — announcing those as "radio button, not
     * selected" would describe a choice the user is not being asked to make.
     */
    selectable: Boolean = true
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary
        else MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .heightIn(min = 40.dp)
            .semantics {
                if (selectable) {
                    this.selected = selected
                    role = Role.RadioButton
                } else {
                    role = Role.Button
                }
            }
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
        )
    }
}

/** What a screen shows when there is genuinely nothing to show. */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp)
            )
        }
        Spacer(Modifier.height(Space.page))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(Space.page))
            OutlinedButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

/**
 * A failure the user can do something about.
 *
 * Retry is only offered when retrying could actually work — a removed video is
 * not going to come back, and a button that cannot succeed is worse than none.
 */
@Composable
fun ErrorState(
    message: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        if (onRetry != null) {
            Spacer(Modifier.height(Space.page))
            OutlinedButton(onClick = onRetry) {
                Icon(Icons.Rounded.Refresh, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Try again")
            }
        }
    }
}

/** A block that stands in for content while it loads. */
@Composable
fun Placeholder(modifier: Modifier = Modifier, shape: RoundedCornerShape = RoundedCornerShape(8.dp)) {
    val animate = animationsEnabled()
    val alpha = if (animate) {
        val transition = rememberInfiniteTransition(label = "placeholder")
        transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 0.65f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
            label = "placeholderAlpha"
        ).value
    } else {
        0.5f
    }
    Box(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .graphicsLayer { this.alpha = alpha }
            .clearAndSetSemantics { }
    )
}

/**
 * Rows of placeholders, matching the shape of a track list.
 *
 * `modifier` comes first among the optional parameters, which is the Compose
 * convention and what the ModifierParameter lint check enforces — callers pass
 * the count by name.
 */
@Composable
fun PlaceholderRows(modifier: Modifier = Modifier, count: Int = 6) {
    Column(modifier) {
        repeat(count) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .padding(horizontal = Space.page, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Placeholder(Modifier.size(52.dp))
                Spacer(Modifier.width(Space.row))
                Column(Modifier.weight(1f)) {
                    Placeholder(Modifier.fillMaxWidth(0.65f).height(14.dp))
                    Spacer(Modifier.height(8.dp))
                    Placeholder(Modifier.fillMaxWidth(0.35f).height(11.dp))
                }
            }
        }
    }
}

/** Placeholder cards, matching the shape of a shelf. */
@Composable
fun PlaceholderShelf(count: Int = 4, width: Dp = 150.dp) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Space.page),
        horizontalArrangement = Arrangement.spacedBy(Space.row)
    ) {
        repeat(count) {
            Column(Modifier.width(width)) {
                Placeholder(Modifier.width(width).aspectRatio(1f), RoundedCornerShape(12.dp))
                Spacer(Modifier.height(10.dp))
                Placeholder(Modifier.fillMaxWidth(0.8f).height(12.dp))
            }
        }
    }
}

/**
 * "1 track", not "1 tracks".
 *
 * Small, and everywhere: the count appears on every tile, card, shelf and
 * header in the app, so getting it wrong is visible on every screen at once.
 */
fun tracksLabel(count: Int): String = if (count == 1) "1 track" else "$count tracks"

/** m:ss, or h:mm:ss for anything long enough to need it. */
fun formatDuration(seconds: Long): String {
    if (seconds <= 0) return "--:--"
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, secs)
    else "%d:%02d".format(minutes, secs)
}

fun formatMillis(millis: Long): String = formatDuration(millis / 1000)

fun formatBytes(bytes: Long): String = when {
    bytes <= 0 -> "0 MB"
    bytes < 1024L * 1024 -> "${bytes / 1024} KB"
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
}
