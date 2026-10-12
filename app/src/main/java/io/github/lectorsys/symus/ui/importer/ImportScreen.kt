package io.github.lectorsys.symus.ui.importer

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lectorsys.symus.data.ImportItemEntity
import io.github.lectorsys.symus.data.ImportJobEntity
import io.github.lectorsys.symus.data.ImportState
import io.github.lectorsys.symus.data.MatchState
import io.github.lectorsys.symus.library.ImportCounts
import io.github.lectorsys.symus.library.Provider
import io.github.lectorsys.symus.ui.theme.Space
import io.github.lectorsys.symus.library.setupInstructions
import io.github.lectorsys.symus.library.ProviderAccess
import androidx.core.net.toUri
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import android.content.Intent

/**
 * Bringing a playlist in from somewhere else.
 *
 * The screen is arranged around the thing that actually determines whether an
 * import is any good: the review. Matching a title to a YouTube upload is a
 * guess, and this is candid about which rows are guesses — a row that was
 * matched on a shared identity, a row that was matched on a good score, a row
 * that is uncertain, and a row that was not found are four different states and
 * are shown as four different states. Nothing becomes a playlist until the user
 * presses the button.
 */
@Composable
fun ImportScreen(
    onBack: () -> Unit,
    onOpenPlaylist: (Long) -> Unit,
    viewModel: ImportViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val jobs by viewModel.jobs.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(viewModel::importFile) }

    Column(Modifier.fillMaxWidth().imePadding()) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(start = 4.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { if (state.openJobId != null) viewModel.close() else onBack() }) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = "Back")
            }
            Text("Import", style = MaterialTheme.typography.headlineSmall)
        }

        val openJob = state.job
        if (state.openJobId != null && openJob != null) {
            Review(
                job = openJob,
                counts = state.counts,
                items = state.items,
                busy = state.busy,
                message = state.message,
                onChoose = viewModel::choose,
                onRetry = viewModel::retryUnresolved,
                onCommit = { viewModel.commit(onOpenPlaylist) }
            )
            return@Column
        }

        LazyColumn(contentPadding = PaddingValues(bottom = Space.section)) {
            item {
                Column(Modifier.padding(horizontal = Space.page)) {
                    Text(
                        "Paste a YouTube, Spotify or Apple Music playlist link, or " +
                            "import a playlist you exported as CSV, M3U or text. " +
                            "Spotify and Apple Music need credentials of your own - " +
                            "see Connections.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(Space.row))

                    OutlinedTextField(
                        value = state.link,
                        onValueChange = viewModel::setLink,
                        placeholder = { Text("Playlist link") },
                        singleLine = true,
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(Space.tight))
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.tight)) {
                        Button(
                            onClick = viewModel::submitLink,
                            enabled = state.link.isNotBlank() && !state.busy
                        ) { Text("Import link") }
                        OutlinedButton(
                            onClick = {
                                // Deliberately broad: exporters produce .csv,
                                // .txt and .m3u, and half of them are served
                                // with the wrong MIME type.
                                filePicker.launch(arrayOf("*/*"))
                            },
                            enabled = !state.busy
                        ) { Text("Import a file") }
                    }

                    state.message?.let {
                        Spacer(Modifier.height(Space.tight))
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    state.blocked?.let { (provider, reason) -> Blocked(provider, reason) }

                    Spacer(Modifier.height(Space.row))
                    TextButton(onClick = viewModel::toggleConnections) {
                        Text(if (state.showConnections) "Hide connections" else "Connections")
                    }
                    if (state.showConnections) {
                        Connections(
                            access = state.access,
                            onSpotifyClientId = viewModel::setSpotifyClientId,
                            onSpotifySignIn = {
                                viewModel.spotifySignInUrl { url ->
                                    runCatching {
                                        context.startActivity(
                                            Intent(Intent.ACTION_VIEW, url.toUri())
                                        )
                                    }
                                }
                            },
                            onSpotifySignOut = viewModel::spotifySignOut,
                            onAppleToken = viewModel::setAppleDeveloperToken,
                            onAppleStorefront = viewModel::setAppleStorefront,
                            onAppleUserToken = viewModel::setAppleUserToken
                        )
                    }

                    Spacer(Modifier.height(Space.section))
                }
            }

            if (jobs.isNotEmpty()) {
                item {
                    Text(
                        "Imports",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = Space.page)
                    )
                    Spacer(Modifier.height(Space.tight))
                }
                items(jobs.size) { index ->
                    val job = jobs[index]
                    JobRow(
                        job = job,
                        onOpen = { viewModel.open(job.id) },
                        onForget = { viewModel.forget(job.id) }
                    )
                }
            }
        }
    }
}

/**
 * A provider that was recognised and cannot be reached.
 *
 * Says what it would take and offers the route that works, because "not
 * supported" leaves the user guessing whether they did something wrong.
 */
@Composable
private fun Blocked(provider: Provider, reason: String) {
    Spacer(Modifier.height(Space.row))
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(Space.row)
    ) {
        Text(
            "${provider.label} links cannot be imported directly",
            style = MaterialTheme.typography.titleSmall
        )
        Spacer(Modifier.height(Space.tight))
        Text(
            reason,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun JobRow(job: ImportJobEntity, onOpen: () -> Unit, onForget: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = Space.page, vertical = Space.tight),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(job.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Text(
                text = buildString {
                    append(Provider.of(job.provider).label)
                    append(" · ")
                    append(
                        when (job.state) {
                            ImportState.DONE -> "added to your library"
                            ImportState.FAILED -> job.error ?: "failed"
                            ImportState.REVIEW -> "waiting for review"
                            ImportState.MATCHING -> "matching ${job.fetchedTracks} tracks"
                            ImportState.FETCHING -> "reading the playlist"
                            ImportState.CANCELLED -> "cancelled"
                            ImportState.IMPORTING -> "adding"
                            ImportState.PENDING -> "not started"
                        }
                    )
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2
            )
        }
        TextButton(onClick = onForget) { Text("Remove") }
    }
}

@Composable
private fun Review(
    job: ImportJobEntity,
    counts: ImportCounts?,
    items: List<ImportItemEntity>,
    busy: Boolean,
    message: String?,
    onChoose: (Long, String?) -> Unit,
    onRetry: () -> Unit,
    onCommit: () -> Unit
) {
    LazyColumn(contentPadding = PaddingValues(bottom = Space.section)) {
        item {
            Column(Modifier.padding(horizontal = Space.page)) {
                Text(job.title, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(Space.tight))

                if (counts != null) {
                    if (counts.pending > 0) {
                        Text(
                            "Matched ${counts.done} of ${counts.total}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(Space.tight))
                        LinearProgressIndicator(
                            progress = {
                                if (counts.total == 0) 0f
                                else counts.done.toFloat() / counts.total.toFloat()
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Text(
                            buildString {
                                append("${counts.matched} of ${counts.total} matched")
                                if (counts.needsAttention > 0) {
                                    append(" · ${counts.needsAttention} need a look")
                                }
                            },
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                if (job.state == ImportState.DONE) {
                    Spacer(Modifier.height(Space.tight))
                    Text(
                        "Added to your library. Importing again updates the same playlist.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                job.error?.let {
                    Spacer(Modifier.height(Space.tight))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                message?.let {
                    Spacer(Modifier.height(Space.tight))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Spacer(Modifier.height(Space.row))
                Row(horizontalArrangement = Arrangement.spacedBy(Space.tight)) {
                    Button(
                        onClick = onCommit,
                        // Gated on the job, not on a screen-wide busy flag: the
                        // matching loop keeps that flag alive long after there
                        // is something worth committing, and a button that
                        // stays grey with "5 of 5 matched" next to it reads as
                        // broken.
                        enabled = job.state != ImportState.IMPORTING &&
                            (counts?.matched ?: 0) > 0 &&
                            (counts?.pending ?: 0) == 0
                    ) {
                        Text(if (job.state == ImportState.DONE) "Update playlist" else "Add to library")
                    }
                    if ((counts?.unresolved ?: 0) > 0) {
                        OutlinedButton(onClick = onRetry) { Text("Search again") }
                    }
                }
                Spacer(Modifier.height(Space.row))
            }
        }

        items(items.size) { index ->
            ItemRow(item = items[index], onClear = { onChoose(items[index].id, null) })
        }
    }
}

/**
 * One source row and what became of it.
 *
 * The state is spelled out rather than colour-coded alone: "not found" and
 * "check this one" are different problems with different fixes, and a user
 * scanning two hundred rows needs to be able to find the second kind.
 */
@Composable
private fun ItemRow(item: ImportItemEntity, onClear: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Space.page, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Text(
                text = listOfNotNull(item.artist, item.album).joinToString(" · ")
                    .ifEmpty { "No artist in the source" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
        Spacer(Modifier.width(Space.tight))
        StateChip(item.matchState)
        if (item.matchedVideoId != null) {
            TextButton(onClick = onClear) { Text("Skip") }
        }
    }
}

@Composable
private fun StateChip(state: MatchState) {
    val (label, tint) = when (state) {
        MatchState.EXACT -> "Exact" to MaterialTheme.colorScheme.primary
        MatchState.LIKELY -> "Matched" to MaterialTheme.colorScheme.primary
        MatchState.UNCERTAIN -> "Check" to MaterialTheme.colorScheme.tertiary
        MatchState.UNRESOLVED -> "Not found" to MaterialTheme.colorScheme.error
        MatchState.REJECTED -> "Skipped" to MaterialTheme.colorScheme.onSurfaceVariant
        MatchState.PENDING -> "Waiting" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = tint
    )
}

/**
 * What each credentialled provider needs, and where it currently stands.
 *
 * Deliberately specific. "Not connected" would cover a missing client id, a
 * lapsed sign-in and a Spotify app that has not had this account added to it,
 * and only one of those is fixed by pressing Sign in.
 */
@Composable
private fun Connections(
    access: Map<Provider, ProviderAccess>,
    onSpotifyClientId: (String) -> Unit,
    onSpotifySignIn: () -> Unit,
    onSpotifySignOut: () -> Unit,
    onAppleToken: (String) -> Unit,
    onAppleStorefront: (String) -> Unit,
    onAppleUserToken: (String) -> Unit
) {
    var clientId by remember { mutableStateOf("") }
    var appleToken by remember { mutableStateOf("") }
    var storefront by remember { mutableStateOf("") }
    var userToken by remember { mutableStateOf("") }

    Column(Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(Space.row))

        AccessState(Provider.SPOTIFY, access[Provider.SPOTIFY])
        OutlinedTextField(
            value = clientId,
            onValueChange = { clientId = it },
            label = { Text("Spotify client id") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(Space.tight))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.tight)) {
            Button(
                onClick = {
                    onSpotifyClientId(clientId)
                    clientId = ""
                },
                enabled = clientId.isNotBlank()
            ) { Text("Save id") }
            OutlinedButton(onClick = onSpotifySignIn) { Text("Sign in") }
            TextButton(onClick = onSpotifySignOut) { Text("Sign out") }
        }

        Spacer(Modifier.height(Space.section))

        AccessState(Provider.APPLE, access[Provider.APPLE])
        OutlinedTextField(
            value = appleToken,
            onValueChange = { appleToken = it },
            label = { Text("Apple developer token, never the .p8 key") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(Space.tight))
        Button(
            onClick = {
                onAppleToken(appleToken)
                appleToken = ""
            },
            enabled = appleToken.isNotBlank()
        ) { Text("Save token") }

        Spacer(Modifier.height(Space.tight))
        OutlinedTextField(
            value = storefront,
            onValueChange = { storefront = it },
            label = { Text("Storefront, e.g. us, gb, de") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(Space.tight))
        OutlinedTextField(
            value = userToken,
            onValueChange = { userToken = it },
            label = { Text("Music User Token, only for your own library playlists") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(Space.tight))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.tight)) {
            OutlinedButton(
                onClick = {
                    onAppleStorefront(storefront)
                    storefront = ""
                },
                enabled = storefront.isNotBlank()
            ) { Text("Save storefront") }
            OutlinedButton(
                onClick = {
                    onAppleUserToken(userToken)
                    userToken = ""
                },
                enabled = userToken.isNotBlank()
            ) { Text("Save user token") }
        }
    }
}

/**
 * One provider's state in three lines: what it is, where it stands, what to do.
 */
@Composable
private fun AccessState(provider: Provider, access: ProviderAccess?) {
    val headline: String
    val detail: String
    val tone: androidx.compose.ui.graphics.Color

    when (access) {
        is ProviderAccess.Ready -> {
            headline = "Connected"
            detail = access.account ?: "Ready to import"
            tone = MaterialTheme.colorScheme.primary
        }

        is ProviderAccess.NeedsConfiguration -> {
            headline = "Needs ${access.what}"
            detail = access.how
            tone = MaterialTheme.colorScheme.onSurfaceVariant
        }

        is ProviderAccess.NeedsSignIn -> {
            headline = "Not signed in"
            detail = access.why
            tone = MaterialTheme.colorScheme.onSurfaceVariant
        }

        is ProviderAccess.Restricted -> {
            headline = access.reason
            detail = access.remedy
            tone = MaterialTheme.colorScheme.error
        }

        is ProviderAccess.Failed -> {
            headline = "Unavailable"
            detail = access.message
            tone = MaterialTheme.colorScheme.error
        }

        null -> {
            headline = "Checking"
            detail = provider.setupInstructions()
            tone = MaterialTheme.colorScheme.onSurfaceVariant
        }
    }

    Text(provider.label, style = MaterialTheme.typography.titleSmall)
    Text(headline, style = MaterialTheme.typography.labelLarge, color = tone)
    Spacer(Modifier.height(4.dp))
    Text(
        detail,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(Space.tight))
}
