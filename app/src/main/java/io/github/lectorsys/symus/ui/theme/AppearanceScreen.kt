package io.github.lectorsys.symus.ui.theme

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import io.github.lectorsys.symus.data.ThemeEntity
import java.io.File

/**
 * Themes made from the user's own photographs.
 *
 * Everything on this screen happens on the device: the picker hands over one
 * image, the palette is computed from its pixels locally, and the copy that a
 * saved theme keeps lives in the app's own storage. Nothing is uploaded and no
 * gallery permission is asked for — the system photo picker grants exactly one
 * image and nothing else.
 */
@Composable
fun AppearanceScreen(model: AppearanceViewModel, onBack: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val presets by model.presets.collectAsStateWithLifecycle()
    val active by model.active.collectAsStateWithLifecycle()

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let(model::pick) }

    // Leaving with an unsaved preview still applied would leave the app wearing
    // a theme that does not exist in the list.
    DisposableEffect(Unit) { onDispose { model.cancelPreview() } }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = Space.section)
    ) {
        item {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(start = 4.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Rounded.ArrowBack, contentDescription = "Back")
                }
                Text("Appearance", style = MaterialTheme.typography.headlineSmall)
            }
        }

        item {
            Text(
                text = "Build a theme from one of your photos. The picture never " +
                    "leaves your phone.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Space.page)
            )
            Spacer(Modifier.height(Space.row))
        }

        item {
            if (state.source == null) {
                OutlinedButton(
                    onClick = {
                        picker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    modifier = Modifier.padding(horizontal = Space.page)
                ) { Text("Choose a photo") }
            } else {
                Editor(state = state, model = model, onReplace = {
                    picker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                })
            }
            Spacer(Modifier.height(Space.section))
        }

        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Space.page),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Saved themes", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                if (active.isCustom) {
                    TextButton(onClick = model::clear) { Text("Use default") }
                }
            }
        }

        if (presets.isEmpty()) {
            item {
                Text(
                    text = "None yet. A theme you save shows up here and can be " +
                        "switched back on at any time.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Space.page, vertical = Space.tight)
                )
            }
        }

        items(presets, key = { it.id }) { preset ->
            PresetRow(
                preset = preset,
                applied = active.id == preset.id,
                onApply = { model.apply(preset.id) },
                onDelete = { model.delete(preset.id) }
            )
        }
    }
}

@Composable
private fun Editor(
    state: AppearanceState,
    model: AppearanceViewModel,
    onReplace: () -> Unit
) {
    Column(Modifier.padding(horizontal = Space.page)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 10f)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            AsyncImage(
                model = state.source,
                contentDescription = "The photo this theme is built from",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            if (state.analysing) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f))) {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                }
            }
        }

        Spacer(Modifier.height(Space.row))

        if (state.unreadable) {
            Text(
                text = "No usable colour came out of that image. Try another one — " +
                    "a photo with something colourful in it works best.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.height(Space.tight))
        }

        if (state.candidates.isNotEmpty()) {
            Text("Colour", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(Space.tight))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(Space.tight)) {
                items(state.candidates.size) { index ->
                    Swatch(
                        candidate = state.candidates[index],
                        selected = index == state.chosen,
                        onClick = { model.choose(index) }
                    )
                }
            }
            Spacer(Modifier.height(Space.row))
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Use as wallpaper", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "Draws the photo behind the app, dimmed enough to read over.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = state.useWallpaper, onCheckedChange = model::setUseWallpaper)
        }

        if (state.useWallpaper) {
            Spacer(Modifier.height(Space.tight))
            Text(
                "Dim ${(state.dim * 100).toInt()}%",
                style = MaterialTheme.typography.labelLarge
            )
            // Floored well above zero: the app's own text has to stay readable
            // over a photograph it has never seen, and the app cannot check the
            // contrast of an image it does not get to inspect per pixel. 0.45
            // is where white text survived the worst of the test images.
            Slider(value = state.dim, onValueChange = model::setDim, valueRange = 0.45f..0.9f)
            Text("Blur ${(state.blur * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
            Slider(value = state.blur, onValueChange = model::setBlur, valueRange = 0f..1f)
        }

        Spacer(Modifier.height(Space.row))
        NameField(value = state.name, onChange = model::setName)

        Spacer(Modifier.height(Space.row))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.tight)) {
            Button(onClick = { model.save() }, enabled = state.canSave) { Text("Use this theme") }
            OutlinedButton(onClick = onReplace) { Text("Different photo") }
        }
    }
}

@Composable
private fun NameField(value: String, onChange: (String) -> Unit) {
    Column {
        Text("Name", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(Space.tight))
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
            decorationBox = { inner ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 14.dp, vertical = 14.dp)
                ) {
                    if (value.isEmpty()) {
                        Text(
                            "Named after its colour if you leave this blank",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    inner()
                }
            }
        )
    }
}

@Composable
private fun Swatch(candidate: PaletteCandidate, selected: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(candidate.accent)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
                Icon(
                    Icons.Rounded.Check,
                    contentDescription = "Selected",
                    tint = readableOn(candidate.accent)
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = candidate.label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(60.dp)
        )
    }
}

@Composable
private fun PresetRow(
    preset: ThemeEntity,
    applied: Boolean,
    onApply: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onApply)
            .padding(horizontal = Space.page, vertical = Space.tight),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val file = preset.imagePath?.let(::File)?.takeIf { it.exists() }
        Box(
            Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(preset.accent ?: preset.seed))
        ) {
            if (file != null) {
                AsyncImage(
                    model = file,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        Spacer(Modifier.width(Space.row))
        Column(Modifier.weight(1f)) {
            Text(preset.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Text(
                text = when {
                    // Says what it will actually do, because "wallpaper" and
                    // "colour only" look very different once applied.
                    preset.useWallpaper && file != null -> "Colour and wallpaper"
                    file == null -> "Colour only — its photo is gone"
                    else -> "Colour only"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (applied) {
            Icon(
                Icons.Rounded.Check,
                contentDescription = "In use",
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(Space.tight))
        }
        TextButton(onClick = onDelete) { Text("Delete") }
    }
}
