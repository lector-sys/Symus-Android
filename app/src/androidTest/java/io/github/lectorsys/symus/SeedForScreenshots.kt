package io.github.lectorsys.symus

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.Log
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lectorsys.symus.data.HistoryEntryEntity
import io.github.lectorsys.symus.data.LibraryRepository
import io.github.lectorsys.symus.data.PlaylistEntity
import io.github.lectorsys.symus.data.SettingEntity
import io.github.lectorsys.symus.data.SymusDatabase
import io.github.lectorsys.symus.data.ThemeEntity
import io.github.lectorsys.symus.media.Extractor
import io.github.lectorsys.symus.media.SearchKind
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Fills the app's real database with real content, so the screenshots show the
 * interface doing its job rather than an empty state.
 *
 * **Why this is a test and not a debug menu.** It needs the app's own uid to
 * write the app's own database, and it needs the real extractor to fetch real
 * tracks. An instrumentation test is the only thing that has both. It is never
 * part of a normal run — nothing else invokes it, and it is skipped outright
 * when there is no network.
 *
 * Everything it writes is genuine: the tracks come from YouTube through the
 * same extractor the Search screen uses. The listening history is synthetic and
 * says so, because sitting through four hours of playback to populate a
 * statistics screen is not a reasonable way to take a screenshot.
 */
@RunWith(AndroidJUnit4::class)
class SeedForScreenshots {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun seed() {
        runBlocking {
        val extractor = Extractor()
        val reachable = runCatching {
            extractor.suggestions("massive attack").isNotEmpty() ||
                extractor.search("massive attack", SearchKind.SONGS).hits.isNotEmpty()
        }.getOrDefault(false)
        assumeTrue("No network; cannot seed real content", reachable)

        val db = Room.databaseBuilder(context, SymusDatabase::class.java, SymusDatabase.NAME)
            .addMigrations(
                SymusDatabase.MIGRATION_1_2,
                SymusDatabase.MIGRATION_2_3,
                SymusDatabase.MIGRATION_3_4
            )
            .build()

        val library = LibraryRepository(
            db.tracks(), db.history(), db.feedback(), db.playlists(),
            db.searchHistory(), db.channels(), extractor
        )

        val queries = listOf(
            "massive attack teardrop" to "Trip hop",
            "aphex twin xtal" to "Electronic",
            "burial archangel" to "Electronic",
            "portishead glory box" to "Trip hop",
            "boards of canada roygbiv" to "Electronic",
            "radiohead weird fishes" to "Alternative"
        )

        val remembered = buildList {
            queries.forEach { (query, genre) ->
                runCatching {
                    val hits = extractor.search(query, SearchKind.SONGS).hits.take(4)
                    addAll(library.remember(hits, genre))
                    library.recordSearch(query)
                }.onFailure { Log.w(TAG, "search failed for $query: ${it.message}") }
            }
        }
        Log.i(TAG, "SEEDED_TRACKS ${remembered.size}")
        assumeTrue("Nothing came back from YouTube", remembered.isNotEmpty())

        // Saved and liked, so Library and Home have something to show.
        remembered.take(12).forEach { library.save(it.videoId) }
        remembered.take(5).forEach { library.setLiked(it.videoId, true) }

        val lateNight = library.createPlaylist("Late night")
        library.addToPlaylist(lateNight, remembered.take(9).map { it.videoId })
        val imported = db.playlists().create(
            PlaylistEntity(
                title = "From YouTube",
                sourceProvider = "youtube",
                sourceId = "PLscreenshotFixture",
                sourceUrl = "https://www.youtube.com/playlist?list=PLscreenshotFixture"
            )
        )
        library.addToPlaylist(imported, remembered.drop(4).take(7).map { it.videoId })

        // Synthetic listening history. Real enough in shape — measured
        // durations, some skips — and clearly not a record of anyone listening.
        val now = System.currentTimeMillis()
        var written = 0
        remembered.forEachIndexed { index, track ->
            val plays = (remembered.size - index).coerceIn(1, 9)
            repeat(plays) { play ->
                db.history().record(
                    HistoryEntryEntity(
                        videoId = track.videoId,
                        playedAt = now - (index * 6L + play) * 3_600_000L,
                        skipped = play % 7 == 6,
                        listenedMs = if (play % 7 == 6) 8_000L else 150_000L + play * 9_000L
                    )
                )
                written++
            }
        }
        Log.i(TAG, "SEEDED_PLAYS $written")

        // A photo theme, so the appearance screen has one to preview. Generated
        // rather than shipped: a gradient is a wallpaper for layout purposes and
        // carries nobody's copyright.
        val themeDir = File(context.filesDir, "themes").apply { mkdirs() }
        val wallpaper = File(themeDir, "screenshot-wallpaper.jpg")
        if (!wallpaper.exists()) {
            val bitmap = createBitmap(1080, 1920)
            wallpaper.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            bitmap.recycle()
        }
        if (db.themes().all().none { it.name == "Harbour dusk" }) {
            db.themes().insert(
                ThemeEntity(
                    name = "Harbour dusk",
                    imagePath = wallpaper.absolutePath,
                    seed = 0xFF3E6B8C.toInt(),
                    useWallpaper = true,
                    dim = 0.55f,
                    blur = 0.35f
                )
            )
        }

        db.settings().put(SettingEntity("historyEnabled", "true"))
        db.close()
            Log.i(TAG, "SEED_DONE")
        }
    }

    /** A dusk gradient with a bright band, which is what tests legibility. */
    private fun createBitmap(width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint().apply {
            shader = LinearGradient(
                0f, 0f, width.toFloat(), height.toFloat(),
                intArrayOf(
                    0xFF14202B.toInt(),
                    0xFF3E6B8C.toInt(),
                    0xFFE0A56B.toInt(),
                    0xFF2A1B22.toInt()
                ),
                floatArrayOf(0f, 0.45f, 0.62f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        return bitmap
    }

    private companion object {
        const val TAG = "SYMUS_SEED"
    }
}
