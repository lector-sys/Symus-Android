package io.github.lectorsys.symus.widget

import androidx.datastore.preferences.core.mutablePreferencesOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The widget renders from preferences and nothing else, so the round trip
 * through them is the whole contract. A key written but not read, or a null
 * that leaves the previous track's value in place, shows up as a home screen
 * confidently describing the wrong song — with no crash anywhere to find it by.
 */
class WidgetModelsTest {

    @Test
    fun `a snapshot survives the round trip through preferences`() {
        val snapshot = WidgetSnapshot(
            trackId = "abc123",
            title = "Title",
            artist = "Artist",
            artworkFile = "/data/art.jpg",
            playing = true,
            hasNext = true,
            hasPrevious = false,
            updatedAt = 1_000
        )
        val prefs = mutablePreferencesOf().apply { snapshot.writeTo(this) }
        assertEquals(snapshot, WidgetSnapshot.read(prefs))
    }

    /**
     * The case that motivates setOrRemove: a track with no artist must clear
     * the previous one's, not inherit it.
     */
    @Test
    fun `a null field clears what the last track left behind`() {
        val prefs = mutablePreferencesOf()
        WidgetSnapshot(trackId = "a", title = "One", artist = "Somebody").writeTo(prefs)
        WidgetSnapshot(trackId = "b", title = "Two", artist = null).writeTo(prefs)

        assertNull(WidgetSnapshot.read(prefs).artist)
    }

    @Test
    fun `playing is not trusted once the snapshot is old`() {
        val now = 10 * WidgetSnapshot.STALE_AFTER_MS
        val fresh = WidgetSnapshot(trackId = "a", playing = true, updatedAt = now)
        val old = WidgetSnapshot(
            trackId = "a",
            playing = true,
            updatedAt = now - WidgetSnapshot.STALE_AFTER_MS - 1
        )

        assertFalse(fresh.stale(now))
        assertTrue(old.stale(now))
    }

    /**
     * A snapshot written before there was an updatedAt — or by a build that
     * forgot to set one — must not read as twelve hours stale on sight.
     */
    @Test
    fun `a snapshot with no timestamp is not stale`() {
        assertFalse(WidgetSnapshot(trackId = "a", playing = true).stale(now = Long.MAX_VALUE))
    }

    @Test
    fun `opacity cannot be turned down to invisible`() {
        val prefs = mutablePreferencesOf(WidgetKeys.opacity to 0f)
        assertEquals(WidgetConfig.MIN_OPACITY, WidgetConfig.read(prefs).opacity, 0.001f)
    }

    @Test
    fun `an unknown appearance falls back to the app theme`() {
        assertEquals(WidgetAppearance.APP, WidgetAppearance.from("MAUVE"))
        assertEquals(WidgetAppearance.APP, WidgetAppearance.from(null))
        assertEquals(WidgetAppearance.DARK, WidgetAppearance.from("DARK"))
    }

    // -- configuration, which has a different writer and a different lifetime --

    @Test
    fun `a configuration survives the round trip`() {
        val config = WidgetConfig(
            appearance = WidgetAppearance.ARTWORK,
            opacity = 0.5f,
            playlistId = 7,
            playlistTitle = "Late Night",
            playlistCoverFile = "/data/cover.jpg",
            playlistCount = 12,
            playlistMissing = false,
            playOnTap = false,
            configured = true
        )
        val prefs = mutablePreferencesOf().apply { config.writeTo(this) }
        assertEquals(config, WidgetConfig.read(prefs))
    }

    /**
     * The two halves have different owners: playback rewrites its half many
     * times a session and must never touch what the user set by hand, and the
     * configuration screen must not wipe what is playing. Both directions are
     * checked because only testing one is how the other one breaks.
     */
    @Test
    fun `playback and configuration do not overwrite each other`() {
        val prefs = mutablePreferencesOf()
        val config = WidgetConfig(
            appearance = WidgetAppearance.LIGHT,
            opacity = 0.5f,
            playlistId = 3,
            playlistTitle = "Focus",
            playOnTap = false,
            configured = true
        )
        config.writeTo(prefs)

        WidgetSnapshot(trackId = "a", title = "Song", playing = true, updatedAt = 5).writeTo(prefs)
        assertEquals(config, WidgetConfig.read(prefs))

        WidgetConfig.read(prefs).copy(opacity = 0.8f).writeTo(prefs)
        assertEquals("Song", WidgetSnapshot.read(prefs).title)
        assertTrue(WidgetSnapshot.read(prefs).playing)
    }

    @Test
    fun `mirrored theme colours round trip with the snapshot`() {
        val snapshot = WidgetSnapshot(
            trackId = "a",
            appAccent = 0xFFF0B252.toInt(),
            appSurface = 0xFF0B0B0C.toInt(),
            artworkAccent = 0xFF335577.toInt()
        )
        val prefs = mutablePreferencesOf().apply { snapshot.writeTo(this) }
        val read = WidgetSnapshot.read(prefs)
        assertEquals(snapshot.appAccent, read.appAccent)
        assertEquals(snapshot.appSurface, read.appSurface)
        assertEquals(snapshot.artworkAccent, read.artworkAccent)
    }

    /**
     * A playlist widget defaults to playing on tap, because a shortcut that
     * only opens a list is a slower way to do what the app icon already does.
     * Regression: this default was false once and the widget did nothing
     * audible until the user found the switch.
     */
    @Test
    fun `an unconfigured playlist widget defaults to playing`() {
        assertTrue(WidgetConfig.read(mutablePreferencesOf()).playOnTap)
        assertFalse(WidgetConfig.read(mutablePreferencesOf()).configured)
    }

    /**
     * The abrupt-termination case, which onDestroy cannot cover because it
     * never runs: the process is killed mid-song, the snapshot still claims
     * playing, and the widget would otherwise show a pause button for a player
     * that no longer exists. After the heartbeat horizon the claim expires on
     * its own.
     */
    @Test
    fun `a playing claim expires without a heartbeat`() {
        val now = 10_000_000L
        val beating = WidgetSnapshot(trackId = "a", playing = true, updatedAt = now - 5_000)
        val abandoned = WidgetSnapshot(
            trackId = "a",
            playing = true,
            updatedAt = now - WidgetSnapshot.PLAYING_TRUSTED_MS - 1
        )

        assertTrue(beating.playingNow(now))
        assertFalse("a killed player still claimed to be playing", abandoned.playingNow(now))
        // The track itself is still worth showing; only the claim expired.
        assertFalse(abandoned.stale(now))
        assertEquals("a", abandoned.trackId)
    }

    /**
     * Distinct horizons, and the ordering between them is the whole point: the
     * track survives far longer than the claim that it is playing.
     */
    @Test
    fun `the playing horizon is much tighter than the staleness horizon`() {
        assertTrue(WidgetSnapshot.PLAYING_TRUSTED_MS < WidgetSnapshot.STALE_AFTER_MS / 100)
    }

    @Test
    fun `a snapshot that was never written is not playing`() {
        assertFalse(WidgetSnapshot(trackId = "a", playing = true, updatedAt = 0).playingNow(1))
    }

    /**
     * Regression, found on a real home screen. Force-stopping the app made the
     * widget refresh itself; the controller reported "connected" a beat before
     * the service finished restoring its queue, an empty snapshot went out, and
     * two widgets showing a track went blank and stayed blank.
     */
    @Test
    fun `an empty snapshot never replaces one with a track`() {
        val playing = WidgetSnapshot(trackId = "a", title = "Song")
        val nothing = WidgetSnapshot()

        assertFalse(shouldOverwrite(nothing, playing))
        assertTrue(shouldOverwrite(playing, nothing))
        assertTrue(shouldOverwrite(playing, playing.copy(title = "Other")))
        // With nothing stored there is nothing to protect.
        assertTrue(shouldOverwrite(nothing, nothing))
    }
}
