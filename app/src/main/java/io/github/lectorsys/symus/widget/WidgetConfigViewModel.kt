package io.github.lectorsys.symus.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.lectorsys.symus.data.PlaylistDao
import io.github.lectorsys.symus.data.PlaylistSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Which of the two widgets is being configured. */
enum class WidgetKind { NOW_PLAYING, PLAYLIST }

data class WidgetConfigState(
    val widgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID,
    val kind: WidgetKind = WidgetKind.NOW_PLAYING,
    val config: WidgetConfig = WidgetConfig(),
    /** What the widget currently knows about playback, so the preview is real. */
    val snapshot: WidgetSnapshot = WidgetSnapshot(),
    val playlists: List<PlaylistSummary> = emptyList(),
    val previewBitmap: Bitmap? = null
) {
    val previewTitle: String
        get() = when {
            kind == WidgetKind.PLAYLIST -> config.playlistTitle ?: "Choose a playlist"
            else -> snapshot.title ?: "Nothing playing"
        }

    val previewSubtitle: String
        get() = when {
            kind == WidgetKind.PLAYLIST && config.playlistId == null -> "Not configured"
            kind == WidgetKind.PLAYLIST -> {
                if (config.playlistCount == 1) "1 track" else "${config.playlistCount} tracks"
            }
            else -> snapshot.artist ?: "Tap to open SYmuS"
        }

    /** A playlist widget with no playlist is not a widget, so Done stays off. */
    val canCommit: Boolean
        get() = kind != WidgetKind.PLAYLIST || config.playlistId != null
}

/**
 * State for one widget instance's configuration screen.
 *
 * Reads the instance's existing state on open, so editing a placed widget shows
 * what it is currently set to rather than defaults — the difference between
 * "configure" and "reconfigure" being that the second must not silently reset
 * the first.
 */
@HiltViewModel
class WidgetConfigViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playlists: PlaylistDao,
    private val updater: WidgetUpdater
) : ViewModel() {

    private val _state = MutableStateFlow(WidgetConfigState())
    val state: StateFlow<WidgetConfigState> = _state.asStateFlow()

    private var started = false

    fun start(widgetId: Int) {
        if (started) return
        started = true

        viewModelScope.launch {
            val kind = kindOf(widgetId)
            val summaries = runCatching { playlists.summaries().first() }.getOrDefault(emptyList())
            val existing = readState(widgetId)

            _state.value = WidgetConfigState(
                widgetId = widgetId,
                kind = kind,
                config = existing?.second ?: WidgetConfig(),
                snapshot = existing?.first ?: WidgetSnapshot(),
                playlists = summaries,
                previewBitmap = WidgetArt.decode(existing?.first?.artworkFile)
            )
        }
    }

    fun setAppearance(appearance: WidgetAppearance) {
        _state.value = _state.value.let { it.copy(config = it.config.copy(appearance = appearance)) }
    }

    fun setOpacity(opacity: Float) {
        _state.value = _state.value.let { it.copy(config = it.config.copy(opacity = opacity)) }
    }

    fun setPlayOnTap(play: Boolean) {
        _state.value = _state.value.let { it.copy(config = it.config.copy(playOnTap = play)) }
    }

    fun choosePlaylist(id: Long, title: String, count: Int) {
        _state.value = _state.value.let {
            it.copy(
                config = it.config.copy(
                    playlistId = id,
                    playlistTitle = title,
                    playlistCount = count,
                    playlistMissing = false
                )
            )
        }
    }

    /**
     * Writes the configuration to this widget instance and redraws it.
     *
     * [onWritten] runs only after the state is on disk. Finishing the activity
     * before that lets the launcher draw the widget from state that has not
     * been written yet, which is a widget that appears unconfigured for a beat
     * and then corrects itself — reported, every time, as a bug.
     */
    fun commit(onWritten: () -> Unit) {
        val snapshot = _state.value
        viewModelScope.launch {
            runCatching {
                val glanceId = GlanceAppWidgetManager(context).getGlanceIdBy(snapshot.widgetId)
                updateAppWidgetState(context, glanceId) { prefs ->
                    snapshot.config.copy(configured = true).writeTo(prefs)
                    // Cover art for the chosen playlist, fetched now so the
                    // widget's first frame has it rather than waiting for the
                    // next library change to push one.
                    val cover = snapshot.playlists
                        .firstOrNull { it.id == snapshot.config.playlistId }
                        ?.coverUrl
                    if (cover != null && snapshot.config.playlistId != null) {
                        WidgetArt.cover(context, snapshot.config.playlistId, cover)
                            ?.absolutePath
                            ?.let { prefs[WidgetKeys.playlistCoverFile] = it }
                    }
                }
                when (snapshot.kind) {
                    WidgetKind.PLAYLIST -> PlaylistWidget().updateAll(context)
                    // Hands the freshly configured widget the current track, so
                    // it does not read "Nothing playing" over audible music.
                    WidgetKind.NOW_PLAYING -> updater.seed(glanceId)
                }
            }.onFailure { failure ->
                // Never silent. A configuration that fails to save leaves a
                // widget reading "Not configured" for ever, and with the cause
                // swallowed there is nothing to go on but the symptom.
                Log.w("SYMUS_WIDGET", "widget ${snapshot.widgetId} config not saved", failure)
            }
            onWritten()
        }
    }

    /** Which provider this id belongs to, defaulting to the now-playing widget. */
    private fun kindOf(widgetId: Int): WidgetKind {
        val provider = runCatching {
            AppWidgetManager.getInstance(context).getAppWidgetInfo(widgetId)?.provider
        }.getOrNull()
        val playlistProvider = ComponentName(context, PlaylistWidgetReceiver::class.java)
        return if (provider == playlistProvider) WidgetKind.PLAYLIST else WidgetKind.NOW_PLAYING
    }

    /** This instance's current state, or null if it has none yet. */
    private suspend fun readState(widgetId: Int): Pair<WidgetSnapshot, WidgetConfig>? =
        runCatching {
            val glanceId = GlanceAppWidgetManager(context).getGlanceIdBy(widgetId)
            var result: Pair<WidgetSnapshot, WidgetConfig>? = null
            updateAppWidgetState(context, glanceId) { prefs ->
                result = WidgetSnapshot.read(prefs) to WidgetConfig.read(prefs)
            }
            result
        }.getOrNull()
}
