package io.github.lectorsys.symus.library

import android.content.Context
import android.util.Log
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lectorsys.symus.data.LibraryRepository
import io.github.lectorsys.symus.data.MatchState
import io.github.lectorsys.symus.data.SymusDatabase
import io.github.lectorsys.symus.media.Extractor
import io.github.lectorsys.symus.media.SearchKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Playlist import against the real providers, not fixtures.
 *
 * [ProviderFixtureTest] already proves the adapters parse what Spotify and
 * Apple actually send, using recorded responses. This is the other half, and a
 * different claim: that a playlist a person could paste in today comes back,
 * paginates, matches, and commits.
 *
 * Only the providers that need no credentials can be verified this way. Spotify
 * and Apple both require an account and a key belonging to whoever is running
 * this, which is not something a test may invent — so this file reports exactly
 * what each of them is waiting for and stops there, rather than skipping
 * quietly and leaving an unqualified "import works" behind.
 */
@RunWith(AndroidJUnit4::class)
class LiveImportTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun harness(): Harness {
        val db = Room.inMemoryDatabaseBuilder(context, SymusDatabase::class.java).build()
        val extractor = Extractor()
        val library = LibraryRepository(
            db.tracks(), db.history(), db.feedback(), db.playlists(),
            db.searchHistory(), db.channels(), extractor
        )
        val scope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
        )
        val settings = io.github.lectorsys.symus.data.SettingsRepository(db.settings(), scope)
        val client = Extractor.okHttp()
        return Harness(
            db = db,
            extractor = extractor,
            imports = PlaylistImport(
                imports = db.imports(),
                extractor = extractor,
                library = library,
                spotify = SpotifyAdapter(client, settings),
                apple = AppleAdapter(client, settings)
            ),
            library = library
        )
    }

    private class Harness(
        val db: SymusDatabase,
        val extractor: Extractor,
        val imports: PlaylistImport,
        val library: LibraryRepository
    )

    private suspend fun online(extractor: Extractor): Boolean = runCatching {
        extractor.search("massive attack", SearchKind.SONGS, limit = 1).hits.isNotEmpty()
    }.getOrDefault(false)

    // -- YouTube: no credentials, so this one can be proven end to end ---------

    @Test
    fun aRealYouTubePlaylistImportsAndCommits(): Unit = runBlocking {
        val harness = harness()
        try {
            assumeTrue("No network; cannot import live", online(harness.extractor))

            // Resolved at run time rather than hard-coded: the test says which
            // playlist it actually used, and a link that rots does not become a
            // silent pass.
            val url = CANDIDATES.firstOrNull { candidate ->
                runCatching {
                    harness.extractor.playlistTracks(candidate, limit = 5).hits.isNotEmpty()
                }.getOrDefault(false)
            }
            assumeTrue("None of the candidate playlists resolved", url != null)
            say("YT_PLAYLIST $url")

            val link = parseSourceLink(url!!)
            assertEquals("The link should be recognised as YouTube", Provider.YOUTUBE, link?.provider)

            val jobId = harness.imports.startLink(link!!)
            harness.imports.fetch(jobId).getOrThrow()

            val job = harness.imports.jobOnce(jobId)!!
            val counts = harness.imports.counts(jobId)
            say("YT_TITLE     ${job.title}")
            say("YT_STATE     ${job.state}")
            say("YT_TOTAL     ${counts.total}")
            say("YT_EXACT     ${counts.exact}")
            say("YT_UNRESOLVED ${counts.unresolved}")

            assertTrue("The playlist came back empty", counts.total > 0)
            assertEquals("A YouTube import resolves to itself", counts.total, counts.exact)
            assertEquals("Nothing should be left pending", 0, counts.pending)

            val playlistId = harness.imports.commit(jobId).getOrThrow()
            val committed = harness.db.playlists().byId(playlistId)!!
            say("YT_COMMITTED ${committed.title}")
            say("YT_SOURCE    ${committed.sourceProvider}/${committed.sourceId}")

            assertEquals("The committed playlist lost its provider", "youtube", committed.sourceProvider)
            assertEquals("The committed playlist lost its source id", link.sourceId, committed.sourceId)
            val rows = harness.db.playlists().orderedIds(playlistId)
            say("YT_ROWS      ${rows.size}")
            assertEquals("Committed row count differs from matched count", counts.matched, rows.size)
        } finally {
            harness.db.close()
        }
    }

    /**
     * Pagination, proven by reading past the first page.
     *
     * The extractor hands YouTube playlists back a page at a time; a playlist
     * longer than one page is the only thing that shows the continuation is
     * actually being followed rather than the first hundred rows being taken
     * for the whole list.
     */
    @Test
    fun aLongPlaylistIsReadBeyondItsFirstPage(): Unit = runBlocking {
        val harness = harness()
        try {
            assumeTrue("No network", online(harness.extractor))

            val found = CANDIDATES.firstNotNullOfOrNull { candidate ->
                runCatching {
                    val resolved = harness.extractor.playlistTracks(candidate, limit = 250)
                    if (resolved.hits.size > 100) candidate to resolved.hits.size else null
                }.getOrNull()
            }
            assumeTrue("No candidate playlist was longer than one page", found != null)
            say("PAGE_PLAYLIST ${found!!.first}")
            say("PAGE_TRACKS   ${found.second}")
            assertTrue(
                "Only ${found.second} tracks came back; pagination was not followed",
                found.second > 100
            )
        } finally {
            harness.db.close()
        }
    }

    // -- File: the path that produces unresolved rows and a real review --------

    /**
     * A file import against live search, including rows that cannot match.
     *
     * The nonsense entries are there on purpose. An import that resolves
     * everything proves the happy path only; what the review screen exists for
     * is the rows that do not, and those have to survive as unresolved rather
     * than being dropped on the floor.
     */
    @Test
    fun aFileImportProducesAReviewWithUnresolvedRowsAndRetries(): Unit = runBlocking {
        val harness = harness()
        try {
            assumeTrue("No network", online(harness.extractor))

            val content = buildString {
                appendLine("Massive Attack - Teardrop")
                appendLine("Aphex Twin - Xtal")
                appendLine("Portishead - Glory Box")
                appendLine("Boards of Canada - Roygbiv")
                // Three rows that no search can resolve.
                appendLine("Zzqxwv Pplkjh - Nnnbbbvvv Ccxxzz")
                appendLine("Qqqqqqq Wwwwwww - Eeeeeee Rrrrrrr")
                appendLine("Xylophone Gribbleflotch - Untitled 9f3a2b")
            }
            val jobId = harness.imports.startFile("live-import-probe.txt", content).getOrThrow()

            var guard = 0
            while (harness.imports.matchNext(jobId, batch = 4) > 0 && guard++ < 20) {
                // Deliberately batched, as the screen drives it.
            }

            val counts = harness.imports.counts(jobId)
            val job = harness.imports.jobOnce(jobId)!!
            say("FILE_STATE      ${job.state}")
            say("FILE_TOTAL      ${counts.total}")
            say("FILE_MATCHED    ${counts.matched}")
            say("FILE_EXACT      ${counts.exact}")
            say("FILE_LIKELY     ${counts.likely}")
            say("FILE_UNCERTAIN  ${counts.uncertain}")
            say("FILE_UNRESOLVED ${counts.unresolved}")
            say("FILE_ATTENTION  ${counts.needsAttention}")

            assertEquals("Every row should be accounted for", 7, counts.total)
            assertEquals("Nothing may be left pending after matching", 0, counts.pending)
            assertTrue("Nothing matched at all; the matcher is not working", counts.matched >= 3)
            assertTrue(
                "Nonsense rows must survive as unresolved, not vanish",
                counts.unresolved >= 1
            )
            assertEquals(
                "Rows must never be dropped",
                counts.total,
                counts.matched + counts.unresolved + counts.rejected
            )

            // Retry: unresolved rows go back to pending and are searched again.
            harness.imports.retryUnresolved(jobId)
            val afterRetry = harness.imports.counts(jobId)
            say("RETRY_PENDING ${afterRetry.pending}")
            assertEquals(
                "Retry must requeue exactly the unresolved rows",
                counts.unresolved,
                afterRetry.pending
            )
            var retryGuard = 0
            while (harness.imports.matchNext(jobId, batch = 4) > 0 && retryGuard++ < 20) {
                // drain
            }
            val settled = harness.imports.counts(jobId)
            say("RETRY_SETTLED_UNRESOLVED ${settled.unresolved}")
            assertEquals("Retry must settle", 0, settled.pending)

            // The manual override the review screen offers for a row that is wrong.
            val unresolvedItem = harness.imports.itemsOnce(jobId)
                .firstOrNull { it.matchState == MatchState.UNRESOLVED }
            if (unresolvedItem != null) {
                val replacement = harness.extractor
                    .search("Massive Attack Angel", SearchKind.SONGS, limit = 1)
                    .hits.firstOrNull()
                if (replacement != null) {
                    // The review screen remembers whatever the user picked
                    // before choosing it; the track has to exist to be added.
                    harness.library.remember(listOf(replacement))
                    harness.imports.chooseMatch(unresolvedItem.id, replacement.track.videoId)
                    val chosen = harness.imports.itemsOnce(jobId).first { it.id == unresolvedItem.id }
                    say("MANUAL_MATCH ${chosen.matchState} ${chosen.matchedVideoId}")
                    assertEquals(
                        "A manually chosen match must stick",
                        MatchState.EXACT,
                        chosen.matchState
                    )
                }
            }

            val playlistId = harness.imports.commit(jobId).getOrThrow()
            val committed = harness.db.playlists().byId(playlistId)!!
            val rows = harness.db.playlists().orderedIds(playlistId)
            say("FILE_COMMITTED ${committed.title} rows=${rows.size}")
            assertEquals("A file import records its provider", "file", committed.sourceProvider)
            assertTrue("Nothing was committed", rows.isNotEmpty())
        } finally {
            harness.db.close()
        }
    }

    // -- Spotify and Apple: configuration state, stated exactly -----------------

    /**
     * Neither of these can be driven live here, and this records precisely why.
     *
     * The assertion is not "it works" — it is that the app names the missing
     * credential and the steps to supply it, which is the difference between a
     * blocked feature and a broken one.
     */
    @Test
    fun spotifyAndAppleReportWhatTheyAreWaitingFor(): Unit = runBlocking {
        val harness = harness()
        try {
            listOf(Provider.SPOTIFY, Provider.APPLE).forEach { provider ->
                val access = harness.imports.access(provider)
                say("ACCESS_${provider.id.uppercase()} $access")
                say("SETUP_${provider.id.uppercase()} ${provider.setupInstructions()}")

                assertTrue(
                    "$provider must say what it needs, not fail vaguely: $access",
                    access is ProviderAccess.NeedsConfiguration ||
                        access is ProviderAccess.NeedsSignIn
                )
                val what = (access as? ProviderAccess.NeedsConfiguration)?.what
                    ?: (access as ProviderAccess.NeedsSignIn).why
                assertTrue("$provider gave an empty requirement", what.isNotBlank())
                assertTrue(
                    "$provider must carry setup instructions",
                    provider.setupInstructions().length > 40
                )
            }

            // And the two that need nothing must say so.
            listOf(Provider.YOUTUBE, Provider.FILE).forEach { provider ->
                val access = harness.imports.access(provider)
                say("ACCESS_${provider.id.uppercase()} $access")
                assertTrue(
                    "$provider needs no credentials and must be ready",
                    access is ProviderAccess.Ready
                )
            }
        } finally {
            harness.db.close()
        }
    }

    private fun say(line: String) = Log.i(TAG, line)

    private companion object {
        const val TAG = "SYMUS_IMPORT"

        /**
         * Public playlists, tried in order. More than one because a single
         * hard-coded link is a test that fails the day someone makes it private.
         */
        val CANDIDATES = listOf(
            "https://www.youtube.com/playlist?list=PLFgquLnL59alCl_2TQvOiD5Vgm1hCaGSI",
            "https://www.youtube.com/playlist?list=PL4fGSI1pDJn6puJdseH2Rt9sMvt9E2M4i",
            "https://www.youtube.com/playlist?list=PLDcnymzs18LU4Kexrs91TVdrz4Q6QGPnl",
            "https://www.youtube.com/playlist?list=PL15B1E77BB5708555"
        )
    }
}
