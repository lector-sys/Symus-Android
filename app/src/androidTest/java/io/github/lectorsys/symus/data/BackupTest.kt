package io.github.lectorsys.symus.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * A backup is only worth having if it restores, and only safe if restoring it
 * twice changes nothing the second time.
 *
 * Both halves are pinned here. The export must carry the whole library —
 * including the parts the recommender hides and the provenance an imported
 * playlist needs — and the restore must neither destroy what is already on the
 * phone nor inflate anything by replaying it.
 */
@RunWith(AndroidJUnit4::class)
class BackupTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private lateinit var source: SymusDatabase
    private lateinit var target: SymusDatabase

    private fun open() = Room.inMemoryDatabaseBuilder(
        context,
        SymusDatabase::class.java
    ).build()

    private fun SymusDatabase.backup() = Backup(
        context, tracks(), playlists(), feedback(), history(),
        settings(), searchHistory(), channels(), themes()
    )

    private fun track(id: String, title: String, contentType: String? = null) =
        TrackEntity(videoId = id, title = title, artist = "Someone", contentType = contentType)

    @Before
    fun setUp() {
        source = open()
        target = open()
    }

    @After
    fun tearDown() {
        source.close()
        target.close()
    }

    /**
     * A podcast and a disliked track are still part of the library. Exporting
     * only the recommender's candidates drops them, and then the playlist that
     * references them cannot be restored at all — the foreign key rejects it and
     * the whole restore fails.
     */
    @Test
    fun everyTrackSurvivesTheRoundTripEvenWhenTheRecommenderWouldHideIt() = runTest {
        source.tracks().insertIfAbsent(
            listOf(
                track("aaaaaaaaaaa", "A song"),
                track("bbbbbbbbbbb", "An episode", contentType = "podcast"),
                track("ccccccccccc", "A track they hated")
            )
        )
        source.feedback().dislike(DislikeEntity(videoId = "ccccccccccc"))
        val id = source.playlists().create(PlaylistEntity(title = "Everything"))
        source.playlists().append(id, listOf("aaaaaaaaaaa", "bbbbbbbbbbb", "ccccccccccc"))
        source.settings().put(SettingEntity("audioQuality", "HIGH"))

        val summary = target.backup().restore(source.backup().export()).getOrThrow()

        assertEquals(3, summary.tracks)
        assertEquals(
            listOf("A song", "An episode", "A track they hated"),
            target.tracks().allTracks().map { it.title }
        )
        val restored = target.playlists().summariesNow().single()
        assertEquals(
            listOf("aaaaaaaaaaa", "bbbbbbbbbbb", "ccccccccccc"),
            target.playlists().orderedIds(restored.id)
        )
        assertEquals(listOf("ccccccccccc"), target.feedback().dislikedIds())
        assertEquals("HIGH", target.settings().allNow().single { it.key == "audioQuality" }.value)
    }

    /**
     * The regression this was written for: an imported playlist that comes back
     * as a hand-made list has lost the only thing that lets it be re-synced.
     */
    @Test
    fun anImportedPlaylistKeepsItsSourceIdentity() = runTest {
        source.tracks().insertIfAbsent(listOf(track("aaaaaaaaaaa", "One")))
        val id = source.playlists().create(
            PlaylistEntity(
                title = "From YouTube",
                sourceProvider = "youtube",
                sourceId = "PL123abc",
                sourceUrl = "https://www.youtube.com/playlist?list=PL123abc"
            )
        )
        source.playlists().append(id, listOf("aaaaaaaaaaa"))

        target.backup().restore(source.backup().export()).getOrThrow()

        val restored = target.playlists().summariesNow().single()
        val row = target.playlists().byId(restored.id)
        assertEquals("youtube", row?.sourceProvider)
        assertEquals("PL123abc", row?.sourceId)
        assertEquals("https://www.youtube.com/playlist?list=PL123abc", row?.sourceUrl)
    }

    /**
     * Statistics are sums over the history table. A second restore that inserts
     * the same plays again doubles every figure the Listening screen shows, and
     * merging is not by itself a defence against that.
     */
    @Test
    fun restoringTwiceDoesNotDuplicateHistoryOrInflateStatistics() = runTest {
        source.tracks().insertIfAbsent(listOf(track("aaaaaaaaaaa", "One")))
        val at = 1_700_000_000_000L
        repeat(3) { index ->
            source.history().record(
                HistoryEntryEntity(
                    videoId = "aaaaaaaaaaa",
                    playedAt = at + index * 60_000L,
                    listenedMs = 120_000L
                )
            )
        }
        val json = source.backup().export()

        target.backup().restore(json).getOrThrow()
        val afterFirst = target.history().allEntries()
        val msAfterFirst = target.history().listenedMsBetween(0, Long.MAX_VALUE)

        target.backup().restore(json).getOrThrow()
        val afterSecond = target.history().allEntries()

        assertEquals(3, afterFirst.size)
        assertEquals(3, afterSecond.size)
        assertEquals(360_000L, msAfterFirst)
        assertEquals(msAfterFirst, target.history().listenedMsBetween(0, Long.MAX_VALUE))
    }

    /** Restoring merges. It never duplicates a playlist or overwrites a track. */
    @Test
    fun restoringTwiceChangesNothingTheSecondTime() = runTest {
        source.tracks().insertIfAbsent(listOf(track("aaaaaaaaaaa", "Original title")))
        val id = source.playlists().create(PlaylistEntity(title = "Late night"))
        source.playlists().append(id, listOf("aaaaaaaaaaa"))
        val json = source.backup().export()

        target.tracks().insertIfAbsent(listOf(track("aaaaaaaaaaa", "Renamed by hand")))
        target.backup().restore(json).getOrThrow()
        target.backup().restore(json).getOrThrow()

        assertEquals(1, target.playlists().summariesNow().size)
        assertEquals("Renamed by hand", target.tracks().allTracks().single().title)
    }

    @Test
    fun followedChannelsTravel() = runTest {
        source.channels().follow(FollowedChannelEntity("UC123", "A podcast feed"))
        target.backup().restore(source.backup().export()).getOrThrow()
        assertEquals("A podcast feed", target.channels().allNow().single().name)
    }

    /**
     * The photo is the theme. Carrying the row and leaving the image behind
     * produces a theme that renders as nothing, so the bytes travel with it.
     */
    @Test
    fun aPhotoThemeTravelsWithItsWallpaper() = runTest {
        val wallpaper = File(context.filesDir, "themes").apply { mkdirs() }
            .resolve("test-wallpaper.jpg")
            .apply { writeBytes(ByteArray(4096) { (it % 251).toByte() }) }

        source.themes().insert(
            ThemeEntity(
                name = "Harbour",
                imagePath = wallpaper.absolutePath,
                seed = 0xFF2266AA.toInt(),
                useWallpaper = true,
                dim = 0.4f
            )
        )

        val summary = target.backup().restore(source.backup().export()).getOrThrow()

        assertEquals(1, summary.themes)
        val restored = target.themes().all().single()
        assertEquals("Harbour", restored.name)
        assertEquals(0.4f, restored.dim, 0.001f)
        assertTrue("The wallpaper did not travel", restored.useWallpaper)
        assertNotNull(restored.imagePath)
        val file = File(restored.imagePath!!)
        assertTrue("Restored wallpaper is missing from disk", file.isFile)
        assertEquals(4096L, file.length())

        file.delete()
        wallpaper.delete()
    }

    /**
     * A theme whose photo did not travel must not come back pointing at a path
     * on a device the user no longer has.
     */
    @Test
    fun aThemeWithoutItsPhotoIsRestoredAsColourOnlyRatherThanDangling() = runTest {
        source.themes().insert(
            ThemeEntity(
                name = "Ghost",
                imagePath = "/data/user/0/somewhere/else/gone.jpg",
                seed = 0xFF884422.toInt(),
                useWallpaper = true
            )
        )

        val summary = target.backup().restore(source.backup().export()).getOrThrow()

        val restored = target.themes().all().single()
        assertNull("A dangling path was restored", restored.imagePath)
        assertFalse("A wallpaper was promised that does not exist", restored.useWallpaper)
        assertEquals(0xFF884422.toInt(), restored.seed)
        assertEquals(1, summary.themes)
    }

    /** Downloaded audio is not in a backup, so nothing may claim to be offline. */
    @Test
    fun restoringNeverMarksTrackAsDownloaded() = runTest {
        source.tracks().insertIfAbsent(listOf(track("aaaaaaaaaaa", "One")))
        source.tracks().setCached("aaaaaaaaaaa", "/data/user/0/app/files/audio/one.m4a", 4_000_000)

        target.backup().restore(source.backup().export()).getOrThrow()

        val restored = target.tracks().allTracks().single()
        assertNull("A path from another device was restored", restored.cachedPath)
        assertNull(restored.cachedBytes)
        assertEquals(0, target.tracks().downloadedCount())
    }

    /** The saved queue describes a session on one device and must not travel. */
    @Test
    fun theSavedPlaybackQueueIsNotInTheBackup() = runTest {
        source.settings().put(SettingEntity("session", "aaaaaaaaaaa|0|12345|false|0"))
        source.settings().put(SettingEntity("audioQuality", "DATA_SAVER"))

        val json = source.backup().export()
        assertFalse(json.contains("\"session\""))

        target.backup().restore(json).getOrThrow()
        assertNull(target.settings().allNow().firstOrNull { it.key == "session" })
        assertEquals(
            "DATA_SAVER",
            target.settings().allNow().single { it.key == "audioQuality" }.value
        )
    }

    /**
     * A version-1 file has no formatVersion, no channels, no themes and no
     * playlist provenance. It still has to restore — that is the situation
     * backups exist for.
     */
    @Test
    fun aVersionOneBackupStillRestores() = runTest {
        val legacy = JSONObject()
            .put("format", "symus-backup/1")
            .put("exportedAt", 1_700_000_000_000L)
            .put(
                "tracks",
                org.json.JSONArray().put(
                    JSONObject()
                        .put("videoId", "aaaaaaaaaaa")
                        .put("title", "An old song")
                        .put("duration", 200)
                        .put("addedAt", 1_600_000_000_000L)
                )
            )
            .put(
                "playlists",
                org.json.JSONArray().put(
                    JSONObject()
                        .put("title", "Old list")
                        .put("createdAt", 1_600_000_000_000L)
                        .put("videoIds", org.json.JSONArray().put("aaaaaaaaaaa"))
                )
            )
            .toString()

        val inspection = target.backup().inspect(legacy).getOrThrow()
        assertEquals(1, inspection.formatVersion)
        assertTrue(inspection.older)

        val summary = target.backup().restore(legacy).getOrThrow()
        assertEquals(1, summary.tracks)
        assertEquals(1, summary.playlists)
        assertEquals(0, summary.themes)
        assertEquals("An old song", target.tracks().allTracks().single().title)
    }

    /** A file from a future build is refused rather than half-read. */
    @Test
    fun aNewerBackupIsRefusedWithAReason() = runTest {
        val future = JSONObject()
            .put("format", "symus-backup/9")
            .put("formatVersion", 9)
            .toString()

        val failure = target.backup().restore(future).exceptionOrNull()
        assertNotNull(failure)
        assertTrue(
            "The refusal should say why: ${failure?.message}",
            failure?.message?.contains("newer") == true
        )
    }

    @Test
    fun malformedFilesAreRefusedWithoutChangingAnything() = runTest {
        target.tracks().insertIfAbsent(listOf(track("aaaaaaaaaaa", "Untouched")))

        val cases = listOf(
            "not json at all",
            "",
            "{}",
            """{"format":"something-else"}""",
            """{"format":"symus-backup/2","formatVersion":2,"tracks":"not an array"}""",
            """{"format":"symus-backup/2","formatVersion":2,"tracks":[{"title":"no id"}]}"""
        )
        cases.forEach { case ->
            val result = target.backup().restore(case)
            assertTrue("Should have refused: $case", result.isFailure)
        }

        assertEquals("Untouched", target.tracks().allTracks().single().title)
    }

    /** A playlist that lists the same track twice must not abort the restore. */
    @Test
    fun aPlaylistWithDuplicateEntriesRestoresOnce() = runTest {
        val json = JSONObject()
            .put("format", "symus-backup/2")
            .put("formatVersion", 2)
            .put(
                "tracks",
                org.json.JSONArray().put(
                    JSONObject().put("videoId", "aaaaaaaaaaa").put("title", "One")
                )
            )
            .put(
                "playlists",
                org.json.JSONArray().put(
                    JSONObject()
                        .put("title", "Doubled")
                        .put(
                            "videoIds",
                            org.json.JSONArray().put("aaaaaaaaaaa").put("aaaaaaaaaaa")
                        )
                )
            )
            .toString()

        target.backup().restore(json).getOrThrow()
        val playlist = target.playlists().summariesNow().single()
        assertEquals(listOf("aaaaaaaaaaa"), target.playlists().orderedIds(playlist.id))
    }
}
