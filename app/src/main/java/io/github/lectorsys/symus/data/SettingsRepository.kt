package io.github.lectorsys.symus.data

import io.github.lectorsys.symus.media.AudioQuality
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

enum class ThemeChoice { SYSTEM, DARK, LIGHT }

/**
 * Everything the Settings screen can change, as one value.
 *
 * Kept as a single immutable snapshot rather than a field per preference: the
 * player's data source reads two of these from a loading thread, and one
 * volatile reference is a consistent read where four separate ones are not.
 */
data class Settings(
    val theme: ThemeChoice = ThemeChoice.DARK,
    /** Colour the UI from the current artwork. Off means SYmuS's own palette. */
    val artworkColors: Boolean = true,
    /** Keep playing something similar when the queue runs dry. */
    val autoplay: Boolean = true,
    val quality: AudioQuality = AudioQuality.HIGH,
    /** ExoPlayer's silence skipping — audible on podcasts, pointless on music. */
    val skipSilence: Boolean = false,
    /** Restore the queue and position from the last session on launch. */
    val restoreQueue: Boolean = true,
    /** Downloads wait for an unmetered connection. */
    val downloadOnWifiOnly: Boolean = false,
    /** Record what is played. Off stops writing history and stops stats growing. */
    val historyEnabled: Boolean = true,
    /**
     * Short confirming vibrations on the controls that benefit from one.
     *
     * Opt-out rather than opt-in, but genuinely honoured: nothing in the app
     * vibrates when this is off, and reduced-motion is respected separately
     * because the two are different accessibility needs.
     */
    val haptics: Boolean = true
)

/**
 * The settings table, typed.
 *
 * Reads are served from memory so that a preference can be consulted from a
 * non-suspending context (the data source, the notification builder); writes go
 * to the database first and are only reflected in memory once stored, so a
 * failed write cannot leave the UI claiming a setting it does not have.
 */
@Singleton
class SettingsRepository @Inject constructor(
    private val dao: SettingsDao,
    scope: CoroutineScope
) {
    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    /** The last loaded snapshot, for callers that cannot suspend. */
    val current: Settings get() = _settings.value

    init {
        scope.launch { _settings.value = load() }
    }

    private suspend fun load(): Settings = withContext(Dispatchers.IO) {
        Settings(
            theme = when (dao.get(THEME)) {
                "system" -> ThemeChoice.SYSTEM
                "light" -> ThemeChoice.LIGHT
                else -> ThemeChoice.DARK
            },
            artworkColors = dao.get(ARTWORK_COLORS) != "false",
            autoplay = dao.get(AUTOPLAY) != "false",
            quality = if (dao.get(QUALITY) == "saver") AudioQuality.DATA_SAVER else AudioQuality.HIGH,
            skipSilence = dao.get(SKIP_SILENCE) == "true",
            restoreQueue = dao.get(RESTORE_QUEUE) != "false",
            downloadOnWifiOnly = dao.get(WIFI_ONLY) == "true",
            historyEnabled = dao.get(HISTORY) != "false",
            haptics = dao.get(HAPTICS) != "false"
        )
    }

    private suspend fun write(key: String, value: String) = withContext(Dispatchers.IO) {
        dao.put(SettingEntity(key, value))
    }

    suspend fun setTheme(choice: ThemeChoice) {
        write(THEME, choice.name.lowercase())
        _settings.value = _settings.value.copy(theme = choice)
    }

    suspend fun setArtworkColors(on: Boolean) {
        write(ARTWORK_COLORS, on.toString())
        _settings.value = _settings.value.copy(artworkColors = on)
    }

    suspend fun setAutoplay(on: Boolean) {
        write(AUTOPLAY, on.toString())
        _settings.value = _settings.value.copy(autoplay = on)
    }

    suspend fun setQuality(quality: AudioQuality) {
        write(QUALITY, if (quality == AudioQuality.DATA_SAVER) "saver" else "high")
        _settings.value = _settings.value.copy(quality = quality)
    }

    suspend fun setSkipSilence(on: Boolean) {
        write(SKIP_SILENCE, on.toString())
        _settings.value = _settings.value.copy(skipSilence = on)
    }

    suspend fun setRestoreQueue(on: Boolean) {
        write(RESTORE_QUEUE, on.toString())
        _settings.value = _settings.value.copy(restoreQueue = on)
    }

    suspend fun setDownloadOnWifiOnly(on: Boolean) {
        write(WIFI_ONLY, on.toString())
        _settings.value = _settings.value.copy(downloadOnWifiOnly = on)
    }

    suspend fun setHistoryEnabled(on: Boolean) {
        write(HISTORY, on.toString())
        _settings.value = _settings.value.copy(historyEnabled = on)
    }

    suspend fun setHaptics(on: Boolean) {
        write(HAPTICS, on.toString())
        _settings.value = _settings.value.copy(haptics = on)
    }

    // -- the active generated theme ---------------------------------------
    //
    // Deliberately not part of [Settings]: the theme itself lives in its own
    // table with an image beside it, and duplicating its fields into the
    // settings snapshot would give two sources of truth for one appearance.
    // Only the pointer is stored here.

    suspend fun activeThemeId(): Long? = withContext(Dispatchers.IO) {
        dao.get(ACTIVE_THEME)?.toLongOrNull()
    }

    suspend fun setActiveThemeId(id: Long?) = withContext(Dispatchers.IO) {
        if (id == null) dao.remove(ACTIVE_THEME) else dao.put(SettingEntity(ACTIVE_THEME, id.toString()))
    }

    /** Free-form storage for the parts of the app that own one small string. */
    suspend fun get(key: String): String? = withContext(Dispatchers.IO) { dao.get(key) }

    suspend fun put(key: String, value: String) = write(key, value)

    suspend fun remove(key: String) = withContext(Dispatchers.IO) { dao.remove(key) }

    // -- session state, which is remembered but is not a preference --------

    suspend fun saveSession(session: String) = write(SESSION, session)

    suspend fun readSession(): String? = withContext(Dispatchers.IO) { dao.get(SESSION) }

    suspend fun clearSession() = withContext(Dispatchers.IO) { dao.remove(SESSION) }

    private companion object {
        const val THEME = "theme"
        const val ARTWORK_COLORS = "artworkColors"
        const val AUTOPLAY = "autoplay"
        const val QUALITY = "audioQuality"
        const val SKIP_SILENCE = "skipSilence"
        const val RESTORE_QUEUE = "restoreQueue"
        const val WIFI_ONLY = "downloadOnWifiOnly"
        const val HISTORY = "historyEnabled"
        const val SESSION = "session"
        const val HAPTICS = "haptics"
        const val ACTIVE_THEME = "activeThemeId"
    }
}
