package io.github.lectorsys.symus.ui.theme

import android.content.Context
import android.net.Uri
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.lectorsys.symus.data.ActiveTheme
import io.github.lectorsys.symus.data.ThemeEntity
import io.github.lectorsys.symus.data.ThemeRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * The state of the theme editor.
 *
 * [source] is the picked image, held only until the theme is saved — the saved
 * theme references a copy inside app storage instead, because a photo picker
 * URI is a short-lived grant that stops resolving the next time the process
 * starts.
 */
data class AppearanceState(
    val source: Uri? = null,
    val candidates: List<PaletteCandidate> = emptyList(),
    val chosen: Int = 0,
    val useWallpaper: Boolean = false,
    val dim: Float = 0.55f,
    val blur: Float = 0.35f,
    val focusX: Float = 0.5f,
    val focusY: Float = 0.5f,
    val name: String = "",
    val analysing: Boolean = false,
    /** Set when an image was picked but nothing usable could be read from it. */
    val unreadable: Boolean = false,
    /** The theme being edited, or null when building a new one. */
    val editingId: Long? = null
) {
    val accent: Color? get() = candidates.getOrNull(chosen)?.accent
    val canSave: Boolean get() = accent != null
}

@HiltViewModel
class AppearanceViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val themes: ThemeRepository
) : ViewModel() {

    private val _state = MutableStateFlow(AppearanceState())
    val state: StateFlow<AppearanceState> = _state.asStateFlow()

    /** Saved presets, newest first. */
    val presets: StateFlow<List<ThemeEntity>> = themes.all
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val active: StateFlow<ActiveTheme> = themes.active

    /**
     * Reads a freshly picked image and offers the colours in it.
     *
     * The preview is pushed live as soon as there is an accent, so the whole
     * app is already wearing the theme while the user is still deciding whether
     * to keep it — which is the only honest way to answer "what will this look
     * like", given the theme reaches every screen.
     */
    fun pick(uri: Uri) {
        _state.value = _state.value.copy(
            source = uri,
            analysing = true,
            unreadable = false,
            editingId = null
        )
        analyse(uri)
    }

    fun choose(index: Int) {
        _state.value = _state.value.copy(chosen = index)
        pushPreview()
    }

    /**
     * Turning the wallpaper on writes the preview copy immediately.
     *
     * The live preview draws from a file, and the picked image is a
     * content:// grant rather than one. Copying it now — downsampled, into the
     * app's own storage, the same way saving would — is what makes the toggle
     * show the actual result instead of describing it.
     */
    fun setUseWallpaper(on: Boolean) {
        _state.value = _state.value.copy(useWallpaper = on)
        val source = _state.value.source
        if (on && source != null && previewFile == null) {
            viewModelScope.launch {
                previewFile = ImagePalette.store(
                    context,
                    source,
                    File(context.filesDir, "themes/preview.jpg")
                )
                pushPreview()
            }
        }
        pushPreview()
    }

    /** The downsampled copy backing the live wallpaper preview. */
    private var previewFile: File? = null

    fun setDim(value: Float) {
        _state.value = _state.value.copy(dim = value)
        pushPreview()
    }

    fun setBlur(value: Float) {
        _state.value = _state.value.copy(blur = value)
        pushPreview()
    }

    fun setName(name: String) {
        _state.value = _state.value.copy(name = name)
    }

    /**
     * Moves the sample region and re-reads the palette from it.
     *
     * The colours genuinely change: dragging onto the red car gives red. That
     * is the whole reason the focal point exists rather than always averaging
     * the middle of the frame.
     */
    fun setFocus(x: Float, y: Float) {
        val state = _state.value
        _state.value = state.copy(focusX = x, focusY = y)
        state.source?.let { analyse(it) }
    }

    private fun analyse(uri: Uri) {
        viewModelScope.launch {
            val state = _state.value
            val found = runCatching {
                ImagePalette.of(context, uri, state.focusX, state.focusY)
            }.getOrDefault(emptyList())

            _state.value = _state.value.copy(
                candidates = found,
                chosen = 0,
                analysing = false,
                unreadable = found.isEmpty()
            )
            pushPreview()
        }
    }

    /** Shows the theme without saving it, so the editor tells the truth. */
    private fun pushPreview() {
        val state = _state.value
        val accent = state.accent ?: return
        themes.preview(
            ActiveTheme(
                id = ActiveTheme.PREVIEW_ID,
                accent = accent,
                wallpaper = if (state.useWallpaper) previewFile else null,
                dim = state.dim,
                blur = state.blur,
                name = state.name.ifBlank { null }
            )
        )
    }

    /** Drops the live preview and puts back whatever was actually applied. */
    fun cancelPreview() {
        themes.preview(null)
    }

    /**
     * Saves the theme and makes it the active one.
     *
     * Saving without applying would leave the user looking at a preview of
     * something that is not on — the editor has been showing this theme the
     * whole time, and the button says "Use this".
     */
    fun save(onSaved: () -> Unit = {}) {
        val state = _state.value
        val accent = state.accent ?: return
        viewModelScope.launch {
            val id = themes.create(
                name = state.name.ifBlank { defaultName(state) },
                source = state.source,
                seed = accent,
                accent = accent,
                useWallpaper = state.useWallpaper,
                dim = state.dim,
                blur = state.blur,
                focusX = state.focusX,
                focusY = state.focusY
            )
            themes.apply(id)
            _state.value = AppearanceState()
            onSaved()
        }
    }

    fun apply(id: Long) = viewModelScope.launch { themes.apply(id) }

    fun clear() = viewModelScope.launch { themes.reset() }

    fun delete(id: Long) = viewModelScope.launch { themes.delete(id) }

    fun rename(id: Long, name: String) = viewModelScope.launch { themes.rename(id, name) }

    /** Names an unnamed theme after the colour it is built on. */
    private fun defaultName(state: AppearanceState): String =
        state.candidates.getOrNull(state.chosen)?.label?.replaceFirstChar { it.uppercase() }
            ?: "My theme"
}
