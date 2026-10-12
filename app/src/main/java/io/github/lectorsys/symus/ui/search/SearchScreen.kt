package io.github.lectorsys.symus.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import io.github.lectorsys.symus.media.SearchKind
import io.github.lectorsys.symus.ui.SymusViewModel
import io.github.lectorsys.symus.ui.components.EmptyState
import io.github.lectorsys.symus.ui.components.ErrorState
import io.github.lectorsys.symus.ui.components.MediaCard
import io.github.lectorsys.symus.ui.components.Pill
import io.github.lectorsys.symus.ui.components.PlaceholderRows
import io.github.lectorsys.symus.ui.components.SectionHeader
import io.github.lectorsys.symus.ui.components.Shelf
import io.github.lectorsys.symus.ui.components.TrackRow
import io.github.lectorsys.symus.ui.components.formatDuration
import io.github.lectorsys.symus.ui.sheets.LocalTrackSheet
import io.github.lectorsys.symus.ui.sheets.TrackSheetRequest
import io.github.lectorsys.symus.ui.theme.Space

/**
 * Search.
 *
 * One field, three filters, and results that are playable the moment they
 * appear. Recent queries stand in for results before anything has been typed,
 * because the second-most-likely thing anyone wants is what they searched for
 * last time.
 */
@UnstableApi
@Composable
fun SearchScreen(
    model: SymusViewModel,
    onNavigate: (String) -> Unit,
    viewModel: SearchViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val suggestions by viewModel.suggestions.collectAsStateWithLifecycle()
    val recent by viewModel.recentQueries.collectAsStateWithLifecycle()
    val liked by model.likedIds.collectAsStateWithLifecycle()
    val playback by model.playbackState.collectAsStateWithLifecycle()
    val openSheet = LocalTrackSheet.current
    val keyboard = LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()
    var listening by remember { mutableStateOf(false) }

    if (listening) {
        VoiceSheet(
            model = viewModel,
            onDismiss = { listening = false },
            // Into the field, not into a search. What came back may well be
            // wrong, and the user gets to see and fix it first.
            onTranscribed = { text -> viewModel.onQueryChange(text) }
        )
    }

    // Paging from the scroll position rather than a button: the list is the
    // thing being scrolled, so the trigger belongs to it.
    val nearEnd by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= listState.layoutInfo.totalItemsCount - 4
        }
    }
    LaunchedEffect(nearEnd, state.hasMore) {
        if (nearEnd && state.hasMore) viewModel.loadMore()
    }

    Column(Modifier.fillMaxWidth().imePadding()) {
        OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::onQueryChange,
            placeholder = { Text("Songs, artists, or a YouTube link") },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            trailingIcon = {
                Row {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = viewModel::clearQuery) {
                            Icon(Icons.Rounded.Close, contentDescription = "Clear the search")
                        }
                    }
                    // Absent rather than disabled on a device with no
                    // recogniser: a microphone that cannot listen is worse
                    // than no microphone.
                    if (viewModel.voiceAvailable) {
                        IconButton(onClick = { listening = true }) {
                            Icon(Icons.Rounded.Mic, contentDescription = "Search by voice")
                        }
                    }
                }
            },
            singleLine = true,
            shape = MaterialTheme.shapes.large,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                unfocusedIndicatorColor = MaterialTheme.colorScheme.outlineVariant
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(
                onSearch = {
                    keyboard?.hide()
                    viewModel.submit()
                }
            ),
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = Space.page, vertical = Space.tight)
        )

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.page, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Pill(
                label = "Songs",
                selected = state.kind == SearchKind.SONGS,
                onClick = { viewModel.setKind(SearchKind.SONGS) }
            )
            Pill(
                label = "Videos",
                selected = state.kind == SearchKind.VIDEOS,
                onClick = { viewModel.setKind(SearchKind.VIDEOS) }
            )
            Pill(
                label = "Albums",
                selected = state.kind == SearchKind.ALBUMS,
                onClick = { viewModel.setKind(SearchKind.ALBUMS) }
            )
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(bottom = Space.section)
        ) {
            if (!state.submitted) {
                if (state.local.isNotEmpty()) {
                    item { SectionHeader("In your library") }
                    items(state.local, key = { "local-${it.videoId}" }) { track ->
                        TrackRow(
                            title = track.title,
                            subtitle = listOfNotNull(
                                track.artist,
                                formatDuration(track.duration)
                            ).joinToString(" · "),
                            artworkUrl = track.thumbnailUrl,
                            seed = track.videoId,
                            liked = track.videoId in liked,
                            offline = track.cachedPath != null,
                            playing = playback.current?.videoId == track.videoId,
                            onClick = { model.play(state.local, state.local.indexOf(track)) },
                            onMore = { openSheet(TrackSheetRequest(track)) }
                        )
                    }
                }
                if (suggestions.isNotEmpty()) {
                    item { SectionHeader("Suggestions") }
                    items(suggestions, key = { "suggest-$it" }) { suggestion ->
                        SuggestionRow(
                            text = suggestion,
                            icon = Icons.Rounded.Search,
                            onClick = {
                                keyboard?.hide()
                                viewModel.submit(suggestion)
                            }
                        )
                    }
                }
                if (recent.isNotEmpty()) {
                    item {
                        SectionHeader(
                            title = "Recent searches",
                            actionLabel = "Clear",
                            onAction = model::clearSearchHistory
                        )
                    }
                    items(recent, key = { "recent-$it" }) { query ->
                        SuggestionRow(
                            text = query,
                            icon = Icons.Rounded.History,
                            onRemove = { model.removeSearch(query) },
                            onClick = {
                                keyboard?.hide()
                                viewModel.submit(query)
                            }
                        )
                    }
                }
                if (recent.isEmpty() && suggestions.isEmpty() && state.local.isEmpty()) {
                    item {
                        EmptyState(
                            icon = Icons.Rounded.Search,
                            title = "Find something to play",
                            message = "Search YouTube for a song or an artist, or paste a " +
                                "video or playlist link."
                        )
                    }
                }
                return@LazyColumn
            }

            if (state.loading) {
                item { PlaceholderRows(count = 7) }
                return@LazyColumn
            }

            state.error?.let { message ->
                item {
                    ErrorState(
                        message = message,
                        onRetry = if (state.retryable) ({ viewModel.submit() }) else null
                    )
                }
                return@LazyColumn
            }

            if (state.kind == SearchKind.ALBUMS) {
                if (state.albums.isEmpty()) {
                    item {
                        EmptyState(
                            icon = Icons.Rounded.Search,
                            title = "No releases",
                            message = "YouTube Music has no albums matching that."
                        )
                    }
                } else {
                    item {
                        SectionHeader("Releases")
                        Text(
                            text = "From YouTube Music. Opening one plays it and files its " +
                                "tracks under that album.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = Space.page)
                        )
                        Spacer(Modifier.height(Space.row))
                        Shelf {
                            items(state.albums, key = { it.url }) { album ->
                                MediaCard(
                                    title = album.title,
                                    subtitle = listOfNotNull(
                                        album.artist,
                                        album.trackCount.takeIf { it > 0 }?.let { "$it tracks" }
                                    ).joinToString(" · "),
                                    artworkUrl = album.thumbnailUrl,
                                    seed = album.url,
                                    width = 168.dp,
                                    onClick = {
                                        viewModel.openAlbum(album) { tracks ->
                                            if (tracks.isNotEmpty()) model.play(tracks)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
                return@LazyColumn
            }

            if (state.tracks.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Rounded.Search,
                        title = "Nothing found",
                        message = "YouTube returned no playable audio for \"${state.query}\"."
                    )
                }
                return@LazyColumn
            }

            item {
                Row(
                    Modifier.fillMaxWidth().padding(start = Space.page, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${state.tracks.size} results",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { model.play(state.tracks) }) { Text("Play all") }
                    TextButton(onClick = { model.addToQueue(state.tracks) }) { Text("Queue") }
                }
            }

            itemsIndexed(state.tracks, key = { _, track -> track.videoId }) { index, track ->
                TrackRow(
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
                    onClick = { model.play(state.tracks, index) },
                    onMore = { openSheet(TrackSheetRequest(track)) }
                )
            }

            if (state.loadingMore) {
                item {
                    Box(
                        Modifier.fillMaxWidth().padding(Space.page),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun SuggestionRow(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    onRemove: (() -> Unit)? = null
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .padding(start = Space.page, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 52.dp)
                .clickable(onClick = onClick)
                .padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.size(Space.row))
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
        }
        if (onRemove != null) {
            IconButton(onClick = onRemove, modifier = Modifier.size(48.dp)) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = "Remove \"$text\" from recent searches",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
