package io.github.lectorsys.symus.widget

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.datastore.preferences.core.MutablePreferences
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.lectorsys.symus.data.PlaylistSummary
import io.github.lectorsys.symus.ui.player.accentOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pushes what is playing, and what the app looks like, out to whatever widgets
 * exist.
 *
 * Every call here is wrapped: a widget that cannot be updated is a widget
 * showing a slightly old track, and that must never be allowed to take down
 * playback. The service calls this from its event stream and never waits on it.
 *
 * Everything is serialised through one mutex. Two pushes a quarter-second apart
 * — which a skip produces — both suspend on a bitmap decode, and without this
 * the slower one can finish last and leave the widget describing the track
 * before the one that is actually playing. The lock is cheap; the symptom is a
 * home screen that is confidently wrong and stays that way until the next
 * event.
 */
@Singleton
class WidgetUpdater @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val mutex = Mutex()

    /**
     * The last complete picture of playback, held so that a theme change can
     * rewrite the widgets without waiting for the next track.
     *
     * Process-lifetime only, and that is correct: the durable copy is the Glance
     * state itself, which is what a widget renders from after a reboot.
     */
    private var current = WidgetSnapshot()

    /** Written to every widget, playback or playlist, so APP appearance follows. */
    private var appAccent: Int? = null
    private var appSurface: Int? = null

    /** Suppresses identical repeat writes; see [pushIfChanged]. */
    private var lastKey: Pair<Set<GlanceId>, WidgetSnapshot>? = null

    /** When the last write actually happened, for the heartbeat below. */
    private var lastWriteAt = 0L

    /**
     * Writes [snapshot] to every placed widget, caching [artworkUrl] first.
     *
     * Returns early when no widget has been placed — which is the usual case,
     * and the reason the artwork download sits behind that check rather than in
     * front of it. There is no point spending a request per track on a picture
     * nothing is going to draw.
     */
    suspend fun push(snapshot: WidgetSnapshot, artworkUrl: String?) = mutex.withLock {
        val ids = idsOf(SymusWidget::class.java) + idsOf(PlaylistWidget::class.java)
        if (ids.isEmpty()) {
            // Still remembered, so placing a widget mid-song can be answered
            // from memory rather than waiting for the next track change.
            if (!snapshot.isEmpty) current = snapshot.withTheme()
            return@withLock
        }

        // An empty snapshot never blanks a widget that has something to show.
        //
        // Empty almost never means "the user cleared the queue". It means a
        // service that has started and not yet finished restoring, or a
        // controller that has connected a few milliseconds before the timeline
        // arrives — both of which happen on every cold start, and both of
        // which used to wipe a perfectly good widget to "Nothing playing" and
        // leave it there. The last thing the user played is the better answer
        // and the whole reason the snapshot is persisted.
        if (!shouldOverwrite(snapshot, storedSnapshot(ids))) return@withLock

        val art = snapshot.trackId?.let { id ->
            artworkUrl?.let { WidgetArt.track(context, id, it) }
        }
        val bitmap = WidgetArt.decode(art?.absolutePath)
        val accent = bitmap?.let {
            runCatching { accentOf(it) }.getOrNull()?.takeIf { c -> c != Color.Unspecified }
        }
        current = snapshot.copy(
            artworkFile = art?.absolutePath,
            artworkAccent = accent?.toArgb()
        ).withTheme()

        pushIfChanged(ids)
    }

    /**
     * Marks playback stopped without touching the rest of the snapshot.
     *
     * Called as the service goes away. The track stays — it is genuinely the
     * last thing the user played, and the widget is the way back to it — but a
     * pause button on a dead player is a button that does nothing.
     */
    suspend fun markStopped() = mutex.withLock {
        current = current.copy(playing = false)
        val ids = idsOf(SymusWidget::class.java)
        if (ids.isEmpty()) return@withLock
        writeAll(ids) { prefs -> prefs[WidgetKeys.playing] = false }
        lastKey = null
        refresh(SymusWidget())
    }

    /**
     * Mirrors the app's live theme into every widget.
     *
     * Called whenever the accent or surface changes — which includes a photo
     * theme being applied, edited, or cleared. Without this, "Match app" would
     * mean "match whatever the app looked like when this widget was last
     * pushed a track", which is a different and much worse promise.
     */
    suspend fun setAppTheme(accent: Color, surface: Color) = mutex.withLock {
        val a = accent.toArgb()
        val s = surface.toArgb()
        if (a == appAccent && s == appSurface) return@withLock
        appAccent = a
        appSurface = s
        current = current.withTheme()

        val playbackIds = idsOf(SymusWidget::class.java)
        val playlistIds = idsOf(PlaylistWidget::class.java)
        if (playbackIds.isEmpty() && playlistIds.isEmpty()) return@withLock
        // Both kinds: a playlist shortcut set to "Match app" has to move too.
        writeAll(playbackIds + playlistIds) { prefs ->
            prefs[WidgetKeys.appAccent] = a
            prefs[WidgetKeys.appSurface] = s
        }
        lastKey = null
        refresh(SymusWidget())
        refresh(PlaylistWidget())
    }

    /**
     * Brings every playlist shortcut back in line with the library.
     *
     * Reads each widget's own playlistId out of its state and looks it up,
     * rather than being told which widget to update: the widget is the only
     * thing that knows which playlist it points at, and a rename, a track
     * added, or a deletion all arrive as "something about playlists changed".
     *
     * A playlist that is gone is marked missing rather than cleared. The widget
     * stays on the home screen until the user removes it, so it needs something
     * true to say.
     */
    suspend fun syncPlaylists(summaries: List<PlaylistSummary>) = mutex.withLock {
        val ids = idsOf(PlaylistWidget::class.java)
        if (ids.isEmpty()) return@withLock
        val byId = summaries.associateBy { it.id }
        var changed = false

        ids.forEach { glanceId ->
            runCatching {
                updateAppWidgetState(context, glanceId) { prefs ->
                    val playlistId = prefs[WidgetKeys.playlistId] ?: return@updateAppWidgetState
                    val summary = byId[playlistId]
                    if (summary == null) {
                        if (prefs[WidgetKeys.playlistMissing] != true) changed = true
                        prefs[WidgetKeys.playlistMissing] = true
                        return@updateAppWidgetState
                    }
                    val cover = summary.coverUrl?.let {
                        WidgetArt.cover(context, summary.id, it)?.absolutePath
                    }
                    if (prefs[WidgetKeys.playlistTitle] != summary.title ||
                        prefs[WidgetKeys.playlistCount] != summary.count ||
                        prefs[WidgetKeys.playlistCoverFile] != cover ||
                        prefs[WidgetKeys.playlistMissing] == true
                    ) {
                        changed = true
                    }
                    prefs[WidgetKeys.playlistTitle] = summary.title
                    prefs[WidgetKeys.playlistCount] = summary.count
                    prefs[WidgetKeys.playlistMissing] = false
                    if (cover != null) prefs[WidgetKeys.playlistCoverFile] = cover
                    appAccent?.let { prefs[WidgetKeys.appAccent] = it }
                    appSurface?.let { prefs[WidgetKeys.appSurface] = it }
                }
            }
        }
        if (changed) refresh(PlaylistWidget())
    }

    /**
     * Writes the current snapshot into a widget that has just been configured.
     *
     * Without this a freshly placed now-playing widget shows "Nothing playing"
     * over music that is audibly coming out of the phone, until the next track
     * change happens to push to it.
     */
    suspend fun seed(glanceId: GlanceId) = mutex.withLock {
        runCatching {
            updateAppWidgetState(context, glanceId) { prefs -> current.writeTo(prefs) }
        }
        lastKey = null
        refresh(SymusWidget())
    }

    /**
     * The snapshot plus whatever the app theme currently is.
     *
     * Qualified receivers are not decoration here: unqualified, both names
     * would resolve to the snapshot's own fields — the extension receiver wins
     * over the enclosing class — and this would quietly copy each value onto
     * itself.
     */
    private fun WidgetSnapshot.withTheme() = copy(
        appAccent = this@WidgetUpdater.appAccent,
        appSurface = this@WidgetUpdater.appSurface
    )

    private suspend fun pushIfChanged(ids: List<GlanceId>) {
        // The timestamp is excluded from the comparison or nothing would ever
        // match. The id set is part of it because a widget placed mid-song has
        // empty state and identical playback — skipping that one leaves it
        // blank until the next track.
        val key = ids.toSet() to current.copy(updatedAt = 0)
        // While something is playing the timestamp is refreshed even when
        // nothing else changed. That heartbeat is what
        // WidgetSnapshot.PLAYING_TRUSTED_MS measures against: without it the
        // de-duplication above would let `updatedAt` go stale mid-song and the
        // widget would decide its own pause button was a lie. Nothing is
        // written when no widget exists, and nothing while paused.
        val now = System.currentTimeMillis()
        val heartbeatDue = current.playing && now - lastWriteAt >= HEARTBEAT_MS
        if (key == lastKey && !heartbeatDue) return
        writeAll(ids) { prefs -> current.writeTo(prefs) }
        lastKey = key
        lastWriteAt = now
        refresh(SymusWidget())
        refresh(PlaylistWidget())
    }

    /**
     * What the widgets currently hold, preferring this process's own memory.
     *
     * After a cold start [current] is empty while the widgets on the home
     * screen are not, so the durable copy has to be consulted before anything
     * is allowed to overwrite it.
     */
    private suspend fun storedSnapshot(ids: List<GlanceId>): WidgetSnapshot {
        if (!current.isEmpty) return current
        val id = ids.firstOrNull() ?: return WidgetSnapshot()
        var stored = WidgetSnapshot()
        runCatching {
            updateAppWidgetState(context, id) { prefs -> stored = WidgetSnapshot.read(prefs) }
        }
        if (!stored.isEmpty) current = stored.withTheme()
        return stored
    }

    private suspend fun idsOf(clazz: Class<out GlanceAppWidget>): List<GlanceId> = runCatching {
        GlanceAppWidgetManager(context).getGlanceIds(clazz)
    }.getOrDefault(emptyList())

    private suspend fun writeAll(
        ids: List<GlanceId>,
        block: suspend (MutablePreferences) -> Unit
    ) {
        ids.forEach { id -> runCatching { updateAppWidgetState(context, id, block) } }
    }

    private suspend fun refresh(widget: GlanceAppWidget) {
        runCatching { widget.updateAll(context) }
    }

    private companion object {
        /**
         * How often an otherwise unchanged snapshot is rewritten while playing.
         *
         * Comfortably inside [WidgetSnapshot.PLAYING_TRUSTED_MS] and well
         * outside the service's eight-second tick, so this is two writes a
         * minute during active playback and none at all otherwise.
         */
        const val HEARTBEAT_MS = 20_000L
    }
}

/**
 * Whether [incoming] is allowed to replace [stored].
 *
 * One rule, stated once: nothing empty replaces something. Extracted from
 * [WidgetUpdater] so it can be tested without a Context, an AppWidgetManager
 * and a launcher.
 */
internal fun shouldOverwrite(incoming: WidgetSnapshot, stored: WidgetSnapshot): Boolean =
    !incoming.isEmpty || stored.isEmpty
