package io.github.lectorsys.symus.ui.assistant

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import io.github.lectorsys.symus.ai.ClaimKind
import io.github.lectorsys.symus.ai.GroundedAnswer
import io.github.lectorsys.symus.data.TrackEntity
import io.github.lectorsys.symus.library.Answer
import io.github.lectorsys.symus.library.formatListening
import io.github.lectorsys.symus.ui.SymusViewModel
import io.github.lectorsys.symus.ui.components.Pill
import io.github.lectorsys.symus.ui.components.TrackRow
import io.github.lectorsys.symus.ui.theme.Space

/**
 * Questions about your own listening, answered on your own device.
 *
 * Two engines, offered side by side rather than one pretending to be the other.
 * **Statistics** counts rows and cannot invent a figure. **Conversation** runs a
 * real language model on this device, which can hold a thread and phrase an
 * answer — and whose numbers still come from the same counted rows, because the
 * model is never given the arithmetic to do.
 *
 * The distinction is on screen at all times. A user who cannot tell which one
 * answered cannot calibrate how much to trust it, and that is the whole
 * difference between a useful assistant and a plausible one.
 */
@UnstableApi
@Composable
fun AssistantScreen(
    model: SymusViewModel,
    onBack: () -> Unit,
    viewModel: AssistantViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val conversational = state.mode == AssistantMode.CONVERSATION

    Column(Modifier.fillMaxWidth().imePadding()) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(start = 4.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = "Back")
            }
            Text("Assistant", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.weight(1f))
            if (state.history.isNotEmpty()) {
                TextButton(onClick = viewModel::clear) { Text("Clear") }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = Space.page, vertical = Space.tight),
            horizontalArrangement = Arrangement.spacedBy(Space.tight)
        ) {
            Pill(
                label = "Statistics",
                selected = !conversational,
                onClick = { viewModel.setMode(AssistantMode.STATISTICS) }
            )
            Pill(
                label = "Conversation",
                selected = conversational,
                onClick = { viewModel.setMode(AssistantMode.CONVERSATION) }
            )
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = Space.section)
        ) {
            if (state.history.isEmpty()) {
                item { Preamble(state) }
                item {
                    Suggestions(
                        labels = if (conversational) {
                            viewModel.chatSuggestions.map { it to it }
                        } else {
                            viewModel.suggestions.map { it.label to it.question }
                        },
                        onPick = viewModel::ask
                    )
                }
            }

            if (state.thinking) {
                item { Thinking(conversational, viewModel::cancel) }
            }

            items(state.history.size) { index ->
                val exchange = state.history[index]
                when {
                    exchange.grounded != null -> GroundedBlock(
                        question = exchange.question,
                        answer = exchange.grounded,
                        millis = exchange.millis,
                        onPlayIds = viewModel::playRows
                    )

                    exchange.answer != null -> AnswerBlock(
                        question = exchange.question,
                        answer = exchange.answer,
                        onPlay = { tracks, at -> model.play(tracks, at) }
                    )
                }
            }
        }

        OutlinedTextField(
            value = state.draft,
            onValueChange = viewModel::setDraft,
            placeholder = {
                Text(
                    if (conversational) "Ask anything about your library"
                    else "Ask about your listening"
                )
            },
            singleLine = true,
            enabled = !state.thinking,
            shape = MaterialTheme.shapes.large,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { viewModel.ask() }),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.page, vertical = Space.tight)
        )
    }
}

@Composable
private fun Suggestions(labels: List<Pair<String, String>>, onPick: (String) -> Unit) {
    Column {
        Text(
            "Try one of these",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Space.page)
        )
        Spacer(Modifier.height(Space.tight))
        LazyRow(
            contentPadding = PaddingValues(horizontal = Space.page),
            horizontalArrangement = Arrangement.spacedBy(Space.tight)
        ) {
            items(labels) { (label, question) ->
                Box(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .clickable { onPick(question) }
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Text(label, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        Spacer(Modifier.height(Space.section))
    }
}

/**
 * Generation on a phone CPU takes seconds, not milliseconds. Saying which
 * engine is working and offering to stop it is the difference between a wait
 * and a hang.
 */
@Composable
private fun Thinking(conversational: Boolean, onCancel: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Space.page, vertical = Space.row),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.tight)
    ) {
        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        Text(
            if (conversational) "Thinking on this device" else "Counting",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.weight(1f))
        if (conversational) TextButton(onClick = onCancel) { Text("Stop") }
    }
}

/**
 * What this is, before anything is asked — stated separately per engine because
 * the two make different promises and only one of them involves a model.
 */
@Composable
private fun Preamble(state: AssistantUiState) {
    Column(Modifier.padding(horizontal = Space.page)) {
        val summary = state.summary
        if (summary != null && !summary.isEmpty) {
            Text(
                text = "In the last 30 days you listened for " +
                    formatListening(summary.listenedMs) + " across ${summary.plays} plays.",
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(Modifier.height(Space.tight))
        }

        Text(
            text = "Answers come from what this app recorded on this device: your " +
                "play history, your library, your playlists and your downloads. " +
                "Asking a question makes no network request — though the rest of " +
                "the app does reach YouTube to search and play.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(Space.tight))

        if (state.mode == AssistantMode.CONVERSATION) {
            Text(
                text = state.modelName?.let {
                    "$it runs here, on this device. It chooses which of your records to " +
                        "read and puts the answer into words — it never calculates a " +
                        "figure, and every number below came from your own history."
                } ?: "No model is installed yet. Settings → Assistant model has the download.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            state.modelError?.let {
                Spacer(Modifier.height(Space.tight))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        } else {
            Text(
                text = "This mode uses no language model. Every figure is counted out of " +
                    "your history when you ask, so it will say \"not enough recorded\" " +
                    "rather than guess.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(Space.section))
    }
}

/**
 * A model-written answer, with the app's own figures printed underneath it.
 *
 * The figures are not a debugging aid. They are what the answer is checked
 * against, so putting them on screen means the user can check it too — and when
 * [GroundedAnswer.trustworthy] is false, they are the correction.
 */
@UnstableApi
@Composable
private fun GroundedBlock(
    question: String,
    answer: GroundedAnswer,
    millis: Long,
    onPlayIds: (List<String>, Int) -> Unit
) {
    Column(Modifier.padding(horizontal = Space.page, vertical = Space.tight)) {
        Text(
            text = question,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(Space.tight))

        // The figures first, and in the answer's own weight.
        //
        // These are rendered by the app from what the query returned; the model
        // never touches them. Putting them above the prose is the whole point —
        // the number a user reads is the number the database holds, whatever
        // the sentence underneath happens to say.
        if (answer.facts.isNotEmpty()) {
            answer.facts.forEach { fact ->
                Text(
                    text = fact,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(Modifier.height(Space.tight))
        }

        // The model's sentence, as commentary — and only when its numbers check
        // out. A sentence carrying a figure no tool produced is not shown at
        // all: it would sit next to the recorded figures in the same card, and
        // there is no wording that reliably stops it being read as one of them.
        val hasFigures = answer.facts.isNotEmpty()
        val proseWorthShowing =
            answer.text.isNotBlank() && answer.trustworthy && answer.statesFigures
        if (proseWorthShowing) {
            Text(
                text = answer.text,
                style = if (hasFigures) MaterialTheme.typography.bodySmall
                else MaterialTheme.typography.bodyMedium,
                color = if (hasFigures) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface
            )
        } else if (!answer.trustworthy) {
            Text(
                text = "The assistant's wording did not match your records, so it has " +
                    "been left out. The figures above are what your history holds.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        Spacer(Modifier.height(4.dp))
        Text(
            text = buildString {
                append(
                    when (answer.kind) {
                        ClaimKind.RECORDED -> "Recorded"
                        ClaimKind.SUGGESTED -> "Suggestion, not a record"
                        ClaimKind.UNAVAILABLE -> "Not enough recorded"
                    }
                )
                if (answer.sources.isNotEmpty()) {
                    append(" · from ").append(answer.sources.joinToString("; "))
                }
                if (millis > 0) append(" · ${millis}ms on this device")
            },
            style = MaterialTheme.typography.labelSmall,
            fontStyle = FontStyle.Italic,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(Space.tight))
    }

    // Rows carry the real names the model never saw; they are playable because
    // an answer you can act on beats one you can only read.
    val playable = answer.rows.filter { it.videoId != null }
    val ids = playable.mapNotNull { it.videoId }
    playable.forEachIndexed { index, row ->
        TrackRow(
            title = row.label,
            subtitle = row.detail,
            artworkUrl = row.artworkUrl,
            seed = row.videoId.orEmpty(),
            onClick = { onPlayIds(ids, index) }
        )
    }
    Spacer(Modifier.height(Space.row))
}

@UnstableApi
@Composable
private fun AnswerBlock(
    question: String,
    answer: Answer,
    onPlay: (List<TrackEntity>, Int) -> Unit
) {
    Column(Modifier.padding(horizontal = Space.page, vertical = Space.tight)) {
        Text(
            text = question,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(Space.tight))
        Text(
            text = answer.text,
            style = MaterialTheme.typography.bodyMedium
        )

        if (answer.sources.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                // Where it came from, every time. An answer about your own
                // history should be checkable.
                text = "Counted from " + answer.sources.joinToString("; "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(Space.tight))
    }

    answer.tracks.forEachIndexed { index, track ->
        TrackRow(
            title = track.title,
            subtitle = track.artist,
            artworkUrl = track.thumbnailUrl,
            seed = track.videoId,
            onClick = { onPlay(answer.tracks, index) }
        )
    }
    Spacer(Modifier.height(Space.row))
}
