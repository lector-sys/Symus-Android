package io.github.lectorsys.symus.data

import android.content.Context
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What a backup contains, and what it deliberately does not.
 *
 * Included: the library rows, playlists with their order *and where they were
 * imported from*, likes, dislikes, saved flags, listening history with its
 * measured durations, followed channels, saved photo themes, search history and
 * settings — everything the app knows that the user created.
 *
 * Not included, and why:
 *
 * - **Downloaded audio.** Re-fetchable, and enormous next to the rest. A backup
 *   that is mostly cached audio is one nobody can email to themselves.
 * - **Artwork.** Re-fetchable from the same source as the track.
 * - **The saved playback queue.** It describes where a *device* was in a
 *   session. Restoring it onto another phone resumes a position nobody is at.
 * - **Download state.** A row saying "downloaded" whose file is not on this
 *   device is worse than no row: the UI would offer offline playback that
 *   cannot happen.
 *
 * The format is JSON rather than a copy of the database file because a restore
 * has to work across schema versions — a raw .db from an older build cannot be
 * opened by a newer one without a migration path, which is exactly the
 * situation people restore backups in.
 */
data class BackupSummary(
    val tracks: Int,
    val playlists: Int,
    val likes: Int,
    val history: Int,
    val channels: Int = 0,
    val themes: Int = 0,
    /** Themes restored without their photo, because the backup did not carry it. */
    val themesWithoutWallpaper: Int = 0
) {
    val isEmpty: Boolean
        get() = tracks == 0 && playlists == 0 && likes == 0 && history == 0 &&
            channels == 0 && themes == 0
}

/** What a file claims to be, before anything is changed. */
data class BackupInspection(
    val summary: BackupSummary,
    val formatVersion: Int,
    val exportedAt: Long,
    /** True when the file predates a field this build writes. Still restorable. */
    val older: Boolean
)

@Singleton
class Backup @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val tracks: TrackDao,
    private val playlists: PlaylistDao,
    private val feedback: FeedbackDao,
    private val history: HistoryDao,
    private val settings: SettingsDao,
    private val searches: SearchHistoryDao,
    private val channels: FollowedChannelDao,
    private val themes: ThemeDao
) {

    /** Serialises everything worth keeping into one JSON document. */
    suspend fun export(): String = withContext(Dispatchers.IO) {
        val root = JSONObject()
        root.put("format", FORMAT)
        root.put("formatVersion", VERSION)
        root.put("exportedAt", System.currentTimeMillis())

        root.put(
            "tracks",
            JSONArray().apply {
                tracks.allTracks().forEach { track ->
                    put(
                        JSONObject()
                            .put("videoId", track.videoId)
                            .put("title", track.title)
                            .putOpt("artist", track.artist)
                            .putOpt("album", track.album)
                            .putOpt("thumbnailUrl", track.thumbnailUrl)
                            .put("duration", track.duration)
                            .putOpt("genre", track.genre)
                            .putOpt("contentType", track.contentType)
                            .put("addedAt", track.addedAt)
                            .putOpt("savedAt", track.savedAt)
                            .put("resumeMs", track.resumeMs)
                        // cachedPath and cachedBytes are deliberately absent.
                        // They name a file on one device's disk.
                    )
                }
            }
        )

        root.put(
            "playlists",
            JSONArray().apply {
                playlists.summariesNow().forEach { summary ->
                    val row = playlists.byId(summary.id)
                    put(
                        JSONObject()
                            .put("title", summary.title)
                            .putOpt("coverUrl", summary.coverUrl)
                            .putOpt("genre", summary.genre)
                            .put("createdAt", summary.createdAt)
                            // Where it came from. Without these, a playlist
                            // imported from YouTube comes back as a hand-made
                            // list with no way to re-sync it.
                            .putOpt("sourceProvider", row?.sourceProvider)
                            .putOpt("sourceId", row?.sourceId)
                            .putOpt("sourceUrl", row?.sourceUrl)
                            .put("videoIds", JSONArray(playlists.orderedIds(summary.id)))
                    )
                }
            }
        )

        root.put("likes", JSONArray(feedback.likedIds()))
        root.put("dislikes", JSONArray(feedback.dislikedIds()))
        root.put("searches", JSONArray(searches.recent(50)))

        root.put(
            "channels",
            JSONArray().apply {
                channels.allNow().forEach { channel ->
                    put(
                        JSONObject()
                            .put("channelId", channel.channelId)
                            .put("name", channel.name)
                            .put("followedAt", channel.followedAt)
                    )
                }
            }
        )

        root.put("themes", exportThemes())

        root.put(
            "history",
            JSONArray().apply {
                history.allEntries().forEach { entry ->
                    put(
                        JSONObject()
                            .put("videoId", entry.videoId)
                            .put("playedAt", entry.playedAt)
                            .put("skipped", entry.skipped)
                            // The measured duration, which is what every
                            // listening statistic is summed from.
                            .put("listenedMs", entry.listenedMs)
                    )
                }
            }
        )

        root.put(
            "settings",
            JSONObject().apply {
                settings.allNow()
                    .filterNot { it.key in DEVICE_LOCAL_SETTINGS }
                    .forEach { setting -> put(setting.key, setting.value) }
            }
        )

        // The active theme travels by name. Its row id is local to the database
        // it came from and would select a different theme, or none, on restore.
        activeThemeName()?.let { root.put("activeThemeName", it) }

        root.toString(2)
    }

    /**
     * Photo themes, with their wallpapers inline where that is reasonable.
     *
     * Wallpapers are embedded rather than referenced because a path is
     * meaningless on another device, and a theme restored without its photo is
     * a theme that silently lost the thing it was made from. They are embedded
     * *up to a budget*, because base64 costs a third on top and a backup with
     * twenty full-resolution photos in it stops being a file anyone can move.
     *
     * Over budget, the row still travels; only the image is dropped, and the
     * restore says so rather than leaving a path pointing at nothing.
     */
    private suspend fun exportThemes(): JSONArray {
        var budget = WALLPAPER_BUDGET
        return JSONArray().apply {
            themes.all().forEach { theme ->
                val file = theme.imagePath?.let(::File)?.takeIf { it.isFile }
                val bytes = file?.takeIf { it.length() <= WALLPAPER_MAX && it.length() <= budget }
                    ?.readBytes()
                if (bytes != null) budget -= bytes.size

                put(
                    JSONObject()
                        .put("name", theme.name)
                        .put("seed", theme.seed)
                        .putOpt("accent", theme.accent)
                        .put("useWallpaper", theme.useWallpaper)
                        .put("dim", theme.dim.toDouble())
                        .put("blur", theme.blur.toDouble())
                        .put("focusX", theme.focusX.toDouble())
                        .put("focusY", theme.focusY.toDouble())
                        .put("createdAt", theme.createdAt)
                        .putOpt(
                            "wallpaper",
                            bytes?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
                        )
                        .put(
                            "wallpaperOmitted",
                            theme.imagePath != null && bytes == null
                        )
                )
            }
        }
    }

    private suspend fun activeThemeName(): String? {
        val id = settings.get(ACTIVE_THEME)?.toLongOrNull() ?: return null
        return themes.byId(id)?.name
    }

    /** What is in a backup file, without changing anything. */
    fun inspect(json: String): Result<BackupInspection> = runCatching {
        val root = JSONObject(json)
        require(root.optString("format").startsWith(FORMAT_PREFIX)) {
            "That file is not a SYmuS backup."
        }
        val version = root.optInt("formatVersion", 1)
        require(version <= VERSION) {
            "That backup was written by a newer version of SYmuS (format $version)."
        }
        require(root.isNull("tracks") || root.optJSONArray("tracks") != null) {
            "That backup is damaged: \"tracks\" is not a list."
        }
        BackupInspection(
            summary = BackupSummary(
                tracks = root.optJSONArray("tracks")?.length() ?: 0,
                playlists = root.optJSONArray("playlists")?.length() ?: 0,
                likes = root.optJSONArray("likes")?.length() ?: 0,
                history = root.optJSONArray("history")?.length() ?: 0,
                channels = root.optJSONArray("channels")?.length() ?: 0,
                themes = root.optJSONArray("themes")?.length() ?: 0
            ),
            formatVersion = version,
            exportedAt = root.optLong("exportedAt"),
            older = version < VERSION
        )
    }

    /**
     * Merges a backup into whatever is already here.
     *
     * Merge, not replace. Restoring onto a phone that has been used since the
     * backup was taken must not delete what happened in between, and a user who
     * restores the wrong file should not lose their library to it.
     *
     * Merging is not by itself a guarantee of safety, so the pieces that could
     * still double up are each handled explicitly:
     *
     * - **Tracks** insert-if-absent; an existing row keeps everything the user
     *   put on it.
     * - **Playlists** are matched by title and left alone if one exists, rather
     *   than duplicated or overwritten.
     * - **History** is unique on (videoId, playedAt) at the schema level, so a
     *   second restore of the same file adds nothing and no statistic moves.
     * - **Themes** are matched by name for the same reason as playlists.
     */
    suspend fun restore(json: String): Result<BackupSummary> = withContext(Dispatchers.IO) {
        runCatching {
            val root = JSONObject(json)
            require(root.optString("format").startsWith(FORMAT_PREFIX)) {
                "That file is not a SYmuS backup."
            }
            val version = root.optInt("formatVersion", 1)
            require(version <= VERSION) {
                "That backup was written by a newer version of SYmuS (format $version)."
            }

            // Present-but-wrong-type is corruption, not absence. optJSONArray
            // returns null for both, and treating a string "tracks" as an empty
            // library would report a successful restore of nothing at all.
            listOf("tracks", "playlists", "likes", "dislikes", "history", "channels", "themes")
                .forEach { key ->
                    require(root.isNull(key) || root.optJSONArray(key) != null) {
                        "That backup is damaged: \"$key\" is not a list."
                    }
                }
            require(root.isNull("settings") || root.optJSONObject("settings") != null) {
                "That backup is damaged: \"settings\" is not a set of values."
            }

            val restoredTracks = restoreTracks(root)
            val addedPlaylists = restorePlaylists(root)
            restoreFeedback(root)
            val restoredHistory = restoreHistory(root)
            val addedChannels = restoreChannels(root)
            val (addedThemes, withoutWallpaper) = restoreThemes(root)
            restoreSettings(root)

            BackupSummary(
                tracks = restoredTracks,
                playlists = addedPlaylists,
                likes = root.optJSONArray("likes")?.length() ?: 0,
                history = restoredHistory,
                channels = addedChannels,
                themes = addedThemes,
                themesWithoutWallpaper = withoutWallpaper
            )
        }
    }

    private suspend fun restoreTracks(root: JSONObject): Int {
        val array = root.optJSONArray("tracks") ?: JSONArray()
        val rows = buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(
                    TrackEntity(
                        videoId = item.getString("videoId"),
                        title = item.optString("title", "Unknown title"),
                        artist = item.optStringOrNull("artist"),
                        album = item.optStringOrNull("album"),
                        thumbnailUrl = item.optStringOrNull("thumbnailUrl"),
                        duration = item.optLong("duration", 0),
                        genre = item.optStringOrNull("genre"),
                        contentType = item.optStringOrNull("contentType"),
                        addedAt = item.optLong("addedAt", System.currentTimeMillis()),
                        savedAt = if (item.isNull("savedAt")) null else item.optLong("savedAt"),
                        resumeMs = item.optLong("resumeMs", 0)
                        // cachedPath stays null. Marking a track downloaded when
                        // its audio is on another device is a promise the player
                        // cannot keep.
                    )
                )
            }
        }
        if (rows.isNotEmpty()) tracks.insertIfAbsent(rows)
        return rows.size
    }

    private suspend fun restorePlaylists(root: JSONObject): Int {
        val existing = playlists.summariesNow().map { it.title }.toSet()
        val array = root.optJSONArray("playlists") ?: JSONArray()
        var added = 0
        for (index in 0 until array.length()) {
            val item = array.getJSONObject(index)
            val title = item.optString("title").ifBlank { continue }
            if (title in existing) continue

            val id = playlists.create(
                PlaylistEntity(
                    title = title,
                    coverUrl = item.optStringOrNull("coverUrl"),
                    genre = item.optStringOrNull("genre"),
                    createdAt = item.optLong("createdAt", System.currentTimeMillis()),
                    sourceProvider = item.optStringOrNull("sourceProvider"),
                    sourceId = item.optStringOrNull("sourceId"),
                    sourceUrl = item.optStringOrNull("sourceUrl")
                )
            )
            val ids = item.optJSONArray("videoIds") ?: JSONArray()
            // Order is the playlist. Duplicates within one are dropped because
            // playlist_tracks is keyed on (playlistId, videoId) and the second
            // copy would be rejected at the database, taking the restore with it.
            val ordered = (0 until ids.length()).map { ids.getString(it) }.distinct()
            if (ordered.isNotEmpty()) playlists.append(id, ordered)
            added++
        }
        return added
    }

    private suspend fun restoreFeedback(root: JSONObject) {
        val likes = root.optJSONArray("likes") ?: JSONArray()
        for (index in 0 until likes.length()) {
            runCatching { feedback.like(LikeEntity(videoId = likes.getString(index))) }
        }
        val dislikes = root.optJSONArray("dislikes") ?: JSONArray()
        for (index in 0 until dislikes.length()) {
            runCatching { feedback.dislike(DislikeEntity(videoId = dislikes.getString(index))) }
        }
    }

    private suspend fun restoreHistory(root: JSONObject): Int {
        val array = root.optJSONArray("history") ?: JSONArray()
        var restored = 0
        for (index in 0 until array.length()) {
            val item = array.getJSONObject(index)
            runCatching {
                history.record(
                    HistoryEntryEntity(
                        videoId = item.getString("videoId"),
                        playedAt = item.optLong("playedAt"),
                        skipped = item.optBoolean("skipped", false),
                        listenedMs = item.optLong("listenedMs", 0)
                    )
                )
                restored++
            }
        }
        return restored
    }

    private suspend fun restoreChannels(root: JSONObject): Int {
        val array = root.optJSONArray("channels") ?: JSONArray()
        var added = 0
        for (index in 0 until array.length()) {
            val item = array.getJSONObject(index)
            runCatching {
                channels.follow(
                    FollowedChannelEntity(
                        channelId = item.getString("channelId"),
                        name = item.optString("name", "Unknown channel"),
                        followedAt = item.optLong("followedAt", System.currentTimeMillis())
                    )
                )
                added++
            }
        }
        return added
    }

    /**
     * Restores photo themes, writing any embedded wallpaper back to disk.
     *
     * A theme whose photo did not travel is restored as a colour-only theme:
     * the accent survives, `useWallpaper` is forced off and `imagePath` stays
     * null. The alternative — keeping the original path — produces a theme that
     * points at a file on a phone the user no longer has, which renders as a
     * black screen with no explanation.
     */
    private suspend fun restoreThemes(root: JSONObject): Pair<Int, Int> {
        val array = root.optJSONArray("themes") ?: return 0 to 0
        val existing = themes.all().map { it.name }.toSet()
        val directory = File(context.filesDir, "themes").apply { mkdirs() }
        var added = 0
        var withoutWallpaper = 0

        for (index in 0 until array.length()) {
            val item = array.getJSONObject(index)
            val name = item.optString("name").ifBlank { continue }
            if (name in existing) continue

            val encoded = item.optStringOrNull("wallpaper")
            val file = encoded?.let {
                runCatching {
                    File(directory, "theme-restored-${System.nanoTime()}.jpg").apply {
                        writeBytes(Base64.decode(it, Base64.NO_WRAP))
                    }
                }.getOrNull()
            }
            if (file == null && item.optBoolean("wallpaperOmitted", false)) withoutWallpaper++

            runCatching {
                themes.insert(
                    ThemeEntity(
                        name = name,
                        imagePath = file?.absolutePath,
                        seed = item.optInt("seed"),
                        accent = if (item.isNull("accent")) null else item.optInt("accent"),
                        useWallpaper = item.optBoolean("useWallpaper", false) && file != null,
                        dim = item.optDouble("dim", 0.55).toFloat(),
                        blur = item.optDouble("blur", 0.35).toFloat(),
                        focusX = item.optDouble("focusX", 0.5).toFloat(),
                        focusY = item.optDouble("focusY", 0.5).toFloat(),
                        createdAt = item.optLong("createdAt", System.currentTimeMillis())
                    )
                )
                added++
            }
        }
        return added to withoutWallpaper
    }

    private suspend fun restoreSettings(root: JSONObject) {
        val settingsObject = root.optJSONObject("settings")
        settingsObject?.keys()?.forEach { key ->
            if (key in DEVICE_LOCAL_SETTINGS) return@forEach
            runCatching { settings.put(SettingEntity(key, settingsObject.getString(key))) }
        }

        // Re-point the active theme at the row that actually exists here.
        root.optStringOrNull("activeThemeName")?.let { name ->
            themes.all().firstOrNull { it.name == name }?.let { theme ->
                settings.put(SettingEntity(ACTIVE_THEME, theme.id.toString()))
            }
        }
    }

    private companion object {
        const val FORMAT_PREFIX = "symus-backup"
        const val VERSION = 2
        const val FORMAT = "symus-backup/2"
        const val ACTIVE_THEME = "activeThemeId"

        /**
         * Settings that describe this device rather than this user.
         *
         * `session` is the saved playback queue — a position in a listening
         * session, which means nothing on another phone. `activeThemeId` is a
         * local row id, remapped by name on restore instead.
         */
        val DEVICE_LOCAL_SETTINGS = setOf("session", ACTIVE_THEME)

        /** Per-wallpaper ceiling, and the total across all of them. */
        const val WALLPAPER_MAX = 3L * 1024 * 1024
        const val WALLPAPER_BUDGET = 12L * 1024 * 1024
    }
}

private fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }
