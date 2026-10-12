package io.github.lectorsys.symus.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.lectorsys.symus.data.ArtistPlays
import io.github.lectorsys.symus.data.GenreCount
import io.github.lectorsys.symus.data.LibraryRepository
import io.github.lectorsys.symus.data.TrackEntity
import io.github.lectorsys.symus.ui.SymusViewModel
import io.github.lectorsys.symus.ui.components.EmptyState
import io.github.lectorsys.symus.ui.components.MediaCard
import io.github.lectorsys.symus.ui.components.Pill
import io.github.lectorsys.symus.ui.components.PlaceholderShelf
import io.github.lectorsys.symus.ui.components.SectionHeader
import io.github.lectorsys.symus.ui.components.Shelf
import io.github.lectorsys.symus.ui.components.tracksLabel
import io.github.lectorsys.symus.ui.theme.Space
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.net.URLEncoder
import javax.inject.Inject

/**
 * Home.
 *
 * Built only from what this library actually contains: what was played, what
 * the scorer makes of it, the playlists that exist, the genres that are tagged.
 * Nothing here is filled with placeholder content to look busy — an empty
 * library gets one clear invitation instead of six rows of nothing.
 */
@UnstableApi
@Composable
fun HomeScreen(
    model: SymusViewModel,
    onNavigate: (String) -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val savedCount by viewModel.savedCount.collectAsStateWithLifecycle()
    val playlists by model.playlists.collectAsStateWithLifecycle()
    val liked by model.likedIds.collectAsStateWithLifecycle()
    val playback by model.playbackState.collectAsStateWithLifecycle()

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
                    Text(
                        text = "SYmuS",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        // Says what is true: playing, paused, or nothing yet.
                        text = playback.current?.let { track ->
                            if (playback.playing) "Playing ${track.title}"
                            else "Paused · ${track.title}"
                        } ?: "Your music, from YouTube, on this device",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = { onNavigate("history") }) {
                    Icon(
                        Icons.Rounded.History,
                        contentDescription = "Listening history",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { onNavigate("settings") }) {
                    Icon(
                        Icons.Rounded.Settings,
                        contentDescription = "Settings",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(Space.tight))
        }

        if (state.loading && state.recent.isEmpty()) {
            item {
                SectionHeader("Loading your library")
                PlaceholderShelf()
            }
        }

        if (state.fresh(savedCount)) {
            item {
                EmptyState(
                    icon = Icons.Rounded.MusicNote,
                    title = "Nothing to play yet",
                    message = "Search for a song, an artist, or paste a YouTube link. " +
                        "What you play and save shows up here.",
                    actionLabel = "Search YouTube",
                    onAction = { onNavigate("search") }
                )
            }
        }

        trackShelf(
            title = "Pick up where you left off",
            tracks = state.recent,
            onPlay = { index -> model.play(state.recent, index) }
        )

        trackShelf(
            title = "Made for you",
            tracks = state.madeForYou,
            subtitle = { it.genre ?: it.artist ?: "Unknown artist" },
            onPlay = { index -> model.play(state.madeForYou, index) }
        )

        if (playlists.isNotEmpty()) {
            item {
                SectionHeader(
                    title = "Your playlists",
                    actionLabel = "All",
                    onAction = { onNavigate("library") }
                )
                Shelf {
                    items(playlists, key = { it.id }) { playlist ->
                        MediaCard(
                            title = playlist.title,
                            subtitle = buildString {
                                append(tracksLabel(playlist.count))
                                playlist.genre?.let { append(" · $it") }
                            },
                            artworkUrl = playlist.coverUrl,
                            seed = "playlist-${playlist.id}",
                            onClick = { onNavigate("playlist/${playlist.id}") }
                        )
                    }
                }
                Spacer(Modifier.height(Space.row))
            }
        }

        if (liked.isNotEmpty() || savedCount > 0) {
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Space.page, vertical = Space.tight),
                    horizontalArrangement = Arrangement.spacedBy(Space.row)
                ) {
                    QuickTile(
                        icon = Icons.Rounded.Favorite,
                        label = "Liked songs",
                        detail = "${liked.size} tracks",
                        modifier = Modifier.weight(1f),
                        onClick = { onNavigate("liked") }
                    )
                    QuickTile(
                        icon = Icons.Rounded.MusicNote,
                        label = "Saved",
                        detail = "$savedCount tracks",
                        modifier = Modifier.weight(1f),
                        onClick = { onNavigate("saved") }
                    )
                }
            }
        }

        trackShelf(
            title = "Most listened",
            tracks = state.mostPlayed,
            onPlay = { index -> model.play(state.mostPlayed, index) }
        )

        if (state.genres.isNotEmpty()) {
            item {
                SectionHeader(
                    title = "Your genres",
                    actionLabel = "Moods",
                    onAction = { onNavigate("moods") }
                )
                Shelf {
                    items(state.genres, key = { it.genre }) { genre ->
                        Pill(
                            label = "${genre.genre} · ${genre.count}",
                            selected = false,
                            // A link to the genre, not one of a set of choices.
                            selectable = false,
                            onClick = { onNavigate("genre/${URLEncoder.encode(genre.genre, "UTF-8")}") }
                        )
                    }
                }
                Spacer(Modifier.height(Space.row))
            }
        }

        if (state.artists.isNotEmpty()) {
            item {
                SectionHeader("Artists you play")
                Shelf {
                    items(state.artists, key = { it.artist }) { artist ->
                        MediaCard(
                            title = artist.artist,
                            subtitle = "${artist.plays} plays",
                            artworkUrl = null,
                            seed = artist.artist,
                            circular = true,
                            initial = artist.artist,
                            width = 120.dp,
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

/** A shelf of tracks, or nothing at all if there are none. */
private fun androidx.compose.foundation.lazy.LazyListScope.trackShelf(
    title: String,
    tracks: List<TrackEntity>,
    subtitle: (TrackEntity) -> String = { it.artist ?: "Unknown artist" },
    onPlay: (Int) -> Unit
) {
    if (tracks.isEmpty()) return
    item {
        SectionHeader(title)
        Shelf {
            items(tracks.size, key = { tracks[it].videoId }) { index ->
                val track = tracks[index]
                MediaCard(
                    title = track.title,
                    subtitle = subtitle(track),
                    artworkUrl = track.thumbnailUrl,
                    seed = track.videoId,
                    onClick = { onPlay(index) }
                )
            }
        }
        Spacer(Modifier.height(Space.row))
    }
}

@Composable
private fun QuickTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    detail: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    androidx.compose.material3.Surface(
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
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.size(Space.tight))
            Column {
                Text(label, style = MaterialTheme.typography.titleSmall)
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

data class HomeState(
    val loading: Boolean = true,
    val recent: List<TrackEntity> = emptyList(),
    val madeForYou: List<TrackEntity> = emptyList(),
    val mostPlayed: List<TrackEntity> = emptyList(),
    val genres: List<GenreCount> = emptyList(),
    val artists: List<ArtistPlays> = emptyList()
) {
    /**
     * Nothing has happened yet.
     *
     * Deliberately not "the tracks table is empty": that table fills with the
     * first search, which would make the first-run screen vanish before anyone
     * had actually done anything with the app.
     */
    fun fresh(savedCount: Int): Boolean =
        !loading && recent.isEmpty() && madeForYou.isEmpty() && savedCount == 0
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val library: LibraryRepository
) : ViewModel() {

    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state.asStateFlow()

    val savedCount: StateFlow<Int> = library.savedCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /**
     * Loaded on every visit rather than observed.
     *
     * Three of these are scored in memory over the whole history, which is not
     * something to recompute on every like — and Home is a screen people arrive
     * at, not one they sit on while the data underneath changes.
     */
    fun load() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            _state.value = HomeState(
                loading = false,
                recent = library.recentlyPlayed(16),
                madeForYou = library.recommended(16),
                mostPlayed = library.mostPlayed(16).map { it.track() },
                genres = library.genres(),
                artists = library.mostPlayedArtists(12)
            )
        }
    }
}
