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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What a repeat search must not destroy.
 *
 * The first version of this port wrote search results with a whole-row upsert,
 * which quietly overwrote the genre somebody had typed, reset addedAt so
 * "recently added" reordered itself, and nulled the offline path so a
 * downloaded file was orphaned on disk. Every case below is one of those.
 */
@RunWith(AndroidJUnit4::class)
class LibraryPreservationTest {

    private lateinit var db: SymusDatabase
    private lateinit var library: LibraryRepository

    private val hit = SearchHit(
        track = TrackEntity(
            videoId = "aaaaaaaaaaa",
            title = "Blinding Lights",
            artist = "The Weeknd",
            thumbnailUrl = "https://example.invalid/a.jpg",
            duration = 200
        ),
        channelId = "UC123",
        channelName = "The Weeknd"
    )

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            SymusDatabase::class.java
        ).build()
        library = LibraryRepository(
            tracks = db.tracks(),
            history = db.history(),
            feedback = db.feedback(),
            playlists = db.playlists(),
            searchHistory = db.searchHistory(),
            channels = db.channels(),
            // Never reached: nothing below asks for a network top-up.
            extractor = Extractor()
        )
    }

    @After
    fun close() {
        db.close()
    }

    // -- the defect this test exists for ----------------------------------

    @Test
    fun repeatedSearchKeepsEverythingTheUserOwns() = runTest {
        library.remember(listOf(hit), hint = null)
        library.save("aaaaaaaaaaa")
        library.setGenre("aaaaaaaaaaa", "Shoegaze")
        db.tracks().setCached("aaaaaaaaaaa", "/data/audio/aaaaaaaaaaa.webm", 4_096)
        db.tracks().setResume("aaaaaaaaaaa", 90_000)
        db.tracks().setAlbum("aaaaaaaaaaa", "After Hours")
        val before = db.tracks().byId("aaaaaaaaaaa")!!

        // The same video comes back from a later search, and this time the
        // query is full of genre words.
        library.remember(listOf(hit), hint = "jazz essentials")
        val after = db.tracks().byId("aaaaaaaaaaa")!!

        assertEquals("a hand-set genre was overwritten", "Shoegaze", after.genre)
        assertEquals("addedAt moved", before.addedAt, after.addedAt)
        assertEquals("savedAt moved", before.savedAt, after.savedAt)
        assertEquals("the offline file was orphaned", before.cachedPath, after.cachedPath)
        assertEquals(before.cachedBytes, after.cachedBytes)
        assertEquals("the resume point was lost", 90_000L, after.resumeMs)
        assertEquals("the album was cleared", "After Hours", after.album)
    }

    @Test
    fun aSparseSightingNeverBlanksRicherMetadata() = runTest {
        library.remember(listOf(hit), null)

        // A channel listing knows the title and nothing else.
        library.remember(
            listOf(hit.copy(track = hit.track.copy(artist = null, thumbnailUrl = null, duration = 0))),
            null
        )

        val row = db.tracks().byId("aaaaaaaaaaa")!!
        assertEquals("The Weeknd", row.artist)
        assertEquals("https://example.invalid/a.jpg", row.thumbnailUrl)
        assertEquals(200L, row.duration)
    }

    @Test
    fun youtubesOwnFieldsDoGetRefreshed() = runTest {
        library.remember(listOf(hit), null)
        library.remember(
            listOf(hit.copy(track = hit.track.copy(title = "Blinding Lights (Remastered)"))),
            null
        )
        assertEquals("Blinding Lights (Remastered)", db.tracks().byId("aaaaaaaaaaa")!!.title)
    }

    @Test
    fun anInferredGenreFillsAnEmptyOneOnly() = runTest {
        // Nothing in the title says a genre, and the hint does.
        library.remember(listOf(hit), hint = "best jazz standards")
        assertEquals("Jazz", db.tracks().byId("aaaaaaaaaaa")!!.genre)

        // A second search with a different hint must not reclassify it.
        library.remember(listOf(hit), hint = "thrash metal classics")
        assertEquals("Jazz", db.tracks().byId("aaaaaaaaaaa")!!.genre)
    }

    @Test
    fun theTracksOwnTextBeatsTheSearchItCameFrom() = runTest {
        val metal = hit.copy(
            track = hit.track.copy(videoId = "bbbbbbbbbbb", title = "Thrash Metal Classics")
        )
        library.remember(listOf(metal), hint = "jazz essentials")
        assertEquals("Metal", db.tracks().byId("bbbbbbbbbbb")!!.genre)
    }

    // -- saving is explicit ------------------------------------------------

    @Test
    fun searchingDoesNotPutAnythingInTheLibrary() = runTest {
        library.remember(listOf(hit), null)
        assertNull(db.tracks().byId("aaaaaaaaaaa")!!.savedAt)
    }

    @Test
    fun likingSaves() = runTest {
        library.remember(listOf(hit), null)
        library.setLiked("aaaaaaaaaaa", true)
        assertNotNull(db.tracks().byId("aaaaaaaaaaa")!!.savedAt)
        assertEquals(listOf("aaaaaaaaaaa"), db.feedback().likedIds())
    }

    @Test
    fun likeAndDislikeAreMutuallyExclusive() = runTest {
        library.remember(listOf(hit), null)
        library.setLiked("aaaaaaaaaaa", true)
        library.setDisliked("aaaaaaaaaaa", true)
        assertTrue(db.feedback().likedIds().isEmpty())
        assertEquals(listOf("aaaaaaaaaaa"), db.feedback().dislikedIds())

        library.setLiked("aaaaaaaaaaa", true)
        assertTrue(db.feedback().dislikedIds().isEmpty())
    }

    @Test
    fun unsavingLeavesPlaysAndLikesAlone() = runTest {
        library.remember(listOf(hit), null)
        library.setLiked("aaaaaaaaaaa", true)
        library.recordPlay("aaaaaaaaaaa")
        library.unsave("aaaaaaaaaaa")

        assertNull(db.tracks().byId("aaaaaaaaaaa")!!.savedAt)
        assertEquals(1, db.history().playCount("aaaaaaaaaaa"))
        assertEquals(listOf("aaaaaaaaaaa"), db.feedback().likedIds())
    }

    // -- plays -------------------------------------------------------------

    @Test
    fun theSamePlayReportedTwiceCountsOnce() = runTest {
        library.remember(listOf(hit), null)
        assertTrue(library.recordPlay("aaaaaaaaaaa"))
        // A controller reconnecting re-announces the current item.
        assertTrue(!library.recordPlay("aaaaaaaaaaa"))
        assertEquals(1, db.history().playCount("aaaaaaaaaaa"))
    }

    @Test
    fun aPlayOfSomethingUnknownIsNotRecorded() = runTest {
        assertTrue(!library.recordPlay("zzzzzzzzzzz"))
        assertEquals(0, db.history().playCount("zzzzzzzzzzz"))
    }

    @Test
    fun skipMarksOnlyTheLatestPlay() = runTest {
        library.remember(listOf(hit), null)
        db.history().record(HistoryEntryEntity(videoId = "aaaaaaaaaaa", playedAt = 1_000))
        db.history().record(HistoryEntryEntity(videoId = "aaaaaaaaaaa", playedAt = 2_000))
        library.markSkipped("aaaaaaaaaaa")

        val tallies = db.history().playTallies()
        assertEquals(1, tallies.first { it.skipped }.plays)
        assertEquals(1, tallies.first { !it.skipped }.plays)
    }

    @Test
    fun aResumePointIsOnlyKeptWhenItIsWorthKeeping() = runTest {
        library.remember(listOf(hit), null)

        // Ten seconds in: starting over is what anybody would expect.
        library.setResume("aaaaaaaaaaa", 10_000, 200_000)
        assertEquals(0L, db.tracks().byId("aaaaaaaaaaa")!!.resumeMs)

        // Two minutes into a long track: worth coming back to.
        library.setResume("aaaaaaaaaaa", 120_000, 600_000)
        assertEquals(120_000L, db.tracks().byId("aaaaaaaaaaa")!!.resumeMs)

        // Near the end: resuming there would be pointless.
        library.setResume("aaaaaaaaaaa", 595_000, 600_000)
        assertEquals(0L, db.tracks().byId("aaaaaaaaaaa")!!.resumeMs)
    }

    // -- genres ------------------------------------------------------------

    @Test
    fun taggingCarriesToTheArtistsUntaggedTracks() = runTest {
        val second = hit.copy(track = hit.track.copy(videoId = "bbbbbbbbbbb", title = "Starboy"))
        val third = hit.copy(track = hit.track.copy(videoId = "ccccccccccc", title = "Sacrifice"))
        val other = hit.copy(
            track = hit.track.copy(videoId = "ddddddddddd", title = "So What", artist = "Miles Davis")
        )
        library.remember(listOf(hit, second, third, other), null)

        // Real titles carry no genre word, so inference tags nothing and manual
        // tagging is the only thing that ever fills the column. Propagating by
        // artist is what makes that one tap worth making.
        val alsoTagged = library.setGenre("aaaaaaaaaaa", "R&B")
        assertEquals("both untagged siblings should follow", 2, alsoTagged)
        assertEquals("R&B", db.tracks().byId("bbbbbbbbbbb")!!.genre)
        assertEquals("R&B", db.tracks().byId("ccccccccccc")!!.genre)
        assertNull("another artist must not be touched", db.tracks().byId("ddddddddddd")!!.genre)
    }

    @Test
    fun retaggingOneTrackLeavesItsSiblingsAlone() = runTest {
        val second = hit.copy(track = hit.track.copy(videoId = "bbbbbbbbbbb", title = "Starboy"))
        library.remember(listOf(hit, second), null)
        library.setGenre("aaaaaaaaaaa", "R&B")

        // Nothing is untagged any more, so this is a correction to one track and
        // must stay that way — the propagation only ever fills nulls.
        assertEquals(0, library.setGenre("bbbbbbbbbbb", "Synthpop"))
        assertEquals("Synthpop", db.tracks().byId("bbbbbbbbbbb")!!.genre)
        assertEquals("a deliberate tag was overwritten", "R&B", db.tracks().byId("aaaaaaaaaaa")!!.genre)
    }

    @Test
    fun clearingAGenreTagsNothingElse() = runTest {
        val second = hit.copy(track = hit.track.copy(videoId = "bbbbbbbbbbb", title = "Starboy"))
        library.remember(listOf(hit, second), null)
        assertEquals(0, library.setGenre("aaaaaaaaaaa", null))
        assertNull(db.tracks().byId("bbbbbbbbbbb")!!.genre)
    }

    // -- the cache the library shares with search --------------------------

    @Test
    fun pruningKeepsAnythingTheUserTouched() = runTest {
        val saved = hit.copy(track = hit.track.copy(videoId = "bbbbbbbbbbb"))
        val played = hit.copy(track = hit.track.copy(videoId = "ccccccccccc"))
        val liked = hit.copy(track = hit.track.copy(videoId = "ddddddddddd"))
        library.remember(listOf(hit, saved, played, liked), null)

        library.save("bbbbbbbbbbb")
        library.recordPlay("ccccccccccc")
        library.setLiked("ddddddddddd", true)

        // Everything looks old enough to age out.
        db.tracks().pruneUnreferenced(System.currentTimeMillis() + 1_000)

        assertNull("an untouched search result should age out", db.tracks().byId("aaaaaaaaaaa"))
        assertNotNull(db.tracks().byId("bbbbbbbbbbb"))
        assertNotNull(db.tracks().byId("ccccccccccc"))
        assertNotNull(db.tracks().byId("ddddddddddd"))
    }

    @Test
    fun pruningSpares_aDownloadedTrack() = runTest {
        library.remember(listOf(hit), null)
        db.tracks().setCached("aaaaaaaaaaa", "/data/audio/a.webm", 1_024)
        db.tracks().pruneUnreferenced(System.currentTimeMillis() + 1_000)
        assertNotNull(db.tracks().byId("aaaaaaaaaaa"))
    }
}
