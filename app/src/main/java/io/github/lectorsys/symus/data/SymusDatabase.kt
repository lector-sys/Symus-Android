package io.github.lectorsys.symus.data

import androidx.room.Database
import androidx.room.RoomDatabase

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
        FollowedChannelEntity::class
    ],
    version = 1,
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

    companion object {
        const val NAME = "symus.db"
    }
}
