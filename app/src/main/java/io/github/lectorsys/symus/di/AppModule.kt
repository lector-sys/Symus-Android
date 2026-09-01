package io.github.lectorsys.symus.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.lectorsys.symus.data.FeedbackDao
import io.github.lectorsys.symus.data.FollowedChannelDao
import io.github.lectorsys.symus.data.HistoryDao
import io.github.lectorsys.symus.data.PlaylistDao
import io.github.lectorsys.symus.data.SearchHistoryDao
import io.github.lectorsys.symus.data.SettingsDao
import io.github.lectorsys.symus.data.SymusDatabase
import io.github.lectorsys.symus.data.TrackDao
import io.github.lectorsys.symus.media.Extractor
import okhttp3.OkHttpClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): SymusDatabase =
        Room.databaseBuilder(context, SymusDatabase::class.java, SymusDatabase.NAME).build()

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

    /**
     * One client for the whole app: the extractor, artwork loading and, later,
     * the player's data source all share its connection pool. Separate clients
     * would mean separate pools and three TLS handshakes to the same hosts.
     */
    @Provides
    @Singleton
    fun okHttp(): OkHttpClient = Extractor.okHttp()
}
