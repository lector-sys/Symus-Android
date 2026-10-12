package io.github.lectorsys.symus.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        TrackEntity::class,
        PlaylistEntity::class,
        PlaylistTrackEntity::class,
        LikeEntity::class,
        DislikeEntity::class,
        HistoryEntryEntity::class,
        LyricsEntity::class,
        SearchQueryEntity::class,
        SettingEntity::class,
        FollowedChannelEntity::class,
        DownloadEntity::class,
        ImportJobEntity::class,
        ImportItemEntity::class,
        ThemeEntity::class
    ],
    version = 4,
    exportSchema = true
)
abstract class SymusDatabase : RoomDatabase() {
    abstract fun tracks(): TrackDao
    abstract fun history(): HistoryDao
    abstract fun feedback(): FeedbackDao
    abstract fun playlists(): PlaylistDao
    abstract fun searchHistory(): SearchHistoryDao
    abstract fun settings(): SettingsDao
    abstract fun channels(): FollowedChannelDao
    abstract fun downloads(): DownloadDao
    abstract fun lyrics(): LyricsDao
    abstract fun imports(): ImportDao
    abstract fun themes(): ThemeDao

    companion object {
        const val NAME = "symus.db"

        /**
         * 1 -> 2: explicit saving, resume positions, the download queue, and
         * search counts.
         *
         * Every statement is additive. A version-1 database was already someone's
         * library, and the whole point of a migration over a destructive rebuild
         * is that their plays, playlists and offline files come through intact —
         * so nothing here drops or rewrites an existing row.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tracks ADD COLUMN savedAt INTEGER")
                db.execSQL("ALTER TABLE tracks ADD COLUMN resumeMs INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tracks_savedAt ON tracks (savedAt)")
                db.execSQL("ALTER TABLE search_queries ADD COLUMN uses INTEGER NOT NULL DEFAULT 1")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS downloads (
                      videoId TEXT NOT NULL,
                      state TEXT NOT NULL,
                      bytes INTEGER NOT NULL,
                      total INTEGER NOT NULL,
                      error TEXT,
                      requestedAt INTEGER NOT NULL,
                      PRIMARY KEY(videoId),
                      FOREIGN KEY(videoId) REFERENCES tracks(videoId)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                // A version-1 library predates the saved/seen distinction, and
                // everything in it arrived because the user did something with it.
                // Anything they liked, filed, played or downloaded is therefore
                // treated as saved, rather than silently emptying their library.
                db.execSQL(
                    """
                    UPDATE tracks SET savedAt = addedAt WHERE videoId IN (
                      SELECT videoId FROM likes
                      UNION SELECT videoId FROM playlist_tracks
                      UNION SELECT videoId FROM history
                      UNION SELECT videoId FROM tracks WHERE cachedPath IS NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * 2 -> 3: measured listening time, playlist provenance, the import
         * tables, and saved image themes.
         *
         * Additive in the same way MIGRATION_1_2 is, and for the same reason.
         * The one thing worth stating plainly: `listenedMs` defaults to 0 on
         * every existing row, and 0 here means "this play predates measurement",
         * not "they listened for no time". Statistics report those rows as
         * unmeasured rather than averaging them in, because inventing a duration
         * for someone's old history is exactly the kind of confident fiction the
         * assistant is not allowed to produce.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE history ADD COLUMN listenedMs INTEGER NOT NULL DEFAULT 0")

                db.execSQL("ALTER TABLE playlists ADD COLUMN sourceProvider TEXT")
                db.execSQL("ALTER TABLE playlists ADD COLUMN sourceId TEXT")
                db.execSQL("ALTER TABLE playlists ADD COLUMN sourceUrl TEXT")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS import_jobs (
                      id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                      provider TEXT NOT NULL,
                      sourceId TEXT NOT NULL,
                      sourceUrl TEXT,
                      title TEXT NOT NULL,
                      owner TEXT,
                      artworkUrl TEXT,
                      state TEXT NOT NULL,
                      totalTracks INTEGER NOT NULL,
                      fetchedTracks INTEGER NOT NULL,
                      nextPageToken TEXT,
                      playlistId INTEGER,
                      error TEXT,
                      createdAt INTEGER NOT NULL,
                      updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_import_jobs_provider_sourceId " +
                        "ON import_jobs (provider, sourceId)"
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS import_items (
                      id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                      jobId INTEGER NOT NULL,
                      position INTEGER NOT NULL,
                      sourceTrackId TEXT,
                      isrc TEXT,
                      title TEXT NOT NULL,
                      artist TEXT,
                      album TEXT,
                      durationSec INTEGER NOT NULL,
                      version TEXT,
                      explicit INTEGER,
                      matchedVideoId TEXT,
                      confidence REAL NOT NULL,
                      matchState TEXT NOT NULL,
                      userEdited INTEGER NOT NULL,
                      FOREIGN KEY(jobId) REFERENCES import_jobs(id)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_import_items_jobId ON import_items (jobId)")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_import_items_jobId_position " +
                        "ON import_items (jobId, position)"
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS themes (
                      id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                      name TEXT NOT NULL,
                      imagePath TEXT,
                      seed INTEGER NOT NULL,
                      accent INTEGER,
                      useWallpaper INTEGER NOT NULL,
                      dim REAL NOT NULL,
                      blur REAL NOT NULL,
                      focusX REAL NOT NULL,
                      focusY REAL NOT NULL,
                      createdAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * 3 -> 4: makes a history row identifiable, so restoring a backup twice cannot
         * count the same play twice.
         *
         * Before this, `history` had only an autoincrement id, which means two
         * restores of the same file produced two rows for one listen — and
         * every statistic built on that table quietly doubled. A play is
         * identified by what it is: a track, at an instant. Two plays of one
         * track in the same millisecond is not a thing that happens.
         *
         * Existing duplicates are collapsed first, keeping the lowest id, or
         * the index could not be created on a database that already has them.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    DELETE FROM history WHERE id NOT IN (
                      SELECT MIN(id) FROM history GROUP BY videoId, playedAt
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_history_videoId_playedAt " +
                        "ON history (videoId, playedAt)"
                )
            }
        }
    }
}
