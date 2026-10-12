package io.github.lectorsys.symus.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.lectorsys.symus.data.DownloadDao
import io.github.lectorsys.symus.data.FeedbackDao
import io.github.lectorsys.symus.data.FollowedChannelDao
import io.github.lectorsys.symus.data.HistoryDao
import io.github.lectorsys.symus.data.ImportDao
import io.github.lectorsys.symus.data.LyricsDao
import io.github.lectorsys.symus.data.PlaylistDao
import io.github.lectorsys.symus.data.SearchHistoryDao
import io.github.lectorsys.symus.data.SettingsDao
import io.github.lectorsys.symus.data.SymusDatabase
import io.github.lectorsys.symus.data.ThemeDao
import io.github.lectorsys.symus.data.TrackDao
import io.github.lectorsys.symus.media.Extractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): SymusDatabase =
        Room.databaseBuilder(context, SymusDatabase::class.java, SymusDatabase.NAME)
            // Migrations, never a destructive rebuild: a version-1 database is
            // somebody's library, and losing their plays and playlists to an
            // app update is not an acceptable upgrade path.
            .addMigrations(
                SymusDatabase.MIGRATION_1_2,
                SymusDatabase.MIGRATION_2_3,
                SymusDatabase.MIGRATION_3_4
            )
            .build()

    @Provides
    fun tracks(db: SymusDatabase): TrackDao = db.tracks()

    @Provides
    fun history(db: SymusDatabase): HistoryDao = db.history()

    @Provides
    fun feedback(db: SymusDatabase): FeedbackDao = db.feedback()

    @Provides
    fun playlists(db: SymusDatabase): PlaylistDao = db.playlists()

    @Provides
    fun searchHistory(db: SymusDatabase): SearchHistoryDao = db.searchHistory()

    @Provides
    fun settings(db: SymusDatabase): SettingsDao = db.settings()

    @Provides
    fun channels(db: SymusDatabase): FollowedChannelDao = db.channels()

    @Provides
    fun downloads(db: SymusDatabase): DownloadDao = db.downloads()

    @Provides
    fun lyrics(db: SymusDatabase): LyricsDao = db.lyrics()

    @Provides
    fun imports(db: SymusDatabase): ImportDao = db.imports()

    @Provides
    fun themes(db: SymusDatabase): ThemeDao = db.themes()

    /**
     * One client for the whole app: the extractor, artwork loading, the player's
     * data source and the downloader all share its connection pool. Separate
     * clients would mean separate pools and repeated TLS handshakes to the same
     * few hosts.
     */
    @Provides
    @Singleton
    fun okHttp(): OkHttpClient = Extractor.okHttp()

    /**
     * Work that belongs to the application rather than to any screen: loading
     * settings, pruning the cache, draining the download queue.
     *
     * Deliberately not tied to a lifecycle. Anything cancelled when a screen
     * closes does not belong here.
     */
    @Provides
    @Singleton
    fun appScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
