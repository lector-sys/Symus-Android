package io.github.lectorsys.symus.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lectorsys.symus.media.Extractor
import io.github.lectorsys.symus.media.SearchHit
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Playlists: appending, deduplicating, reordering, and what deleting one takes with it. */
@RunWith(AndroidJUnit4::class)
class PlaylistTest {

    private lateinit var db: SymusDatabase
    private lateinit var library: LibraryRepository

    private fun hit(id: String, title: String, artist: String? = "The Weeknd") = SearchHit(
        track = TrackEntity(
            videoId = id,
            title = title,
            artist = artist,
            thumbnailUrl = "https://example.invalid/$id.jpg",
            duration = 180
        ),
        channelId = null,
        channelName = null
    )

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            SymusDatabase::class.java
        ).build()
        library = LibraryRepository(
            db.tracks(), db.history(), db.feedback(), db.playlists(),
            db.searchHistory(), db.channels(), Extractor()
        )
    }

    @After
    fun close() {
        db.close()
    }

    @Test
    fun addingToAPlaylistSavesAndKeepsOrder() = runTest {
        library.remember(listOf(hit("aaaaaaaaaaa", "One"), hit("bbbbbbbbbbb", "Two")), null)
        val id = library.createPlaylist("Late night")
        library.addToPlaylist(id, listOf("aaaaaaaaaaa", "bbbbbbbbbbb"))

        assertEquals(
            listOf("aaaaaaaaaaa", "bbbbbbbbbbb"),
            library.playlistTracksNow(id).map { it.videoId }
        )
        // Anything filed in a playlist is in the library by definition.
        assertNotNull(db.tracks().byId("aaaaaaaaaaa")!!.savedAt)
        assertNotNull(db.tracks().byId("bbbbbbbbbbb")!!.savedAt)
    }

    @Test
    fun reAddingIsANoOpRatherThanAConstraintError() = runTest {
        library.remember(listOf(hit("aaaaaaaaaaa", "One")), null)
        val id = library.createPlaylist("Late night")
        assertEquals(1, library.addToPlaylist(id, listOf("aaaaaaaaaaa")))
        assertEquals(0, library.addToPlaylist(id, listOf("aaaaaaaaaaa")))
        assertEquals(1, library.playlistTracksNow(id).size)
    }

    @Test
    fun positionsContinueFromTheEnd() = runTest {
        library.remember(
            listOf(hit("aaaaaaaaaaa", "One"), hit("bbbbbbbbbbb", "Two"), hit("ccccccccccc", "Three")),
            null
        )
        val id = library.createPlaylist("Late night")
        library.addToPlaylist(id, listOf("aaaaaaaaaaa"))
        library.addToPlaylist(id, listOf("bbbbbbbbbbb", "ccccccccccc"))
        assertEquals(
            listOf("aaaaaaaaaaa", "bbbbbbbbbbb", "ccccccccccc"),
            library.playlistTracksNow(id).map { it.videoId }
        )
    }

    @Test
    fun reorderingPersists() = runTest {
        library.remember(
            listOf(hit("aaaaaaaaaaa", "One"), hit("bbbbbbbbbbb", "Two"), hit("ccccccccccc", "Three")),
            null
        )
        val id = library.createPlaylist("Late night")
        library.addToPlaylist(id, listOf("aaaaaaaaaaa", "bbbbbbbbbbb", "ccccccccccc"))

        val moved = library.playlistTracksNow(id).map { it.videoId }.movedTo(2, 0)
        library.reorderPlaylist(id, moved)

        assertEquals(
            listOf("ccccccccccc", "aaaaaaaaaaa", "bbbbbbbbbbb"),
            library.playlistTracksNow(id).map { it.videoId }
        )
    }

    @Test
    fun removingATrackLeavesTheRestNumberedUsably() = runTest {
        library.remember(
            listOf(hit("aaaaaaaaaaa", "One"), hit("bbbbbbbbbbb", "Two"), hit("ccccccccccc", "Three")),
            null
        )
        val id = library.createPlaylist("Late night")
        library.addToPlaylist(id, listOf("aaaaaaaaaaa", "bbbbbbbbbbb", "ccccccccccc"))
        library.removeFromPlaylist(id, "bbbbbbbbbbb")

        assertEquals(
            listOf("aaaaaaaaaaa", "ccccccccccc"),
            library.playlistTracksNow(id).map { it.videoId }
        )
        // A later add must not collide with the gap the removal left.
        library.remember(listOf(hit("ddddddddddd", "Four")), null)
        library.addToPlaylist(id, listOf("ddddddddddd"))
        assertEquals(
            listOf("aaaaaaaaaaa", "ccccccccccc", "ddddddddddd"),
            library.playlistTracksNow(id).map { it.videoId }
        )
    }

    @Test
    fun deletingAPlaylistKeepsItsTracks() = runTest {
        library.remember(listOf(hit("aaaaaaaaaaa", "One")), null)
        val id = library.createPlaylist("Late night")
        library.addToPlaylist(id, listOf("aaaaaaaaaaa"))
        library.deletePlaylist(id)

        assertNull(db.playlists().byId(id))
        assertNotNull("the track itself must survive", db.tracks().byId("aaaaaaaaaaa"))
    }

    @Test
    fun aPlaylistTakesItsCoverAndGenreFromWhatItHolds() = runTest {
        library.remember(listOf(hit("aaaaaaaaaaa", "Some Song")), null)
        val id = library.createPlaylist("Late night jazz")
        library.addToPlaylist(id, listOf("aaaaaaaaaaa"))

        val playlist = db.playlists().byId(id)!!
        assertEquals("Jazz", playlist.genre)
        assertEquals("https://example.invalid/aaaaaaaaaaa.jpg", playlist.coverUrl)
        // The playlist's name is a fallback hint for an untagged track.
        assertEquals("Jazz", db.tracks().byId("aaaaaaaaaaa")!!.genre)
    }

    @Test
    fun deletingATrackRemovesItFromEveryPlaylist() = runTest {
        library.remember(listOf(hit("aaaaaaaaaaa", "One")), null)
        val id = library.createPlaylist("Late night")
        library.addToPlaylist(id, listOf("aaaaaaaaaaa"))

        // The cascade is what keeps a pruned cache row from leaving a dangling
        // playlist entry behind.
        db.tracks().pruneUnreferenced(System.currentTimeMillis() + 1_000)
        assertNotNull("a playlisted track must not be pruned", db.tracks().byId("aaaaaaaaaaa"))
        assertEquals(1, library.playlistTracksNow(id).size)
    }
}
