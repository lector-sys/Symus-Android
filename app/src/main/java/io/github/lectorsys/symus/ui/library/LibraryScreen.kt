package io.github.lectorsys.symus.ui.library

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.QuestionAnswer
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.lectorsys.symus.data.AlbumSummary
import io.github.lectorsys.symus.data.ArtistCount
import io.github.lectorsys.symus.data.GenreCount
import io.github.lectorsys.symus.data.LibraryRepository
import io.github.lectorsys.symus.media.Downloads
import io.github.lectorsys.symus.ui.SymusViewModel
import io.github.lectorsys.symus.ui.components.EmptyState
import io.github.lectorsys.symus.ui.components.MediaCard
import io.github.lectorsys.symus.ui.components.Pill
import io.github.lectorsys.symus.ui.components.SectionHeader
import io.github.lectorsys.symus.ui.components.Shelf
import io.github.lectorsys.symus.ui.components.formatBytes
import io.github.lectorsys.symus.ui.sheets.NamePlaylistDialog
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
 * The library: the four lists everybody has, then playlists, then the ways in
 * through genre, release and artist.
 *
 * Ordered by how often each is opened rather than by how much data is behind it
 * — liked songs first, artists last.
 */
@UnstableApi
@Composable
fun LibraryScreen(
    model: SymusViewModel,
    onNavigate: (String) -> Unit,
    viewModel: LibraryViewModel = hiltViewModel()
) {
    val playlists by model.playlists.collectAsStateWithLifecycle()
    val liked by model.likedIds.collectAsStateWithLifecycle()
    val saved by model.savedIds.collectAsStateWithLifecycle()
    val genres by viewModel.genres.collectAsStateWithLifecycle()
    val cache by viewModel.cache.collectAsStateWithLifecycle()
    val extras by viewModel.extras.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }

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
                Text(
                    text = "Your library",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { creating = true }) {
                    Icon(Icons.Rounded.Add, contentDescription = "New playlist")
                }
            }
            Spacer(Modifier.height(Space.tight))
        }

        item {
            Column(Modifier.padding(horizontal = Space.page)) {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.row)) {
                    LibraryTile(
                        icon = Icons.Rounded.Favorite,
                        label = "Liked songs",
                        detail = tracksLabel(liked.size),
                        modifier = Modifier.weight(1f),
                        onClick = { onNavigate("liked") }
                    )
                    LibraryTile(
                        icon = Icons.Rounded.MusicNote,
                        label = "Saved",
                        detail = tracksLabel(saved.size),
                        modifier = Modifier.weight(1f),
                        onClick = { onNavigate("saved") }
                    )
                }
                Spacer(Modifier.height(Space.row))
                Row(horizontalArrangement = Arrangement.spacedBy(Space.row)) {
                    LibraryTile(
                        icon = Icons.Rounded.Download,
                        label = "Downloads",
                        detail = if (cache.count == 0) "Nothing offline"
                        else "${tracksLabel(cache.count)} · ${formatBytes(cache.bytes)}",
                        modifier = Modifier.weight(1f),
                        onClick = { onNavigate("downloads") }
                    )
                    LibraryTile(
                        icon = Icons.Rounded.History,
                        label = "History",
                        detail = "Everything you played",
                        modifier = Modifier.weight(1f),
                        onClick = { onNavigate("history") }
                    )
                }
                Spacer(Modifier.height(Space.row))
                Row(horizontalArrangement = Arrangement.spacedBy(Space.row)) {
                    LibraryTile(
                        icon = Icons.Rounded.BarChart,
                        label = "Listening",
                        detail = "Counted, not estimated",
                        modifier = Modifier.weight(1f),
                        onClick = { onNavigate("listening") }
                    )
                    LibraryTile(
                        icon = Icons.Rounded.QuestionAnswer,
                        label = "Assistant",
                        detail = "Ask about your library",
                        modifier = Modifier.weight(1f),
                        onClick = { onNavigate("assistant") }
                    )
                }
                Spacer(Modifier.height(Space.row))
                Row(horizontalArrangement = Arrangement.spacedBy(Space.row)) {
                    LibraryTile(
                        icon = Icons.Rounded.PlaylistAdd,
                        label = "Import",
                        detail = "A link or an export file",
                        modifier = Modifier.weight(1f),
                        onClick = { onNavigate("import") }
                    )
                    Spacer(Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(Space.row))
        }

        item {
            SectionHeader(
                title = "Playlists",
                actionLabel = "New",
                onAction = { creating = true }
            )
        }

        if (playlists.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Rounded.LibraryMusic,
                    title = "No playlists yet",
                    message = "Make one here, or add a track to a new playlist from its menu.",
                    actionLabel = "New playlist",
                    onAction = { creating = true }
                )
            }
        } else {
            items(playlists, key = { it.id }) { playlist ->
                Surface(
                    onClick = { onNavigate("playlist/${playlist.id}") },
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier.padding(horizontal = Space.page, vertical = Space.tight),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        io.github.lectorsys.symus.ui.components.ArtworkSquare(
                            url = playlist.coverUrl,
                            seed = "playlist-${playlist.id}",
                            size = 52.dp
                        )
                        Spacer(Modifier.width(Space.row))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = playlist.title,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = buildString {
                                    append(tracksLabel(playlist.count))
                                    playlist.genre?.let { append(" · $it") }
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        if (genres.isNotEmpty()) {
            item {
                SectionHeader(
                    title = "Genres",
                    actionLabel = "Moods",
                    onAction = { onNavigate("moods") }
                )
                Shelf {
                    items(genres, key = { it.genre }) { genre ->
                        Pill(
                            label = "${genre.genre} · ${genre.count}",
                            selected = false,
                            // A link to the genre, not one of a set of choices.
                            selectable = false,
                            onClick = {
                                onNavigate("genre/${URLEncoder.encode(genre.genre, "UTF-8")}")
                            }
                        )
                    }
                }
                Spacer(Modifier.height(Space.row))
            }
        }

        if (extras.albums.isNotEmpty()) {
            item {
                SectionHeader("Releases")
                Shelf {
                    items(extras.albums, key = { "${it.name}-${it.artist}" }) { album ->
                        MediaCard(
                            title = album.name,
                            subtitle = listOfNotNull(album.artist, tracksLabel(album.count))
                                .joinToString(" · "),
                            artworkUrl = album.coverUrl,
                            seed = album.name,
                            width = 140.dp,
                            onClick = {
                                onNavigate(
                                    "album/${URLEncoder.encode(album.name, "UTF-8")}" +
                                        "?artist=${URLEncoder.encode(album.artist.orEmpty(), "UTF-8")}"
                                )
                            }
                        )
                    }
                }
                Spacer(Modifier.height(Space.row))
            }
        } else {
            item {
                SectionHeader("Releases")
                Row(Modifier.padding(horizontal = Space.page)) {
                    Icon(
                        Icons.Rounded.Album,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(Space.tight))
                    Text(
                        text = "YouTube only publishes album names on YouTube Music release " +
                            "pages. Open one from the Albums tab in search and it shows up here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(Space.row))
            }
        }

        if (extras.artists.isNotEmpty()) {
            item {
                SectionHeader("Artists")
                Shelf {
                    items(extras.artists, key = { it.artist }) { artist ->
                        MediaCard(
                            title = artist.artist,
                            subtitle = tracksLabel(artist.count),
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

    if (creating) {
        NamePlaylistDialog(
            onDismiss = { creating = false },
            onConfirm = {
                model.createPlaylist(it)
                creating = false
            }
        )
    }
}

@Composable
private fun LibraryTile(
    icon: ImageVector,
    label: String,
    detail: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier
    ) {
        Column(Modifier.padding(Space.row)) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.height(Space.tight))
            Text(label, style = MaterialTheme.typography.titleSmall, maxLines = 1)
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

data class LibraryExtras(
    val albums: List<AlbumSummary> = emptyList(),
    val artists: List<ArtistCount> = emptyList()
)

@UnstableApi
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val library: LibraryRepository,
    downloads: Downloads
) : ViewModel() {

    val genres: StateFlow<List<GenreCount>> = library.genresFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val cache = downloads.summary
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            io.github.lectorsys.symus.data.CacheSummary(0, 0)
        )

    private val _extras = MutableStateFlow(LibraryExtras())
    val extras: StateFlow<LibraryExtras> = _extras.asStateFlow()

    fun load() {
        viewModelScope.launch {
            _extras.value = LibraryExtras(
                albums = library.albums(),
                artists = library.topArtists(20)
            )
        }
    }
}
