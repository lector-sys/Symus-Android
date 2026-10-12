package io.github.lectorsys.symus.library

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The provider adapters, driven by recorded response shapes rather than by a
 * live account.
 *
 * **What this proves and what it does not.** It proves the parsing, the
 * pagination, the ordering, the ISRC carry-through and the error mapping — all
 * of which are the parts that silently corrupt an import when they are wrong.
 * It does not prove that a real Spotify or Apple account can be read, because
 * no credentials exist here; that remains unverified and is reported as such.
 *
 * The fixtures are the documented response shapes, trimmed to the fields the
 * adapters read.
 */
@RunWith(AndroidJUnit4::class)
class ProviderFixtureTest {

    // -- Spotify error mapping ----------------------------------------------

    @Test
    fun spotifyDistinguishesAnExpiredSignInFromAnAppRestriction() {
        val unauthorised = failureFor(
            401,
            """{"error":{"status":401,"message":"Missing/invalid/expired access token"}}"""
        )
        assertTrue(unauthorised.access is ProviderAccess.NeedsSignIn)

        val forbidden = failureFor(403, """{"error":{"status":403,"message":"Forbidden"}}""")
        val restricted = forbidden.access as ProviderAccess.Restricted
        // The remedy has to name the actual cause, which is almost always a
        // development-mode app that has not had the account added to it.
        assertTrue(restricted.remedy.contains("development mode"))
        assertTrue(restricted.remedy.contains("Users and Access"))
    }

    /**
     * Spotify answers 404 for a private playlist the signed-in user does not
     * own, which is indistinguishable from a deleted one. Saying only "not
     * found" sends people looking for a playlist that is right there in their
     * app.
     */
    @Test
    fun spotifyExplainsThatAPrivatePlaylistLooksLikeAMissingOne() {
        val failure = failureFor(404, """{"error":{"status":404,"message":"Not found."}}""")
        val state = failure.access as ProviderAccess.Failed
        assertTrue(state.message.contains("private"))
    }

    @Test
    fun spotifyRateLimitingIsNotReportedAsAnAuthProblem() {
        val failure = failureFor(429, "")
        assertTrue(failure.access is ProviderAccess.Failed)
        assertTrue((failure.access as ProviderAccess.Failed).message.contains("rate limit"))
    }

    // -- Apple parsing ------------------------------------------------------

    private val adapter = AppleAdapter(
        io.github.lectorsys.symus.media.Extractor.okHttp(),
        // Not used by parsePage; the parser is deliberately free of I/O.
        NoSettings.repository()
    )

    /** The first response: a playlist resource with its tracks nested inside. */
    private val applyFirstPage = """
        {
          "data": [{
            "id": "pl.abc",
            "attributes": { "name": "Late Night" },
            "relationships": {
              "tracks": {
                "data": [
                  { "id": "1", "attributes": {
                      "name": "Teardrop", "artistName": "Massive Attack",
                      "albumName": "Mezzanine", "durationInMillis": 330000,
                      "isrc": "GBAAA9800123" } },
                  { "id": "2", "attributes": {
                      "name": "Angel", "artistName": "Massive Attack",
                      "durationInMillis": 380000 } }
                ],
                "next": "/v1/catalog/us/playlists/pl.abc/tracks?offset=2",
                "total": 5
              }
            }
          }]
        }
    """.trimIndent()

    /** A continuation: the track array at the top level, shaped differently. */
    private val appleSecondPage = """
        {
          "data": [
            { "id": "3", "attributes": {
                "name": "Inertia Creeps", "artistName": "Massive Attack",
                "durationInMillis": 350000 } }
          ],
          "total": 5
        }
    """.trimIndent()

    @Test
    fun appleReadsAPlaylistAndItsTracksFromTheFirstResponse() {
        val page = adapter.parsePage(JSONObject(applyFirstPage), continuation = false)

        assertEquals("Late Night", page.title)
        assertEquals(5, page.totalTracks)
        assertEquals(2, page.tracks.size)
        assertEquals("Teardrop", page.tracks[0].title)
        assertEquals("Massive Attack", page.tracks[0].artist)
        assertEquals("Mezzanine", page.tracks[0].album)
        assertEquals(330L, page.tracks[0].durationSec)
        // The ISRC is the only identifier that makes an exact match possible,
        // so losing it downgrades every row to a title guess.
        assertEquals("GBAAA9800123", page.tracks[0].isrc)
        assertNull(page.tracks[1].album)
    }

    /** Apple returns a path; a relative URL would not be fetchable. */
    @Test
    fun applesNextPageIsMadeAbsolute() {
        val page = adapter.parsePage(JSONObject(applyFirstPage), continuation = false)
        assertEquals(
            "https://api.music.apple.com/v1/catalog/us/playlists/pl.abc/tracks?offset=2",
            page.nextPage
        )
    }

    /**
     * The second page is shaped differently from the first. Reading it with the
     * first page's parser yields zero tracks and a silently truncated playlist.
     */
    @Test
    fun appleReadsTheDifferentlyShapedContinuationPage() {
        val page = adapter.parsePage(JSONObject(appleSecondPage), continuation = true)
        assertEquals(1, page.tracks.size)
        assertEquals("Inertia Creeps", page.tracks[0].title)
        assertNull("A continuation has no further page here", page.nextPage)
    }

    @Test
    fun appleSkipsRowsWithNoTitleRatherThanImportingBlanks() {
        val withHole = """
            {"data":[{"id":"1","attributes":{"artistName":"Nobody"}},
                     {"id":"2","attributes":{"name":"Real","artistName":"Someone"}}]}
        """.trimIndent()
        val page = adapter.parsePage(JSONObject(withHole), continuation = true)
        assertEquals(1, page.tracks.size)
        assertEquals("Real", page.tracks[0].title)
    }

    // -- Apple token handling -----------------------------------------------

    /**
     * The check that matters most in this file. A user following a tutorial
     * will paste the .p8 key, and an app that accepts it has put a signing key
     * in a settings table.
     */
    @Test
    fun aPrivateSigningKeyIsRefusedOutright() {
        val key = "-----BEGIN PRIVATE KEY-----\nMIGTAgEAMBMGByqGSM49\n-----END PRIVATE KEY-----"
        assertTrue(AppleAdapter.rejectsPrivateKey(key))
        assertTrue(AppleAdapter.rejectsPrivateKey("-----BEGIN EC PRIVATE KEY-----"))
        assertFalse(AppleAdapter.rejectsPrivateKey("eyJhbGciOiJFUzI1NiJ9.eyJleHAiOjF9.sig"))
    }

    @Test
    fun aDeveloperTokensAlgorithmAndExpiryAreReadWithoutVerifyingIt() {
        // {"alg":"ES256","kid":"ABC"} . {"iss":"TEAM","exp":2000000000} . signature
        val jwt = "eyJhbGciOiJFUzI1NiIsImtpZCI6IkFCQyJ9" +
            ".eyJpc3MiOiJURUFNIiwiZXhwIjoyMDAwMDAwMDAwfQ" +
            ".c2lnbmF0dXJl"
        assertEquals("ES256", AppleAdapter.headerAlgorithm(jwt))
        assertEquals(2_000_000_000_000L, AppleAdapter.expiryOf(jwt))
    }

    @Test
    fun appleErrorsNameTheStorefrontWhenAPlaylistIsNotFound() {
        val failure = appleFailureFor(404, """{"errors":[{"detail":"Resource not found"}]}""")
        val state = failure.access as ProviderAccess.Failed
        assertTrue(state.message.contains("storefront"))
    }

    @Test
    fun anExpiredDeveloperTokenIsConfigurationNotASignInProblem() {
        val failure = appleFailureFor(401, "")
        assertTrue(failure.access is ProviderAccess.NeedsConfiguration)
    }

    @Test
    fun aMissingUserTokenIsASignInProblemNotAConfigurationOne() {
        val failure = appleFailureFor(403, "")
        assertTrue(failure.access is ProviderAccess.NeedsSignIn)
    }

    // -- links and files ----------------------------------------------------

    @Test
    fun providerLinksAreStillRecognised() {
        val spotify = parseSourceLink("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M")
        assertEquals(Provider.SPOTIFY, spotify?.provider)
        assertEquals("37i9dQZF1DXcBWIGoYBM5M", spotify?.sourceId)

        val apple = parseSourceLink("https://music.apple.com/us/playlist/late-night/pl.u-abc123")
        assertEquals(Provider.APPLE, apple?.provider)
        assertEquals("pl.u-abc123", apple?.sourceId)

        val youtube = parseSourceLink("https://music.youtube.com/playlist?list=OLAK5uy_x1")
        assertEquals(Provider.YOUTUBE, youtube?.provider)
    }

    @Test
    fun everyProviderStatesWhatItNeeds() {
        Provider.entries.forEach { provider ->
            assertTrue(
                "${provider.label} has no setup instructions",
                provider.setupInstructions().isNotBlank()
            )
        }
        assertTrue(Provider.SPOTIFY.setupInstructions().contains("symus://spotify-callback"))
        assertTrue(Provider.APPLE.setupInstructions().contains("never the .p8"))
        assertFalse(Provider.YOUTUBE.needsCredentials)
        assertFalse(Provider.FILE.needsCredentials)
    }

    // -- file formats -------------------------------------------------------

    @Test
    fun m3uAndM3u8FilesAreRead() {
        val m3u = """
            #EXTM3U
            #EXTINF:330,Massive Attack - Teardrop
            /music/teardrop.mp3
            #EXTINF:380,Massive Attack - Angel
            /music/angel.mp3
        """.trimIndent()

        listOf("list.m3u", "list.m3u8").forEach { name ->
            val tracks = parsePlaylistFile(name, m3u)
            assertEquals("$name did not parse", 2, tracks.size)
            assertEquals("Teardrop", tracks[0].title)
            assertEquals("Massive Attack", tracks[0].artist)
            assertEquals(330L, tracks[0].durationSec)
            assertEquals(1, tracks[1].position)
        }
    }

    /** An extension-less file that starts with #EXTM3U is still an M3U. */
    @Test
    fun anM3uIsRecognisedByItsHeaderWhenTheNameIsUnhelpful() {
        val tracks = parsePlaylistFile(
            "playlist",
            "#EXTM3U\n#EXTINF:120,Aphex Twin - Xtal\nxtal.flac"
        )
        assertEquals(1, tracks.size)
        assertEquals("Xtal", tracks[0].title)
    }

    /** A URL-only M3U has no metadata at all; the filename is all there is. */
    @Test
    fun anM3uWithNoExtinfFallsBackToTheFileName() {
        val tracks = parsePlaylistFile("bare.m3u", "#EXTM3U\n/music/Burial - Archangel.mp3")
        assertEquals(1, tracks.size)
        assertEquals("Archangel.mp3", tracks[0].title)
        assertEquals("Burial", tracks[0].artist)
    }

    @Test
    fun csvExportsAreStillRead() {
        val csv = """
            Track Name,Artist Name(s),Album Name,Duration (ms),ISRC
            "Hello, Goodbye",The Beatles,Magical Mystery Tour,207000,GBAYE6800075
        """.trimIndent()
        val tracks = parsePlaylistFile("export.csv", csv)
        assertEquals(1, tracks.size)
        // The quoted comma is the whole reason the CSV splitter exists.
        assertEquals("Hello, Goodbye", tracks[0].title)
        assertEquals("The Beatles", tracks[0].artist)
        assertEquals(207L, tracks[0].durationSec)
        assertEquals("GBAYE6800075", tracks[0].isrc)
    }

    @Test
    fun aPlainListIsRead() {
        val tracks = parsePlaylistFile("list.txt", "Burial - Archangel\nAphex Twin - Xtal")
        assertEquals(2, tracks.size)
        assertEquals("Archangel", tracks[0].title)
        assertNotNull(tracks[1].artist)
    }
}

/**
 * A settings repository that is never read, for the parser tests.
 *
 * `parsePage` is pure by design so it can be tested without credentials; this
 * exists only to satisfy the constructor and would throw if anything in the
 * parsing path ever started reaching for storage.
 */
private object NoSettings {
    fun repository(): io.github.lectorsys.symus.data.SettingsRepository {
        val context = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.content.Context>()
        val db = androidx.room.Room.inMemoryDatabaseBuilder(
            context,
            io.github.lectorsys.symus.data.SymusDatabase::class.java
        ).build()
        return io.github.lectorsys.symus.data.SettingsRepository(
            db.settings(),
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
        )
    }
}
