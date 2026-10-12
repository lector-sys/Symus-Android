package io.github.lectorsys.symus.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lectorsys.symus.ai.LlmAvailability
import io.github.lectorsys.symus.ai.ModelCatalog
import io.github.lectorsys.symus.ai.ModelSpec
import io.github.lectorsys.symus.ai.ModelState
import io.github.lectorsys.symus.ui.sheets.ConfirmDialog
import io.github.lectorsys.symus.ui.theme.Space

/**
 * Installing, replacing and removing the model that powers conversation mode.
 *
 * Everything that costs the user something is stated before it is spent: the
 * download size in megabytes, the licence the weights carry, where they come
 * from, and what the device would need to run them. A half-gigabyte download
 * that starts on a tap and explains itself afterwards is not a feature, it is
 * a surprise on someone's data plan.
 */
@Composable
fun ModelScreen(
    onBack: () -> Unit,
    viewModel: ModelViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(viewModel::import) }

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(start = 4.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = "Back")
            }
            Text("Assistant model", style = MaterialTheme.typography.headlineSmall)
        }

        LazyColumn(contentPadding = PaddingValues(bottom = Space.section)) {
            item {
                Column(Modifier.padding(horizontal = Space.page)) {
                    Text(
                        "Conversation mode runs a language model on this device. " +
                            "Nothing you ask is sent anywhere, and the app never falls back " +
                            "to a server when the model is missing — the mode is simply " +
                            "unavailable until one is installed.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(Space.row))
                    Unsupported(state.availability)
                    Spacer(Modifier.height(Space.row))
                }
            }

            when (val install = state.install) {
                is ModelState.Downloading -> item {
                    Progress(
                        label = "Downloading",
                        detail = "${install.bytes / 1_000_000} MB of ${install.total / 1_000_000} MB",
                        fraction = install.fraction,
                        onCancel = viewModel::cancel
                    )
                }

                is ModelState.Verifying -> item {
                    Progress(
                        label = "Checking the file",
                        detail = "Comparing the whole download against its published checksum",
                        fraction = null,
                        onCancel = null
                    )
                }

                is ModelState.Importing -> item {
                    Progress(
                        label = "Importing",
                        detail = "${install.bytes / 1_000_000} MB copied",
                        fraction = null,
                        onCancel = viewModel::cancel
                    )
                }

                is ModelState.Installed -> item {
                    Installed(
                        name = install.spec?.name ?: "Imported model",
                        detail = buildString {
                            append("${install.bytes / 1_000_000} MB")
                            install.spec?.let { append(" · ").append(it.license) }
                            append(if (install.spec != null) " · checksum verified" else " · imported, not checksummed")
                        },
                        loaded = state.loadedName,
                        error = state.loadError,
                        onLoad = viewModel::load,
                        onDelete = { confirmDelete = true }
                    )
                }

                is ModelState.Failed -> item {
                    Column(Modifier.padding(horizontal = Space.page)) {
                        Text(
                            install.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        if (install.partialBytes > 0) {
                            Spacer(Modifier.height(Space.tight))
                            TextButton(onClick = viewModel::discardPartial) {
                                Text("Discard the ${install.partialBytes / 1_000_000} MB kept")
                            }
                        }
                        Spacer(Modifier.height(Space.row))
                    }
                }

                ModelState.Absent -> Unit
            }

            if (state.install !is ModelState.Installed) {
                items(ModelCatalog.entries.size) { index ->
                    val spec = ModelCatalog.entries[index]
                    Candidate(
                        spec = spec,
                        enabled = state.availability is LlmAvailability.Ready &&
                            state.install !is ModelState.Downloading,
                        partialBytes = state.partial[spec.id] ?: 0L,
                        onDownload = { viewModel.download(spec) }
                    )
                }

                item {
                    Column(Modifier.padding(Space.page)) {
                        Text(
                            "Already have a bundle?",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Any MediaPipe .task bundle can be imported. It is not checksummed, " +
                                "because nobody published a checksum for it — whether it works is " +
                                "proven by loading it.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(Space.tight))
                        OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }) {
                            Text("Import a file")
                        }
                    }
                }
            }

            item {
                Column(Modifier.padding(horizontal = Space.page)) {
                    Spacer(Modifier.height(Space.row))
                    Text(
                        "Free space: ${state.freeBytes / 1_000_000} MB",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "Remove the model?",
            message = "Conversation mode stops working until another is installed. " +
                "Statistics mode is unaffected. The file will have to be downloaded again.",
            confirmLabel = "Remove",
            onConfirm = {
                viewModel.delete()
                confirmDelete = false
            },
            onDismiss = { confirmDelete = false }
        )
    }
}

/** Why this device cannot, when it cannot. Never a silently disabled button. */
@Composable
private fun Unsupported(availability: LlmAvailability) {
    val message = when (availability) {
        is LlmAvailability.UnsupportedAbi ->
            "This device's processor (${availability.abis.joinToString()}) has no build of " +
                "the inference runtime. Everything else in the app works normally."

        is LlmAvailability.NotEnoughMemory ->
            "This needs about ${availability.needBytes / (1024 * 1024 * 1024)} GB of RAM and " +
                "this device has ${availability.haveBytes / (1024 * 1024 * 1024)} GB. " +
                "Statistics mode works regardless."

        is LlmAvailability.RuntimeMissing -> availability.message
        LlmAvailability.Ready -> null
    }
    if (message != null) {
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error
        )
    }
}

@Composable
private fun Progress(label: String, detail: String, fraction: Float?, onCancel: (() -> Unit)?) {
    Column(Modifier.padding(horizontal = Space.page)) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(Space.tight))
        if (fraction != null) {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        if (onCancel != null) {
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
        Spacer(Modifier.height(Space.row))
    }
}

@Composable
private fun Installed(
    name: String,
    detail: String,
    loaded: String?,
    error: String?,
    onLoad: () -> Unit,
    onDelete: () -> Unit
) {
    Column(Modifier.padding(horizontal = Space.page)) {
        Text(name, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (loaded != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                "Loaded and ready",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        if (error != null) {
            Spacer(Modifier.height(4.dp))
            Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(Space.tight))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.tight)) {
            if (loaded == null) {
                Button(onClick = onLoad) { Text("Load it now") }
            }
            OutlinedButton(onClick = onDelete) { Text("Remove") }
        }
        Spacer(Modifier.height(Space.row))
    }
}

@Composable
private fun Candidate(
    spec: ModelSpec,
    enabled: Boolean,
    partialBytes: Long,
    onDownload: () -> Unit
) {
    Column(Modifier.padding(horizontal = Space.page, vertical = Space.tight)) {
        Text(spec.name, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            "${spec.sizeLabel} · ${spec.parameters} · ${spec.quantisation} · ${spec.license}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "Needs about ${spec.minRamBytes / (1024 * 1024 * 1024)} GB of RAM",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (partialBytes > 0) {
            Text(
                "${partialBytes / 1_000_000} MB already downloaded — this resumes",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(Modifier.height(Space.tight))
        Button(onClick = onDownload, enabled = enabled) {
            Text(if (partialBytes > 0) "Resume" else "Download")
        }
    }
}
