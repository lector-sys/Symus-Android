package io.github.lectorsys.symus.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import io.github.lectorsys.symus.ui.theme.SymusTheme
import io.github.lectorsys.symus.ui.theme.Space

/**
 * The screen a launcher shows when a widget is added, and again when it is
 * edited.
 *
 * Declared as the configure activity for both providers and works out which one
 * it was opened for from the widget id, because the two share every control
 * except the playlist picker. Splitting them would have meant two screens whose
 * appearance sections drift apart.
 *
 * The result convention is the one the system defines and is easy to get
 * subtly wrong: the activity must set RESULT_CANCELED *first*, so that backing
 * out of a fresh widget removes it rather than leaving a half-configured one on
 * the home screen, and RESULT_OK only once the state has actually been written.
 */
@AndroidEntryPoint
class WidgetConfigActivity : ComponentActivity() {

    private val model: WidgetConfigViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val widgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        // Back, swipe away, or a crash all leave this as the answer.
        setResult(Activity.RESULT_CANCELED, resultIntent(widgetId))

        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        model.start(widgetId)

        setContent {
            SymusTheme {
                // Surface, not a bare Column with a background colour. Surface
                // is what provides LocalContentColor; without it every Text
                // that does not name its own colour falls back to the
                // CompositionLocal default, which is black — and that is how
                // this screen's title came to be black on black while every
                // label around it, each of which sets a colour explicitly,
                // looked fine.
                androidx.compose.material3.Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                WidgetConfigScreen(
                    model = model,
                    onCancel = { finish() },
                    onDone = {
                        model.commit {
                            setResult(Activity.RESULT_OK, resultIntent(widgetId))
                            finish()
                        }
                    }
                )
                }
            }
        }
    }

    private fun resultIntent(widgetId: Int) =
        Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
}

@Composable
private fun WidgetConfigScreen(
    model: WidgetConfigViewModel,
    onCancel: () -> Unit,
    onDone: () -> Unit
) {
    val state by model.state.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // The activity draws edge to edge, so the status bar is this
            // screen's to avoid. A fixed top spacer looked right on one device
            // and put the title under the clock on the next.
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = Space.page)
    ) {
        Spacer(Modifier.height(Space.section))
        Text(
            text = if (state.kind == WidgetKind.PLAYLIST) "Playlist widget" else "Now playing widget",
            style = MaterialTheme.typography.headlineSmall
        )
        Spacer(Modifier.height(Space.tight))
        Text(
            text = "Only this widget changes. Add another for a different look.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        LazyColumn(modifier = Modifier.weight(1f)) {
            item {
                Spacer(Modifier.height(Space.section))
                WidgetPreview(state)
                Spacer(Modifier.height(Space.section))
            }

            item {
                Label("Appearance")
                Row(horizontalArrangement = Arrangement.spacedBy(Space.tight)) {
                    WidgetAppearance.entries.forEach { appearance ->
                        AppearanceChip(
                            appearance = appearance,
                            selected = state.config.appearance == appearance,
                            onClick = { model.setAppearance(appearance) }
                        )
                    }
                }
                Spacer(Modifier.height(Space.section))
            }

            item {
                Label("Background ${(state.config.opacity * 100).toInt()}%")
                Slider(
                    value = state.config.opacity,
                    onValueChange = model::setOpacity,
                    // Never reaches zero: see WidgetConfig.MIN_OPACITY.
                    valueRange = WidgetConfig.MIN_OPACITY..1f
                )
                Text(
                    text = "Lower lets your wallpaper through. Text stays readable.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(Space.section))
            }

            if (state.kind == WidgetKind.PLAYLIST) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Play on tap", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = if (state.config.playOnTap) {
                                    "Starts the playlist"
                                } else {
                                    "Opens the playlist"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = state.config.playOnTap,
                            onCheckedChange = model::setPlayOnTap
                        )
                    }
                    Spacer(Modifier.height(Space.section))
                    Label("Playlist")
                }

                if (state.playlists.isEmpty()) {
                    item {
                        Text(
                            text = "No playlists yet. Make one in Library first.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                items(state.playlists, key = { it.id }) { playlist ->
                    PlaylistChoice(
                        title = playlist.title,
                        count = playlist.count,
                        selected = state.config.playlistId == playlist.id,
                        onClick = { model.choosePlaylist(playlist.id, playlist.title, playlist.count) }
                    )
                }
            }

            item { Spacer(Modifier.height(Space.section)) }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = Space.row),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = onCancel) { Text("Cancel") }
            Spacer(Modifier.width(Space.tight))
            TextButton(onClick = onDone, enabled = state.canCommit) {
                Text(if (state.kind == WidgetKind.PLAYLIST) "Add widget" else "Done")
            }
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = Space.tight)
    )
}

@Composable
private fun AppearanceChip(
    appearance: WidgetAppearance,
    selected: Boolean,
    onClick: () -> Unit
) {
    val border = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(border.copy(alpha = if (selected) 1f else 0.4f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(
            text = appearance.label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurface
            }
        )
    }
}

@Composable
private fun PlaylistChoice(
    title: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                } else {
                    Color.Transparent
                }
            )
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Text(
                text = if (count == 1) "1 track" else "$count tracks",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (selected) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = "Selected",
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/**
 * A faithful-enough rendering of what the launcher will show.
 *
 * Compose rather than Glance, because Glance can only draw into a real
 * AppWidgetHost — there is no way to inflate one inside an ordinary screen. The
 * layout is duplicated here on purpose and kept deliberately close to the real
 * one; the colours are not duplicated at all, they come from the same
 * [resolveSkin] the widget itself uses, so the preview cannot disagree with the
 * result about the thing users actually change.
 */
@Composable
private fun WidgetPreview(state: WidgetConfigState) {
    val skin = resolveSkin(state.config.appearance, state.config.opacity, state.snapshot)
    val art = state.previewBitmap

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(2.2f)
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        if (art != null) {
            androidx.compose.foundation.Image(
                bitmap = art.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(skin.background.copy(alpha = skin.scrimOverArt))
            )
        } else {
            Box(Modifier.fillMaxSize().background(skin.background))
        }

        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.Bottom
        ) {
            Text(
                text = state.previewTitle,
                color = skin.ink,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
            Text(
                text = state.previewSubtitle,
                color = skin.muted,
                fontSize = 12.sp,
                maxLines = 1
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                PreviewGlyph(skin.ink, 0.35f)
                Spacer(Modifier.width(8.dp))
                PreviewGlyph(skin.accent, 1f)
                Spacer(Modifier.width(8.dp))
                PreviewGlyph(skin.ink, 0.35f)
            }
        }
    }
}

/** A stand-in for a transport button, at the size the real one occupies. */
@Composable
private fun PreviewGlyph(color: Color, alpha: Float) {
    Box(
        Modifier
            .size(24.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = alpha))
    )
}
