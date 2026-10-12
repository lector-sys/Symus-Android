package io.github.lectorsys.symus.data

import androidx.room.Room
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Upgrading somebody's library.
 *
 * A version-1 database is a real person's plays, playlists and offline files.
 * The whole reason the migration exists rather than a destructive rebuild is
 * that all of it has to come through, so this writes a version-1 database with
 * the version-1 DDL, opens it with the real database class — migration and all
 * — and reads it back.
 *
 * Opening it through Room is the important part: Room recomputes the schema
 * after the migration and refuses to open a database that does not match the
 * entities, so a migration that drifts from the schema fails this test rather
 * than shipping.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun removeAnyPreviousRun() {
        context.deleteDatabase(DB)
    }

    /**
     * The upgrade an early user actually gets: straight from 1 to the current
     * version, running every migration in the chain back to back.
     */
    @Test
    fun migratesOneToFourKeepingEverything() {
        writeVersionOneDatabase()

        val db = Room.databaseBuilder(context, SymusDatabase::class.java, DB)
            .addMigrations(
                SymusDatabase.MIGRATION_1_2,
                SymusDatabase.MIGRATION_2_3,
                SymusDatabase.MIGRATION_3_4
            )
            .build()

        try {
            runBlocking {
                val kept = db.tracks().byId("aaaaaaaaaaa")
                assertNotNull("the track did not survive the migration", kept)
                kept!!
                assertEquals("a hand-set genre was lost", "Shoegaze", kept.genre)
                assertEquals("addedAt moved", 1_700_000_000_000L, kept.addedAt)
                assertEquals("the offline file was orphaned", "/data/audio/a.webm", kept.cachedPath)
                assertEquals(4096L, kept.cachedBytes)
                assertEquals("After Hours", kept.album)
                // Liked, played, playlisted and downloaded: this was somebody's
                // library before the column existed, so it must arrive saved.
                assertEquals(1_700_000_000_000L, kept.savedAt)
                assertEquals(0L, kept.resumeMs)

                val onlySearched = db.tracks().byId("bbbbbbbbbbb")
                assertNotNull(onlySearched)
                assertNull("a row nobody touched must not arrive saved", onlySearched!!.savedAt)

                assertEquals(listOf("aaaaaaaaaaa"), db.feedback().likedIds())
                assertEquals(1, db.history().playCount("aaaaaaaaaaa"))
                assertEquals(
                    listOf("aaaaaaaaaaa"),
                    db.playlists().tracks(1).map { it.videoId }
                )
                assertEquals(listOf("blinding lights"), db.searchHistory().recent(10))

                // The new table exists and is empty, which is what an upgrade
                // should leave behind.
                assertTrue(db.downloads().pending().isEmpty())

                // And the migrated database still works for writing.
                db.tracks().setResume("aaaaaaaaaaa", 120_000)
                assertEquals(120_000L, db.tracks().byId("aaaaaaaaaaa")!!.resumeMs)

                // The v3 tables arrive empty, and the v3 columns arrive neutral.
                assertTrue(db.imports().recentJobs().isEmpty())
                assertTrue(db.themes().all().isEmpty())
                assertNull(db.playlists().byId(1)!!.sourceProvider)
            }
        } finally {
            db.close()
        }
    }

    /**
     * The upgrade from 2 to 3 on its own, with listening history in place.
     *
     * The assertion that matters is about a number nobody measured: a play
     * recorded before listenedMs existed arrives as 0, and 0 has to mean
     * "unmeasured" everywhere downstream. The statistics layer counts these
     * separately for exactly this reason - see StatisticsTest.
     */
    @Test
    fun migratesTwoToFourPreservingHistoryAndMarkingItUnmeasured() {
        writeVersionTwoDatabase()

        val db = Room.databaseBuilder(context, SymusDatabase::class.java, DB)
            .addMigrations(SymusDatabase.MIGRATION_2_3, SymusDatabase.MIGRATION_3_4)
            .build()

        try {
            runBlocking {
                val kept = db.tracks().byId("ccccccccccc")
                assertNotNull("the track did not survive 2 -> 3", kept)
                assertEquals("Jazz", kept!!.genre)
                assertEquals(1_700_000_000_000L, kept.savedAt)
                assertEquals(90_000L, kept.resumeMs)
                assertEquals("/data/audio/c.webm", kept.cachedPath)

                // Two plays, both from before measurement existed.
                assertEquals(2, db.history().playCount("ccccccccccc"))
                assertEquals(
                    "old plays must not claim listening time",
                    0L,
                    db.history().listenedMsBetween(0, Long.MAX_VALUE)
                )
                assertEquals(
                    "old plays must be reported as unmeasured, not as zero listening",
                    2,
                    db.history().unmeasuredPlaysBetween(0, Long.MAX_VALUE)
                )

                // The queued download survived, which is queue persistence.
                assertEquals(
                    listOf("ccccccccccc"),
                    db.downloads().pending().map { it.videoId }
                )
                assertEquals(listOf("ccccccccccc"), db.feedback().likedIds())
                assertEquals(listOf("ccccccccccc"), db.playlists().tracks(1).map { it.videoId })

                // And the new column accepts writes.
                db.history().addListened("ccccccccccc", 4_000)
                assertEquals(4_000L, db.history().listenedMsBetween(0, Long.MAX_VALUE))
                assertEquals(
                    "crediting one play must not clear the other's unmeasured flag",
                    1,
                    db.history().unmeasuredPlaysBetween(0, Long.MAX_VALUE)
                )
            }
        } finally {
            db.close()
        }
    }

    private fun writeVersionTwoDatabase() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(DB)
                .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        VERSION_TWO_DDL.forEach(db::execSQL)
                        VERSION_TWO_ROWS.forEach(db::execSQL)
                    }

                    override fun onUpgrade(
                        db: androidx.sqlite.db.SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int
                    ) = Unit
                })
                .build()
        )
        helper.writableDatabase.use { }
        helper.close()
    }

    /**
     * The version-1 schema, verbatim from the exported schemas/1.json.
     *
     * Written out by hand on purpose: this is a historical artifact, and a test
     * that generated it from today's entities would not be testing a migration.
     */
    private fun writeVersionOneDatabase() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(DB)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        VERSION_ONE_DDL.forEach(db::execSQL)
                        VERSION_ONE_ROWS.forEach(db::execSQL)
                    }

                    override fun onUpgrade(
                        db: androidx.sqlite.db.SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int
                    ) = Unit
                })
                .build()
        )
        // Opening it writable is what runs onCreate and stamps user_version = 1,
        // which is what makes Room treat it as a version-1 database.
        helper.writableDatabase.use { }
        helper.close()
    }


    /**
     * A version-3 database holding the same play twice, which is exactly what a
     * user who restored a backup twice on an older build ends up with.
     *
     * Built by running the real 2 -> 3 migration over the real version-2 DDL
     * rather than hand-writing a third copy of the schema: the point is to test
     * 3 -> 4 against what 2 -> 3 actually produces.
     */
    private fun writeVersionThreeDatabaseWithDuplicateHistory() {
        writeVersionTwoDatabase()

        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(DB)
                .callback(object : SupportSQLiteOpenHelper.Callback(3) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) = Unit

                    override fun onUpgrade(
                        db: androidx.sqlite.db.SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int
                    ) {
                        SymusDatabase.MIGRATION_2_3.migrate(db)
                    }
                })
                .build()
        )
        helper.writableDatabase.use { db ->
            db.execSQL(
                """
                INSERT INTO tracks
                  (videoId, title, artist, album, thumbnailUrl, duration, genre,
                   contentType, addedAt, savedAt, resumeMs, cachedPath, cachedBytes)
                VALUES
                  ('ddddddddddd', 'Teardrop', 'Massive Attack', NULL, NULL, 330,
                   NULL, NULL, 1700000000000, NULL, 0, NULL, NULL)
                """.trimIndent()
            )
            // The same play, written twice. This is the state the unique index
            // would refuse to be created over.
            repeat(2) {
                db.execSQL(
                    "INSERT INTO history (videoId, playedAt, skipped, listenedMs) " +
                        "VALUES ('ddddddddddd', 1700000000003, 0, 120000)"
                )
            }
            db.execSQL(
                "INSERT INTO history (videoId, playedAt, skipped, listenedMs) " +
                    "VALUES ('ddddddddddd', 1700000000009, 0, 60000)"
            )
        }
        helper.close()
    }

    /**
     * 3 -> 4 adds a uniqueness constraint to a table that may already violate it.
     *
     * A database written before this build could hold two rows for one play —
     * that is the bug the constraint exists to stop, and it is also exactly what
     * would make `CREATE UNIQUE INDEX` fail. The migration collapses duplicates
     * first, and this proves it on a database that has them.
     */
    @Test
    fun migratingToFourCollapsesDuplicatePlaysInsteadOfFailing() {
        writeVersionThreeDatabaseWithDuplicateHistory()

        val db = Room.databaseBuilder(context, SymusDatabase::class.java, DB)
            .addMigrations(SymusDatabase.MIGRATION_3_4)
            .build()

        try {
            runBlocking {
                // Scoped to this track: the version-2 fixture has history of its
                // own, and collapsing duplicates must not touch rows that are
                // already distinct.
                val entries = db.history().allEntries().filter { it.videoId == "ddddddddddd" }
                assertEquals("duplicate plays were not collapsed", 2, entries.size)
                assertEquals(
                    "an unrelated play was collapsed",
                    2,
                    db.history().allEntries().count { it.videoId == "ccccccccccc" }
                )
                assertEquals(
                    "the surviving rows are not the distinct ones",
                    listOf(1_700_000_000_003L, 1_700_000_000_009L),
                    entries.map { it.playedAt }.sorted()
                )
                // The measured duration on the kept row has to be the one that
                // was recorded, not a zero from whichever copy won.
                assertEquals(
                    120_000L,
                    entries.first { it.playedAt == 1_700_000_000_003L }.listenedMs
                )
            }
        } finally {
            db.close()
        }
    }

    private companion object {
        const val DB = "symus-migration-test.db"

        val VERSION_ONE_DDL = listOf(
            "CREATE TABLE IF NOT EXISTS `tracks` (`videoId` TEXT NOT NULL, `title` TEXT NOT NULL, `artist` TEXT, `album` TEXT, `thumbnailUrl` TEXT, `duration` INTEGER NOT NULL, `genre` TEXT, `contentType` TEXT, `addedAt` INTEGER NOT NULL, `cachedPath` TEXT, `cachedBytes` INTEGER, PRIMARY KEY(`videoId`))",
            "CREATE INDEX IF NOT EXISTS `index_tracks_artist` ON `tracks` (`artist`)",
            "CREATE INDEX IF NOT EXISTS `index_tracks_album` ON `tracks` (`album`)",
            "CREATE INDEX IF NOT EXISTS `index_tracks_genre` ON `tracks` (`genre`)",
            "CREATE TABLE IF NOT EXISTS `playlists` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `coverUrl` TEXT, `genre` TEXT, `createdAt` INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS `playlist_tracks` (`playlistId` INTEGER NOT NULL, `videoId` TEXT NOT NULL, `position` INTEGER NOT NULL, PRIMARY KEY(`playlistId`, `videoId`), FOREIGN KEY(`playlistId`) REFERENCES `playlists`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`videoId`) REFERENCES `tracks`(`videoId`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_playlist_tracks_videoId` ON `playlist_tracks` (`videoId`)",
            "CREATE INDEX IF NOT EXISTS `index_playlist_tracks_playlistId_position` ON `playlist_tracks` (`playlistId`, `position`)",
            "CREATE TABLE IF NOT EXISTS `likes` (`videoId` TEXT NOT NULL, `likedAt` INTEGER NOT NULL, PRIMARY KEY(`videoId`), FOREIGN KEY(`videoId`) REFERENCES `tracks`(`videoId`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE TABLE IF NOT EXISTS `dislikes` (`videoId` TEXT NOT NULL, `dislikedAt` INTEGER NOT NULL, PRIMARY KEY(`videoId`), FOREIGN KEY(`videoId`) REFERENCES `tracks`(`videoId`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE TABLE IF NOT EXISTS `history` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `videoId` TEXT NOT NULL, `playedAt` INTEGER NOT NULL, `skipped` INTEGER NOT NULL, FOREIGN KEY(`videoId`) REFERENCES `tracks`(`videoId`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_history_videoId` ON `history` (`videoId`)",
            "CREATE INDEX IF NOT EXISTS `index_history_playedAt` ON `history` (`playedAt`)",
            "CREATE TABLE IF NOT EXISTS `lyrics` (`videoId` TEXT NOT NULL, `synced` TEXT, `plain` TEXT, `fetchedAt` INTEGER NOT NULL, PRIMARY KEY(`videoId`), FOREIGN KEY(`videoId`) REFERENCES `tracks`(`videoId`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE TABLE IF NOT EXISTS `search_queries` (`query` TEXT NOT NULL, `at` INTEGER NOT NULL, PRIMARY KEY(`query`))",
            "CREATE TABLE IF NOT EXISTS `settings` (`key` TEXT NOT NULL, `value` TEXT NOT NULL, PRIMARY KEY(`key`))",
            "CREATE TABLE IF NOT EXISTS `followed_channels` (`channelId` TEXT NOT NULL, `name` TEXT NOT NULL, `followedAt` INTEGER NOT NULL, PRIMARY KEY(`channelId`))"
        )

        val VERSION_ONE_ROWS = listOf(
            """
            INSERT INTO tracks
              (videoId, title, artist, album, thumbnailUrl, duration, genre, contentType,
               addedAt, cachedPath, cachedBytes)
            VALUES
              ('aaaaaaaaaaa', 'Blinding Lights', 'The Weeknd', 'After Hours',
               'https://example.invalid/a.jpg', 200, 'Shoegaze', NULL,
               1700000000000, '/data/audio/a.webm', 4096)
            """.trimIndent(),
            """
            INSERT INTO tracks (videoId, title, duration, addedAt)
            VALUES ('bbbbbbbbbbb', 'Only Searched', 120, 1700000000001)
            """.trimIndent(),
            "INSERT INTO likes (videoId, likedAt) VALUES ('aaaaaaaaaaa', 1700000000002)",
            "INSERT INTO history (videoId, playedAt, skipped) VALUES ('aaaaaaaaaaa', 1700000000003, 0)",
            "INSERT INTO playlists (title, coverUrl, genre, createdAt) VALUES ('Late night', NULL, NULL, 1700000000004)",
            "INSERT INTO playlist_tracks (playlistId, videoId, position) VALUES (1, 'aaaaaaaaaaa', 0)",
            "INSERT INTO search_queries (`query`, at) VALUES ('blinding lights', 1700000000005)"
        )

        /** The version-2 schema, verbatim from the exported schemas/2.json. */
        val VERSION_TWO_DDL = listOf(
            "CREATE TABLE IF NOT EXISTS `tracks` (`videoId` TEXT NOT NULL, `title` TEXT NOT NULL, `artist` TEXT, `album` TEXT, `thumbnailUrl` TEXT, `duration` INTEGER NOT NULL, `genre` TEXT, `contentType` TEXT, `addedAt` INTEGER NOT NULL, `savedAt` INTEGER, `resumeMs` INTEGER NOT NULL, `cachedPath` TEXT, `cachedBytes` INTEGER, PRIMARY KEY(`videoId`))",
            "CREATE INDEX IF NOT EXISTS `index_tracks_artist` ON `tracks` (`artist`)",
            "CREATE INDEX IF NOT EXISTS `index_tracks_album` ON `tracks` (`album`)",
            "CREATE INDEX IF NOT EXISTS `index_tracks_genre` ON `tracks` (`genre`)",
            "CREATE INDEX IF NOT EXISTS `index_tracks_savedAt` ON `tracks` (`savedAt`)",
            "CREATE TABLE IF NOT EXISTS `playlists` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `coverUrl` TEXT, `genre` TEXT, `createdAt` INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS `playlist_tracks` (`playlistId` INTEGER NOT NULL, `videoId` TEXT NOT NULL, `position` INTEGER NOT NULL, PRIMARY KEY(`playlistId`, `videoId`), FOREIGN KEY(`playlistId`) REFERENCES `playlists`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`videoId`) REFERENCES `tracks`(`videoId`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_playlist_tracks_videoId` ON `playlist_tracks` (`videoId`)",
            "CREATE INDEX IF NOT EXISTS `index_playlist_tracks_playlistId_position` ON `playlist_tracks` (`playlistId`, `position`)",
            "CREATE TABLE IF NOT EXISTS `likes` (`videoId` TEXT NOT NULL, `likedAt` INTEGER NOT NULL, PRIMARY KEY(`videoId`), FOREIGN KEY(`videoId`) REFERENCES `tracks`(`videoId`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE TABLE IF NOT EXISTS `dislikes` (`videoId` TEXT NOT NULL, `dislikedAt` INTEGER NOT NULL, PRIMARY KEY(`videoId`), FOREIGN KEY(`videoId`) REFERENCES `tracks`(`videoId`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE TABLE IF NOT EXISTS `history` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `videoId` TEXT NOT NULL, `playedAt` INTEGER NOT NULL, `skipped` INTEGER NOT NULL, FOREIGN KEY(`videoId`) REFERENCES `tracks`(`videoId`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_history_videoId` ON `history` (`videoId`)",
            "CREATE INDEX IF NOT EXISTS `index_history_playedAt` ON `history` (`playedAt`)",
            "CREATE TABLE IF NOT EXISTS `lyrics` (`videoId` TEXT NOT NULL, `synced` TEXT, `plain` TEXT, `fetchedAt` INTEGER NOT NULL, PRIMARY KEY(`videoId`), FOREIGN KEY(`videoId`) REFERENCES `tracks`(`videoId`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE TABLE IF NOT EXISTS `search_queries` (`query` TEXT NOT NULL, `uses` INTEGER NOT NULL, `at` INTEGER NOT NULL, PRIMARY KEY(`query`))",
            "CREATE TABLE IF NOT EXISTS `settings` (`key` TEXT NOT NULL, `value` TEXT NOT NULL, PRIMARY KEY(`key`))",
            "CREATE TABLE IF NOT EXISTS `followed_channels` (`channelId` TEXT NOT NULL, `name` TEXT NOT NULL, `followedAt` INTEGER NOT NULL, PRIMARY KEY(`channelId`))",
            "CREATE TABLE IF NOT EXISTS `downloads` (`videoId` TEXT NOT NULL, `state` TEXT NOT NULL, `bytes` INTEGER NOT NULL, `total` INTEGER NOT NULL, `error` TEXT, `requestedAt` INTEGER NOT NULL, PRIMARY KEY(`videoId`), FOREIGN KEY(`videoId`) REFERENCES `tracks`(`videoId`) ON UPDATE NO ACTION ON DELETE CASCADE )"
        )

        val VERSION_TWO_ROWS = listOf(
            """
            INSERT INTO tracks
              (videoId, title, artist, album, thumbnailUrl, duration, genre, contentType,
               addedAt, savedAt, resumeMs, cachedPath, cachedBytes)
            VALUES
              ('ccccccccccc', 'Take Five', 'Dave Brubeck', 'Time Out',
               'https://example.invalid/c.jpg', 324, 'Jazz', NULL,
               1700000000000, 1700000000000, 90000, '/data/audio/c.webm', 8192)
            """.trimIndent(),
            "INSERT INTO likes (videoId, likedAt) VALUES ('ccccccccccc', 1700000000002)",
            "INSERT INTO history (videoId, playedAt, skipped) VALUES ('ccccccccccc', 1700000000003, 0)",
            "INSERT INTO history (videoId, playedAt, skipped) VALUES ('ccccccccccc', 1700000000004, 0)",
            "INSERT INTO playlists (title, coverUrl, genre, createdAt) VALUES ('Cool jazz', NULL, NULL, 1700000000005)",
            "INSERT INTO playlist_tracks (playlistId, videoId, position) VALUES (1, 'ccccccccccc', 0)",
            """
            INSERT INTO downloads (videoId, state, bytes, total, error, requestedAt)
            VALUES ('ccccccccccc', 'QUEUED', 0, 0, NULL, 1700000000006)
            """.trimIndent()
        )
    }
}
