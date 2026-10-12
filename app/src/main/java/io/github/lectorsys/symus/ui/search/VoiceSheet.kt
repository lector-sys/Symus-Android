package io.github.lectorsys.symus.ui.search

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lectorsys.symus.media.VoiceState
import io.github.lectorsys.symus.ui.theme.Space

/**
 * The microphone, and what it is doing.
 *
 * Three rules this screen exists to keep:
 *
 * 1. **The state is never ambiguous.** Listening, thinking and failed look
 *    different, and the level meter moves with the user's actual voice so a
 *    dead microphone is visibly dead rather than silently unhelpful.
 * 2. **Nothing is searched behind the user's back.** A transcription lands in
 *    the search field, where it can be corrected before anything is sent. Voice
 *    recognition gets names wrong constantly, and a search that runs on the
 *    wrong words with no way to see them is worse than typing.
 * 3. **Cancelling is always available** and actually releases the microphone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceSheet(
    model: SearchViewModel,
    onDismiss: () -> Unit,
    onTranscribed: (String) -> Unit
) {
    val state by model.voiceState.collectAsStateWithLifecycle()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) model.startListening() else model.voicePermissionDenied()
    }

    LaunchedEffect(Unit) { permission.launch(Manifest.permission.RECORD_AUDIO) }

    // A finished transcription leaves immediately: the editing happens in the
    // search field, which is a better place to correct words than a sheet.
    LaunchedEffect(state) {
        val heard = state as? VoiceState.Heard ?: return@LaunchedEffect
        onTranscribed(heard.text)
        model.consumeVoice()
        onDismiss()
    }

    ModalBottomSheet(
        onDismissRequest = {
            model.cancelListening()
            onDismiss()
        },
        sheetState = sheet
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.page)
                .padding(bottom = Space.section),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Level(state)
            Spacer(Modifier.height(Space.row))

            Text(
                text = when (state) {
                    is VoiceState.Listening -> "Listening"
                    VoiceState.Working -> "One moment"
                    is VoiceState.Heard -> "Got it"
                    is VoiceState.Failed -> "Didn't work"
                    VoiceState.Idle -> "Ready"
                },
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(Space.tight))

            Text(
                text = when (val current = state) {
                    is VoiceState.Listening ->
                        current.partial.ifBlank { "Say an artist, a song, or an album." }
                    VoiceState.Working -> "Working out what you said."
                    is VoiceState.Heard -> current.text
                    is VoiceState.Failed -> current.message
                    VoiceState.Idle -> "Tap the microphone to start."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(Space.tight))
            Text(
                // Said plainly rather than buried: whether audio leaves the
                // device is the user's business.
                text = if (model.voiceIsOnDevice) {
                    "Recognised on this device. No audio is sent anywhere."
                } else {
                    "This device uses Google's recogniser, which sends audio to " +
                        "its servers and needs a connection."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(Space.row))
            Row(horizontalArrangement = Arrangement.spacedBy(Space.tight)) {
                TextButton(onClick = {
                    model.cancelListening()
                    onDismiss()
                }) { Text("Cancel") }

                when (val current = state) {
                    is VoiceState.Listening ->
                        OutlinedButton(onClick = model::stopListening) { Text("Done") }
                    is VoiceState.Failed ->
                        if (current.retryable) {
                            OutlinedButton(onClick = model::startListening) { Text("Try again") }
                        }
                    else -> Unit
                }
            }
        }
    }
}

/**
 * The level meter.
 *
 * Driven by the recogniser's own RMS readings, so it moves with the room. A
 * microphone that is muted or blocked shows a circle that never grows, which is
 * the fastest way for someone to tell that the problem is the hardware and not
 * their pronunciation.
 */
@Composable
private fun Level(state: VoiceState) {
    val target = when (state) {
        is VoiceState.Listening -> 1f + state.rms * 0.5f
        VoiceState.Working -> 1.05f
        else -> 1f
    }
    val scale by animateFloatAsState(targetValue = target, label = "voice-level")

    Box(
        modifier = Modifier.size(96.dp).padding(top = Space.row),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .size(72.dp)
                .scale(scale)
                .clip(CircleShape)
                .background(
                    when (state) {
                        is VoiceState.Failed -> MaterialTheme.colorScheme.errorContainer
                        is VoiceState.Listening -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    }
                )
        )
        Icon(
            imageVector = Icons.Rounded.Mic,
            contentDescription = null,
            tint = when (state) {
                is VoiceState.Listening -> MaterialTheme.colorScheme.onPrimary
                is VoiceState.Failed -> MaterialTheme.colorScheme.onErrorContainer
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(32.dp)
        )
    }
    Spacer(Modifier.width(0.dp))
}
