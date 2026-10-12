package io.github.lectorsys.symus.data

import android.content.Context
import android.net.Uri
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.lectorsys.symus.ui.theme.Amber
import io.github.lectorsys.symus.ui.theme.ImagePalette
import io.github.lectorsys.symus.ui.theme.asAccent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The theme the app is currently wearing.
 *
 * Resolved once, here, rather than recomputed by every screen: the accent has to
 * be identical in the nav bar, the player, a dialog and a widget, and four
 * places deriving it independently is four places for it to be subtly different.
 */
data class ActiveTheme(
    val id: Long? = null,
    val accent: Color = Amber,
    /** The wallpaper file, or null for palette-only — which is the default. */
    val wallpaper: File? = null,
    val dim: Float = 0.55f,
    val blur: Float = 0.35f,
    val name: String? = null
) {
    val hasWallpaper: Boolean get() = wallpaper != null

    /** True for anything other than SYmuS's own palette, including a preview. */
    val isCustom: Boolean get() = id != null

    companion object {
        /**
         * The id a live preview wears.
         *
         * Negative so it can never collide with a row id, and present so that
         * everything downstream — the accent, the wallpaper, what gets mirrored
         * to the widgets — treats an unsaved preview exactly like a saved
         * theme. Without it the editor showed the old colours while claiming to
         * be a preview, which is the one thing a preview must not do.
         */
        const val PREVIEW_ID = -1L
    }
}

/**
 * Themes the user generated from their own photographs.
 *
 * Owns the image files as well as the rows: a theme's picture lives in the
 * app's own storage under themes/, is written there downsampled, and is deleted
 * when the theme is. Nothing here touches the gallery beyond the single URI the
 * system photo picker handed over, and nothing leaves the device.
 */
@Singleton
class ThemeRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val themes: ThemeDao,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope
) {
    private val directory: File get() = File(context.filesDir, "themes")

    private val _active = MutableStateFlow(ActiveTheme())

    /** What [io.github.lectorsys.symus.ui.theme.SymusTheme] is driven from. */
    val active: StateFlow<ActiveTheme> = _active.asStateFlow()

    val all: Flow<List<ThemeEntity>> = themes.allFlow()

    init {
        scope.launch { refresh() }
    }

    /** Re-reads the active theme from storage. */
    suspend fun refresh() {
        val id = settings.activeThemeId()
        _active.value = if (id == null) ActiveTheme() else resolve(themes.byId(id))
    }

    private fun resolve(entity: ThemeEntity?): ActiveTheme {
        if (entity == null) return ActiveTheme()
        val file = entity.imagePath?.let(::File)?.takeIf { it.exists() }
        return ActiveTheme(
            id = entity.id,
            accent = Color(entity.accent ?: entity.seed),
            // A theme whose image has been deleted out from under it degrades to
            // palette-only rather than rendering a blank rectangle.
            wallpaper = if (entity.useWallpaper) file else null,
            dim = entity.dim,
            blur = entity.blur,
            name = entity.name
        )
    }

    /**
     * Saves a new theme, copying its image into app storage.
     *
     * The copy happens before the row is written, so a row never points at a
     * file that does not exist.
     */
    suspend fun create(
        name: String,
        source: Uri?,
        seed: Color,
        accent: Color?,
        useWallpaper: Boolean,
        dim: Float,
        blur: Float,
        focusX: Float,
        focusY: Float
    ): Long = withContext(Dispatchers.IO) {
        val stamp = System.currentTimeMillis()
        val stored = if (source == null) null else {
            ImagePalette.store(context, source, File(directory, "theme-$stamp.jpg"))
        }
        themes.insert(
            ThemeEntity(
                name = name,
                imagePath = stored?.absolutePath,
                seed = seed.toArgb(),
                accent = accent?.toArgb(),
                useWallpaper = useWallpaper && stored != null,
                dim = dim,
                blur = blur,
                focusX = focusX,
                focusY = focusY,
                createdAt = stamp
            )
        )
    }

    /**
     * Updates a theme in place, optionally replacing its image.
     *
     * When the image changes the old file is deleted only after the new one has
     * been written and the row updated — losing the old picture and failing to
     * write the new one would leave the user with a theme and no image.
     */
    suspend fun update(
        id: Long,
        name: String? = null,
        newImage: Uri? = null,
        accent: Color? = null,
        useWallpaper: Boolean? = null,
        dim: Float? = null,
        blur: Float? = null,
        focusX: Float? = null,
        focusY: Float? = null
    ) = withContext(Dispatchers.IO) {
        val existing = themes.byId(id) ?: return@withContext
        val replacement = newImage?.let {
            ImagePalette.store(context, it, File(directory, "theme-$id-${System.currentTimeMillis()}.jpg"))
        }
        val updated = existing.copy(
            name = name ?: existing.name,
            imagePath = replacement?.absolutePath ?: existing.imagePath,
            accent = accent?.toArgb() ?: existing.accent,
            useWallpaper = useWallpaper ?: existing.useWallpaper,
            dim = dim ?: existing.dim,
            blur = blur ?: existing.blur,
            focusX = focusX ?: existing.focusX,
            focusY = focusY ?: existing.focusY
        )
        themes.update(updated)
        if (replacement != null && existing.imagePath != null) {
            runCatching { File(existing.imagePath).delete() }
            ImagePalette.forget()
        }
        if (_active.value.id == id) _active.value = resolve(updated)
    }

    suspend fun rename(id: Long, name: String) = withContext(Dispatchers.IO) {
        themes.rename(id, name)
        if (_active.value.id == id) _active.value = _active.value.copy(name = name)
    }

    /** Deletes the theme, its image, and any reference to it as the active one. */
    suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        val existing = themes.byId(id)
        themes.delete(id)
        existing?.imagePath?.let { runCatching { File(it).delete() } }
        ImagePalette.forget()
        if (settings.activeThemeId() == id) reset()
    }

    /** Makes [id] the active theme, or clears it when null. */
    suspend fun apply(id: Long?) {
        settings.setActiveThemeId(id)
        refresh()
    }

    /** Back to SYmuS's own palette. */
    suspend fun reset() = apply(null)

    /**
     * Deletes image files no theme refers to any more.
     *
     * Cheap insurance against a crash between writing a file and writing its
     * row. Runs on startup rather than on a schedule; there are never more than
     * a handful of these.
     */
    suspend fun sweepOrphans() = withContext(Dispatchers.IO) {
        val referenced = themes.all().mapNotNull { it.imagePath }.toSet()
        directory.listFiles()?.forEach { file ->
            if (file.absolutePath !in referenced) runCatching { file.delete() }
        }
    }

    /** Pushes a live preview without persisting it, so the editor shows the truth. */
    fun preview(theme: ActiveTheme?) {
        if (theme == null) {
            scope.launch { refresh() }
        } else {
            _active.value = theme
        }
    }

    /** The accent a colour becomes once forced into a legible band. */
    fun accentFor(seed: Color, dark: Boolean): Color = seed.asAccent(dark)
}
