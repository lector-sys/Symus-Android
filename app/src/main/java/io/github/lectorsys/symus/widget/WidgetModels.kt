package io.github.lectorsys.symus.widget

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

/**
 * What a widget knows, and how it is dressed.
 *
 * Both live in Glance's own per-widget preference state rather than in memory,
 * and that is the whole design. A launcher will ask a widget to redraw long
 * after the app process has been killed — on a reboot, after the system
 * reclaims memory overnight, when the user resizes it. Anything held in a
 * singleton is gone by then; anything in Glance state is still there. So the
 * widget renders from these keys and nothing else, and is correct on the first
 * frame after a cold start without waking the player to ask.
 */
object WidgetKeys {

    // -- the playback snapshot, written by the app, read by every widget ----

    val trackId = stringPreferencesKey("trackId")
    val title = stringPreferencesKey("title")
    val artist = stringPreferencesKey("artist")

    /** Path to the cached artwork bitmap. Not a URL: a widget cannot fetch. */
    val artworkFile = stringPreferencesKey("artworkFile")
    val playing = booleanPreferencesKey("playing")
    val hasNext = booleanPreferencesKey("hasNext")
    val hasPrevious = booleanPreferencesKey("hasPrevious")

    /** When the snapshot was written, so a stale one can be labelled as such. */
    val updatedAt = longPreferencesKey("updatedAt")

    /**
     * A colour pulled from the current artwork, already forced into a legible
     * band. Written with the snapshot so [WidgetAppearance.ARTWORK] does not
     * have to decode a bitmap during composition to find out what colour to be.
     */
    val artworkAccent = intPreferencesKey("artworkAccent")

    // -- the app's own theme, mirrored so APP appearance can follow it -------

    /**
     * The app's live accent and surface, ARGB.
     *
     * Mirrored rather than read: the widget has no access to the app's theme —
     * it renders in the launcher's process, from a snapshot, often with no app
     * process alive at all. Pushing these two in with the playback snapshot is
     * what makes "follow the app" mean something rather than a guess frozen at
     * configuration time. A generated photo theme changes them like any other.
     */
    val appAccent = intPreferencesKey("appAccent")
    val appSurface = intPreferencesKey("appSurface")

    // -- per-instance configuration, written by the configuration screen ----

    /** One of [WidgetAppearance]. */
    val appearance = stringPreferencesKey("appearance")

    /** Background opacity, 0..1. */
    val opacity = floatPreferencesKey("opacity")

    /** For the shortcut widget: which playlist, and what it was called. */
    val playlistId = longPreferencesKey("playlistId")
    val playlistTitle = stringPreferencesKey("playlistTitle")

    /** Cover art for the shortcut widget, cached the same way track art is. */
    val playlistCoverFile = stringPreferencesKey("playlistCoverFile")

    /** How many tracks the playlist held when it was last looked at. */
    val playlistCount = intPreferencesKey("playlistCount")

    /**
     * Set once the playlist a shortcut points at has been deleted.
     *
     * The widget stays on the home screen either way — only the user can remove
     * it — so it has to be able to say what happened instead of silently doing
     * nothing when tapped.
     */
    val playlistMissing = booleanPreferencesKey("playlistMissing")

    /** Shortcut behaviour: open the playlist, or start playing it. */
    val playOnTap = booleanPreferencesKey("playOnTap")

    /**
     * Set by the configuration screen when it commits.
     *
     * Distinguishes "the user has configured this widget" from "this widget has
     * empty state because nothing has happened yet", which otherwise look
     * identical and decide whether defaults may be overwritten.
     */
    val configured = booleanPreferencesKey("configured")
}

/**
 * How a widget colours itself.
 *
 * Four options rather than a colour picker. A widget sits on the user's own
 * wallpaper, which the app cannot see and must not assume anything about, so
 * the useful choice is "how should this relate to its surroundings" — and that
 * has a small number of sensible answers.
 */
enum class WidgetAppearance {
    /** Follow the app's current theme, including a generated one. */
    APP,

    /** Neutral dark, regardless of the app or the system. */
    DARK,

    /** Neutral light, regardless of the app or the system. */
    LIGHT,

    /** Tinted from the artwork of whatever is playing. */
    ARTWORK;

    /** What the configuration screen calls this. */
    val label: String
        get() = when (this) {
            APP -> "Match app"
            DARK -> "Dark"
            LIGHT -> "Light"
            ARTWORK -> "Artwork"
        }

    companion object {
        fun from(name: String?): WidgetAppearance =
            entries.firstOrNull { it.name == name } ?: APP
    }
}

/**
 * The snapshot in a form the composables can take apart.
 *
 * [stale] is not decoration. A widget can be showing a track from three days
 * ago after the process was killed mid-song; claiming it is "playing" then
 * would put a pause button on something that is not making any sound. Past the
 * staleness horizon the widget still shows the track — it is genuinely the last
 * thing the user played — but never as playing.
 */
data class WidgetSnapshot(
    val trackId: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val artworkFile: String? = null,
    val playing: Boolean = false,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
    val updatedAt: Long = 0,
    val artworkAccent: Int? = null,
    val appAccent: Int? = null,
    val appSurface: Int? = null
) {
    val isEmpty: Boolean get() = trackId == null

    /**
     * True once the snapshot is old enough that "playing" cannot be trusted.
     *
     * Twelve hours: long enough that a widget glanced at over a lunch break is
     * still live, short enough that yesterday's session does not show a pause
     * button.
     */
    fun stale(now: Long = System.currentTimeMillis()): Boolean =
        updatedAt > 0 && now - updatedAt > STALE_AFTER_MS

    /**
     * Whether "playing" can still be believed.
     *
     * A much tighter horizon than [stale], and for a different question. The
     * track is worth showing for hours; the claim that it is *playing right
     * now* is only worth anything for as long as the heartbeat that refreshes
     * it. A process killed abruptly — the system reclaiming memory, a crash —
     * never runs onDestroy and never gets to say it stopped, so the snapshot
     * would otherwise keep a pause button on a dead player until the twelve
     * hours ran out. Past [PLAYING_TRUSTED_MS] with no heartbeat, the honest
     * answer is "not playing".
     */
    fun playingNow(now: Long = System.currentTimeMillis()): Boolean =
        playing && updatedAt > 0 && now - updatedAt <= PLAYING_TRUSTED_MS

    val playingNow: Boolean get() = playingNow(System.currentTimeMillis())

    companion object {
        const val STALE_AFTER_MS = 12L * 60 * 60 * 1000

        /**
         * How long a "playing" claim survives without a heartbeat.
         *
         * Five times the service's eight-second state tick, so an ordinary
         * missed beat — a slow write, a GC pause — never flips the button,
         * while a process that has actually gone away is corrected inside a
         * minute rather than inside a day.
         */
        const val PLAYING_TRUSTED_MS = 45_000L

        fun read(prefs: Preferences) = WidgetSnapshot(
            trackId = prefs[WidgetKeys.trackId],
            title = prefs[WidgetKeys.title],
            artist = prefs[WidgetKeys.artist],
            artworkFile = prefs[WidgetKeys.artworkFile],
            playing = prefs[WidgetKeys.playing] ?: false,
            hasNext = prefs[WidgetKeys.hasNext] ?: false,
            hasPrevious = prefs[WidgetKeys.hasPrevious] ?: false,
            updatedAt = prefs[WidgetKeys.updatedAt] ?: 0,
            artworkAccent = prefs[WidgetKeys.artworkAccent],
            appAccent = prefs[WidgetKeys.appAccent],
            appSurface = prefs[WidgetKeys.appSurface]
        )
    }
}

/** Per-instance appearance, read from the same preference store. */
data class WidgetConfig(
    val appearance: WidgetAppearance = WidgetAppearance.APP,
    val opacity: Float = 1f,
    val playlistId: Long? = null,
    val playlistTitle: String? = null,
    val playlistCoverFile: String? = null,
    val playlistCount: Int = 0,
    val playlistMissing: Boolean = false,
    val playOnTap: Boolean = true,
    val configured: Boolean = false
) {
    companion object {
        fun read(prefs: Preferences) = WidgetConfig(
            appearance = WidgetAppearance.from(prefs[WidgetKeys.appearance]),
            // Clamped on read as well as on write: a value from an older build,
            // or a corrupted store, must not produce an invisible widget.
            opacity = (prefs[WidgetKeys.opacity] ?: 1f).coerceIn(MIN_OPACITY, 1f),
            playlistId = prefs[WidgetKeys.playlistId]?.takeIf { it > 0 },
            playlistTitle = prefs[WidgetKeys.playlistTitle],
            playlistCoverFile = prefs[WidgetKeys.playlistCoverFile],
            playlistCount = prefs[WidgetKeys.playlistCount] ?: 0,
            playlistMissing = prefs[WidgetKeys.playlistMissing] ?: false,
            playOnTap = prefs[WidgetKeys.playOnTap] ?: true,
            configured = prefs[WidgetKeys.configured] ?: false
        )

        /**
         * The floor on background opacity.
         *
         * Not zero. A fully transparent widget over an arbitrary wallpaper is
         * unreadable text on an unknown photograph, and the app has no way to
         * check the contrast of something it cannot see. 0.35 keeps enough
         * substrate under the text for it to survive any wallpaper.
         */
        const val MIN_OPACITY = 0.35f
    }
}

/**
 * The inverse of [WidgetSnapshot.read], and deliberately next to it.
 *
 * A key written here and not read there — or the other way round — is a field
 * that silently stops existing the moment the process dies, which is exactly
 * the failure this whole file is arranged to prevent. They stay adjacent so
 * that adding one without the other looks as wrong as it is.
 */
fun WidgetSnapshot.writeTo(prefs: MutablePreferences) {
    prefs.setOrRemove(WidgetKeys.trackId, trackId)
    prefs.setOrRemove(WidgetKeys.title, title)
    prefs.setOrRemove(WidgetKeys.artist, artist)
    prefs.setOrRemove(WidgetKeys.artworkFile, artworkFile)
    prefs[WidgetKeys.playing] = playing
    prefs[WidgetKeys.hasNext] = hasNext
    prefs[WidgetKeys.hasPrevious] = hasPrevious
    prefs[WidgetKeys.updatedAt] = updatedAt
    prefs.setOrRemove(WidgetKeys.artworkAccent, artworkAccent)
    prefs.setOrRemove(WidgetKeys.appAccent, appAccent)
    prefs.setOrRemove(WidgetKeys.appSurface, appSurface)
}

/**
 * Writes only the fields the configuration screen owns.
 *
 * Separate from [writeTo] because the two have different writers and different
 * lifetimes: playback overwrites its half many times a session, and must never
 * touch the half the user set by hand.
 */
fun WidgetConfig.writeTo(prefs: MutablePreferences) {
    prefs[WidgetKeys.appearance] = appearance.name
    prefs[WidgetKeys.opacity] = opacity.coerceIn(WidgetConfig.MIN_OPACITY, 1f)
    prefs.setOrRemove(WidgetKeys.playlistId, playlistId)
    prefs.setOrRemove(WidgetKeys.playlistTitle, playlistTitle)
    prefs.setOrRemove(WidgetKeys.playlistCoverFile, playlistCoverFile)
    prefs[WidgetKeys.playlistCount] = playlistCount
    prefs[WidgetKeys.playlistMissing] = playlistMissing
    prefs[WidgetKeys.playOnTap] = playOnTap
    prefs[WidgetKeys.configured] = configured
}

/**
 * Removes rather than writes a null.
 *
 * Preferences has no null: writing one throws. Leaving the old value in place
 * instead would be worse than throwing — a track with no artist would keep the
 * previous track's.
 */
private fun <T : Any> MutablePreferences.setOrRemove(key: Preferences.Key<T>, value: T?) {
    if (value == null) remove(key) else set(key, value)
}

/**
 * The snapshot for a controller-side view of playback.
 *
 * Used when something other than the service has to answer "what is playing" —
 * a widget being placed, for instance, which arrives as a broadcast with no
 * player in reach. [io.github.lectorsys.symus.media.PlaybackService] builds the
 * same thing from the ExoPlayer directly, because it has one.
 */
fun snapshotOf(
    state: io.github.lectorsys.symus.media.PlaybackUiState,
    now: Long = System.currentTimeMillis()
): WidgetSnapshot = WidgetSnapshot(
    trackId = state.current?.videoId,
    title = state.current?.title,
    artist = state.current?.artist,
    playing = state.playing,
    hasNext = state.hasNext,
    hasPrevious = state.hasPrevious,
    updatedAt = now
)
