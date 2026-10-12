package io.github.lectorsys.symus.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import io.github.lectorsys.symus.data.ThemeChoice
import io.github.lectorsys.symus.media.AudioQuality
import io.github.lectorsys.symus.ui.SymusViewModel
import io.github.lectorsys.symus.ui.components.Pill
import io.github.lectorsys.symus.ui.components.formatBytes
import io.github.lectorsys.symus.ui.components.tracksLabel
import io.github.lectorsys.symus.ui.sheets.ConfirmDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.lectorsys.symus.widget.WidgetPinning
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.lectorsys.symus.ui.theme.Space

/**
 * Settings.
 *
 * Every control here changes something the app actually does. Nothing is
 * present because a settings screen is expected to be long — the desktop app's
 * tray, startup and backup options are all absent because none of them means
 * anything on a phone.
 */
@UnstableApi
@Composable
fun SettingsScreen(
    model: SymusViewModel,
    onBack: () -> Unit,
    onNavigate: (String) -> Unit = {}
) {
    val settings by model.settings.collectAsStateWithLifecycle()
    val saved by model.savedIds.collectAsStateWithLifecycle()
    val liked by model.likedIds.collectAsStateWithLifecycle()
    val downloads by model.downloadQueue.collectAsStateWithLifecycle()
    val cache by model.cacheSummary.collectAsStateWithLifecycle()
    var clearingHistory by remember { mutableStateOf(false) }
    var clearingDownloads by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = Space.section)
    ) {
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(start = 4.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Rounded.ArrowBack, contentDescription = "Back")
                }
                Text("Settings", style = MaterialTheme.typography.headlineSmall)
            }
            Spacer(Modifier.height(Space.tight))
        }

        item { Section("Playback") }
        item {
            SwitchRow(
                label = "Autoplay similar tracks",
                detail = "When the queue runs out, keep going with something related.",
                checked = settings.autoplay,
                onChange = model::setAutoplay
            )
        }
        item {
            ChoiceRow(
                label = "Audio quality",
                detail = "Data saver picks the smallest stream that is still music.",
                options = listOf("Best" to AudioQuality.HIGH, "Data saver" to AudioQuality.DATA_SAVER),
                selected = settings.quality,
                onPick = model::setQuality
            )
        }
        item {
            SwitchRow(
                label = "Skip silence",
                detail = "Useful on long talk uploads, pointless on music.",
                checked = settings.skipSilence,
                onChange = model::setSkipSilence
            )
        }
        item {
            SwitchRow(
                label = "Resume the last queue",
                detail = "On launch, put back what was playing and where it had got to.",
                checked = settings.restoreQueue,
                onChange = model::setRestoreQueue
            )
        }

        item { Section("Appearance") }
        item {
            ChoiceRow(
                label = "Theme",
                detail = null,
                options = listOf(
                    "Dark" to ThemeChoice.DARK,
                    "Light" to ThemeChoice.LIGHT,
                    "System" to ThemeChoice.SYSTEM
                ),
                selected = settings.theme,
                onPick = model::setTheme
            )
        }
        item {
            SwitchRow(
                label = "Colour from artwork",
                detail = "Tints the player's backdrop with a colour taken from the cover.",
                checked = settings.artworkColors,
                onChange = model::setArtworkColors
            )
        }
        item {
            InfoRow(
                label = "Themes from your photos",
                detail = "Build a palette from a picture, optionally use it as a wallpaper."
            ) {
                TextButton(onClick = { onNavigate("appearance") }) { Text("Open") }
            }
        }

        item { Section("Home screen") }
        item { WidgetRows() }

        item { Section("Storage") }
        item {
            SwitchRow(
                label = "Download on wi-fi only",
                detail = "Queued downloads wait for an unmetered connection.",
                checked = settings.downloadOnWifiOnly,
                onChange = model::setDownloadOnWifiOnly
            )
        }
        item {
            InfoRow(
                label = "Offline audio",
                detail = "${tracksLabel(cache.count)} · ${formatBytes(cache.bytes)}" +
                    if (downloads.isNotEmpty()) " · ${downloads.size} in the queue" else ""
            ) {
                // Confirmed here too. This deletes files off the device, and the
                // same action on the Downloads screen has always asked first.
                OutlinedButton(
                    onClick = { clearingDownloads = true },
                    enabled = cache.count > 0
                ) { Text("Remove all") }
            }
        }

        item { Section("Assistant") }
        item {
            InfoRow(
                label = "Assistant model",
                detail = "Conversation mode runs a language model on this device"
            ) {
                OutlinedButton(onClick = { onNavigate("model") }) { Text("Manage") }
            }
        }

        item { Section("Backup") }
        item { BackupRows(model) }

        item { Section("History") }
        item {
            SwitchRow(
                label = "Record what I play",
                detail = "Off stops writing history — recommendations and charts stop growing.",
                checked = settings.historyEnabled,
                onChange = model::setHistoryEnabled
            )
        }
        item {
            InfoRow(label = "Listening history", detail = "Plays, skips and resume points") {
                OutlinedButton(onClick = { clearingHistory = true }) { Text("Clear") }
            }
        }
        item {
            InfoRow(label = "Search history", detail = "Recent queries") {
                OutlinedButton(onClick = model::clearSearchHistory) { Text("Clear") }
            }
        }

        item { Section("About") }
        item {
            InfoRow(
                label = "SYmuS 0.1.0",
                detail = "${saved.size} saved, ${liked.size} liked. Your library, " +
                    "history and playlists are stored only on this device."
            )
        }
        item {
            Text(
                text = "Audio comes from YouTube through NewPipeExtractor, which is compiled " +
                    "into the app. If YouTube changes how its pages work, playback can break " +
                    "until a new version ships.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Space.page, vertical = Space.tight)
            )
        }
        item { Section("What leaves this device") }
        item {
            // Named one by one, because "private" is a claim and this is a
            // list. There is no account and no analytics, and nothing here
            // carries an identifier the app invented — but the app is a
            // streaming client, and a streaming client makes requests.
            Text(
                text = "No account, no analytics, and nothing about you is sent anywhere. " +
                    "The app does make these requests, all of them directly from your phone:\n\n" +
                    "• YouTube — searching, browsing, and streaming or downloading audio. " +
                    "Your search terms go to YouTube because that is what a search is.\n" +
                    "• LRCLIB — the track title and artist, to look up lyrics, when you " +
                    "open the lyrics pane.\n" +
                    "• Spotify or Apple Music — only if you configure them under Import, " +
                    "and only for the playlist you asked to import.\n" +
                    "• Hugging Face — only when you choose to download an assistant model.\n\n" +
                    "Your library, playlists, listening history and search history are " +
                    "written only to this device's storage and are never sent anywhere. " +
                    "The assistant runs entirely on this device and has no network access " +
                    "of any kind.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Space.page, vertical = Space.tight)
            )
        }
    }

    if (clearingDownloads) {
        ConfirmDialog(
            title = "Remove all downloads?",
            message = "Frees ${formatBytes(cache.bytes)}. The tracks stay in your library " +
                "and stream as usual.",
            confirmLabel = "Remove",
            onDismiss = { clearingDownloads = false },
            onConfirm = {
                model.removeAllDownloads()
                clearingDownloads = false
            }
        )
    }

    if (clearingHistory) {
        ConfirmDialog(
            title = "Clear history?",
            message = "This deletes every play and skip. Recommendations and your charts " +
                "are built from them, so both will reset.",
            confirmLabel = "Clear",
            onDismiss = { clearingHistory = false },
            onConfirm = {
                model.clearHistory()
                clearingHistory = false
            }
        )
    }
}

@Composable
private fun Section(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = Space.page, top = Space.page, bottom = 4.dp)
    )
}

@Composable
private fun SwitchRow(
    label: String,
    detail: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            // The whole row is the toggle, so the label and its explanation are
            // what a screen reader announces and what a finger can hit. The
            // Switch itself takes no click: two nested targets would read as two
            // controls, and the bare one announced as an unnamed toggle.
            .toggleable(
                value = checked,
                onValueChange = onChange,
                role = Role.Switch
            )
            .padding(horizontal = Space.page, vertical = Space.tight),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            if (detail != null) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.width(Space.row))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun <T> ChoiceRow(
    label: String,
    detail: String?,
    options: List<Pair<String, T>>,
    selected: T,
    onPick: (T) -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.page, vertical = Space.tight)
    ) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        if (detail != null) {
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(Space.tight))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (text, value) ->
                Pill(label = text, selected = value == selected, onClick = { onPick(value) })
            }
        }
    }
}

@Composable
private fun InfoRow(
    label: String,
    detail: String?,
    action: @Composable (() -> Unit)? = null
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(horizontal = Space.page, vertical = Space.tight),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            if (detail != null) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        action?.invoke()
    }
}

/**
 * Adding and editing home-screen widgets from inside the app.
 *
 * Two reasons this exists rather than leaving it all to the launcher's tray.
 * Reconfiguring a placed widget from the launcher only works on Android 12 and
 * up; below that this is the only route to it at all. And the pin request is
 * one tap, against a widget tray many people have never opened.
 *
 * Recomputed on every resume because both answers change outside the app: a
 * widget can be dragged off the home screen while this screen is in the
 * background, and the whole section is wrong the moment it is.
 */
@Composable
private fun WidgetRows() {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    fun currentlyPlaced() = WidgetPinning.Kind.entries.flatMap { kind ->
        WidgetPinning.placed(context, kind).map { kind to it }
    }

    // Seeded from the first composition rather than waiting for an ON_RESUME
    // callback. This composable is reached while the Activity is *already*
    // resumed, and a lifecycle observer registered after the fact never
    // receives the event it missed — which left both buttons permanently
    // disabled and the placed-widget list permanently empty.
    var placed by remember { mutableStateOf(currentlyPlaced()) }
    var pinSupported by remember { mutableStateOf(WidgetPinning.isSupported(context)) }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                pinSupported = WidgetPinning.isSupported(context)
                placed = currentlyPlaced()
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    Column {
        InfoRow(
            label = "Now playing widget",
            detail = if (pinSupported) {
                "Track, artwork and controls."
            } else {
                "Add it from your launcher's widget list — this launcher cannot add it for you."
            },
            action = {
                TextButton(
                    onClick = { WidgetPinning.request(context, WidgetPinning.Kind.NOW_PLAYING) },
                    enabled = pinSupported
                ) { Text("Add") }
            }
        )
        InfoRow(
            label = "Playlist widget",
            detail = "One playlist, one tap. Pick the playlist when you add it.",
            action = {
                TextButton(
                    onClick = { WidgetPinning.request(context, WidgetPinning.Kind.PLAYLIST) },
                    enabled = pinSupported
                ) { Text("Add") }
            }
        )

        if (placed.isNotEmpty()) {
            placed.forEachIndexed { index, (kind, id) ->
                InfoRow(
                    label = when (kind) {
                        WidgetPinning.Kind.NOW_PLAYING -> "Now playing widget ${index + 1}"
                        WidgetPinning.Kind.PLAYLIST -> "Playlist widget ${index + 1}"
                    },
                    detail = "Appearance, background and behaviour for this one widget.",
                    action = {
                        TextButton(onClick = { WidgetPinning.configure(context, id) }) {
                            Text("Edit")
                        }
                    }
                )
            }
        }
    }
}

/**
 * Taking a copy of everything, and putting one back.
 *
 * Both sides go through the system document picker, so the file lands wherever
 * the user keeps things and the app never asks for storage permission. The
 * wording says what is in the file *before* they choose where to put it —
 * discovering that the audio is not included at restore time is too late.
 */
@UnstableApi
@Composable
private fun BackupRows(model: SymusViewModel) {
    val context = LocalContext.current

    val saver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        model.exportBackup { json ->
            withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { stream ->
                        stream.write(json.toByteArray())
                    } != null
                }.getOrDefault(false)
            }
        }
    }

    val opener = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        model.restoreBackup {
            withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                }.getOrNull()
            }
        }
    }

    Column {
        InfoRow(
            label = "Save a backup",
            detail = "Your library, playlists, likes, history and settings as one " +
                "file. Downloaded audio is not included — it re-downloads."
        ) {
            OutlinedButton(
                onClick = { saver.launch("symus-backup.json") }
            ) { Text("Save") }
        }
        InfoRow(
            label = "Restore a backup",
            detail = "Merges the file in. Nothing already on this phone is removed."
        ) {
            OutlinedButton(
                onClick = { opener.launch(arrayOf("application/json", "text/plain", "*/*")) }
            ) { Text("Restore") }
        }
    }
}
