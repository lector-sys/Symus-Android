package io.github.lectorsys.symus.library

import android.util.Base64
import io.github.lectorsys.symus.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads a Spotify playlist, using the flow Spotify itself recommends for apps
 * that cannot keep a secret.
 *
 * **On the previous position.** This app used to say Spotify was unreachable
 * because its API "needs a client secret a distributed app cannot keep". That
 * is out of date: Authorization Code with PKCE exists precisely for mobile
 * apps, uses no secret, and is what Spotify's own documentation recommends for
 * this case. The correct obstacle is different and smaller — an app must be
 * *registered*, and only the person who registers it can do that.
 *
 * **So the client id comes from the user.** SYmuS has no server and no
 * organisation behind it to hold a registration, and shipping one developer's
 * client id would make every user's imports count against that one app's quota
 * and break the day it is revoked. Instead the user creates an app on Spotify's
 * dashboard in about a minute and pastes the id in. A client id is not a
 * secret — it is published in every redirect — so storing it on device costs
 * nothing.
 *
 * **What this deliberately does not do.** It does not scrape open.spotify.com,
 * it does not read a playlist the signed-in user has no access to, and it does
 * not invent credentials. A playlist that is private and not theirs comes back
 * as a 404 from Spotify, and that is reported as what it is.
 */
@Singleton
class SpotifyAdapter @Inject constructor(
    private val client: OkHttpClient,
    private val settings: SettingsRepository
) : ProviderAdapter {

    override val provider = Provider.SPOTIFY

    override suspend fun access(): ProviderAccess {
        val clientId = settings.get(CLIENT_ID)?.takeIf { it.isNotBlank() }
            ?: return ProviderAccess.NeedsConfiguration(
                what = "a Spotify client id",
                how = "Create an app at developer.spotify.com/dashboard, add " +
                    "$REDIRECT as a redirect URI, and paste the client id here."
            )
        val refresh = settings.get(REFRESH_TOKEN)?.takeIf { it.isNotBlank() }
            ?: return ProviderAccess.NeedsSignIn(
                "Client id $clientId is set. Sign in to Spotify to read your playlists."
            )
        return ProviderAccess.Ready(settings.get(ACCOUNT))
    }

    // -- PKCE ----------------------------------------------------------------

    /**
     * Makes a verifier and returns the URL to send the user to.
     *
     * The verifier is stored rather than returned because the callback arrives
     * on a different screen, possibly after the process died, and a verifier
     * held in memory would be gone exactly when it is needed.
     */
    suspend fun beginSignIn(): Result<String> = runCatching {
        val clientId = settings.get(CLIENT_ID)?.takeIf { it.isNotBlank() }
            ?: error("No Spotify client id is configured.")
        val verifier = randomVerifier()
        settings.put(VERIFIER, verifier)

        val challenge = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )

        "https://accounts.spotify.com/authorize" +
            "?client_id=$clientId" +
            "&response_type=code" +
            "&redirect_uri=${encode(REDIRECT)}" +
            "&code_challenge_method=S256" +
            "&code_challenge=$challenge" +
            // Only what reading a playlist needs. playlist-read-private covers
            // the user's own private lists; playlist-read-collaborative covers
            // ones shared with them. Neither grants any write.
            "&scope=${encode("playlist-read-private playlist-read-collaborative")}"
    }

    /** Exchanges the callback code for tokens. */
    suspend fun completeSignIn(code: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val clientId = settings.get(CLIENT_ID).orEmpty()
            val verifier = settings.get(VERIFIER)
                ?: error("That sign-in did not start here. Try again.")

            val body = FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("code", code)
                .add("redirect_uri", REDIRECT)
                .add("client_id", clientId)
                .add("code_verifier", verifier)
                .build()

            val json = post("https://accounts.spotify.com/api/token", body)
            settings.put(ACCESS_TOKEN, json.getString("access_token"))
            json.optString("refresh_token").takeIf { it.isNotEmpty() }
                ?.let { settings.put(REFRESH_TOKEN, it) }
            settings.put(
                EXPIRES_AT,
                (System.currentTimeMillis() + json.optLong("expires_in", 3600) * 1000).toString()
            )
            settings.remove(VERIFIER)
            runCatching { settings.put(ACCOUNT, me()) }
            Unit
        }
    }

    suspend fun signOut() {
        listOf(ACCESS_TOKEN, REFRESH_TOKEN, EXPIRES_AT, VERIFIER, ACCOUNT).forEach {
            settings.remove(it)
        }
    }

    suspend fun setClientId(clientId: String) = settings.put(CLIENT_ID, clientId.trim())

    // -- reading -------------------------------------------------------------

    /**
     * One page of a playlist's tracks.
     *
     * `fields` is not an optimisation for its own sake: the untrimmed response
     * for a hundred tracks is megabytes of artwork URLs and market lists, and
     * this runs on a phone connection.
     */
    override suspend fun page(sourceId: String, page: String?): Result<ProviderPlaylist> =
        withContext(Dispatchers.IO) {
            runCatching {
                val token = accessToken()
                val title = if (page == null) playlistName(sourceId, token) else null

                val url = page ?: "https://api.spotify.com/v1/playlists/$sourceId/tracks" +
                    "?limit=100&offset=0&fields=" + encode(
                        "next,total,items(track(name,duration_ms,external_ids(isrc)," +
                            "id,album(name),artists(name)))"
                    )

                val json = get(url, token)
                val items = json.optJSONArray("items") ?: org.json.JSONArray()
                val offset = offsetOf(page)

                val tracks = buildList {
                    for (index in 0 until items.length()) {
                        val track = items.getJSONObject(index).optJSONObject("track")
                        // Local files and removed tracks come back as null, and
                        // dropping the row silently would shift every position
                        // after it. Kept as an unmatchable title instead.
                        val name = track?.optString("name").orEmpty()
                        if (name.isEmpty()) continue
                        add(
                            SourceTrack(
                                position = offset + index,
                                title = name,
                                artist = track.optJSONArray("artists")
                                    ?.let { artists ->
                                        (0 until artists.length())
                                            .map { artists.getJSONObject(it).optString("name") }
                                            .filter { it.isNotEmpty() }
                                            .joinToString(", ")
                                    }?.takeIf { it.isNotEmpty() },
                                album = track.optJSONObject("album")
                                    ?.optString("name")?.takeIf { it.isNotEmpty() },
                                durationSec = track.optLong("duration_ms") / 1000,
                                isrc = track.optJSONObject("external_ids")
                                    ?.optString("isrc")?.takeIf { it.isNotEmpty() },
                                sourceTrackId = track.optString("id").takeIf { it.isNotEmpty() }
                            )
                        )
                    }
                }

                ProviderPlaylist(
                    title = title ?: "Spotify playlist",
                    tracks = tracks,
                    totalTracks = json.optInt("total", tracks.size),
                    nextPage = json.optString("next").takeIf { it.isNotEmpty() && it != "null" }
                )
            }
        }

    private suspend fun playlistName(sourceId: String, token: String): String {
        val json = get(
            "https://api.spotify.com/v1/playlists/$sourceId?fields=" +
                encode("name,owner(display_name)"),
            token
        )
        return json.optString("name").ifBlank { "Spotify playlist" }
    }

    private suspend fun me(): String {
        val json = get("https://api.spotify.com/v1/me", accessToken())
        return json.optString("display_name").ifBlank { json.optString("id") }
    }

    /** A live token, refreshing it first if it is expired or nearly so. */
    private suspend fun accessToken(): String {
        val expires = settings.get(EXPIRES_AT)?.toLongOrNull() ?: 0L
        val current = settings.get(ACCESS_TOKEN)
        if (current != null && System.currentTimeMillis() < expires - 60_000) return current

        val refresh = settings.get(REFRESH_TOKEN)
            ?: throw ProviderFailure(
                ProviderAccess.NeedsSignIn("Sign in to Spotify to read your playlists."),
                "Not signed in to Spotify."
            )
        val body = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refresh)
            .add("client_id", settings.get(CLIENT_ID).orEmpty())
            .build()
        val json = post("https://accounts.spotify.com/api/token", body)
        val token = json.getString("access_token")
        settings.put(ACCESS_TOKEN, token)
        // Spotify rotates refresh tokens; keeping the old one would work until
        // it abruptly did not.
        json.optString("refresh_token").takeIf { it.isNotEmpty() }
            ?.let { settings.put(REFRESH_TOKEN, it) }
        settings.put(
            EXPIRES_AT,
            (System.currentTimeMillis() + json.optLong("expires_in", 3600) * 1000).toString()
        )
        return token
    }

    private fun get(url: String, token: String): JSONObject {
        val request = Request.Builder().url(url)
            .header("Authorization", "Bearer $token")
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) throw failureFor(response.code, text)
            return JSONObject(text)
        }
    }

    private fun post(url: String, body: FormBody): JSONObject {
        client.newCall(Request.Builder().url(url).post(body).build()).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) throw failureFor(response.code, text)
            return JSONObject(text)
        }
    }

    private companion object {
        const val REDIRECT = "symus://spotify-callback"

        const val CLIENT_ID = "spotifyClientId"
        const val ACCESS_TOKEN = "spotifyAccessToken"
        const val REFRESH_TOKEN = "spotifyRefreshToken"
        const val EXPIRES_AT = "spotifyExpiresAt"
        const val VERIFIER = "spotifyVerifier"
        const val ACCOUNT = "spotifyAccount"

        fun randomVerifier(): String {
            val bytes = ByteArray(64)
            SecureRandom().nextBytes(bytes)
            return Base64.encodeToString(
                bytes,
                Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
            )
        }

        fun encode(text: String): String = java.net.URLEncoder.encode(text, "UTF-8")

        fun offsetOf(page: String?): Int =
            page?.let { Regex("offset=(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: 0
    }
}

/**
 * Turns Spotify's HTTP codes into the four situations that actually differ.
 *
 * Internal rather than private so the fixture tests can assert the mapping
 * without a network: this is the part most likely to be wrong, and the part a
 * user is most likely to hit.
 */
internal fun failureFor(code: Int, body: String): ProviderFailure {
    val message = runCatching {
        JSONObject(body).optJSONObject("error")?.optString("message")
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    return when (code) {
        401 -> ProviderFailure(
            ProviderAccess.NeedsSignIn("That sign-in has expired. Sign in again."),
            message ?: "Spotify rejected the access token."
        )

        403 -> ProviderFailure(
            ProviderAccess.Restricted(
                reason = "Spotify refused this request for your app.",
                remedy = "A Spotify app in development mode can only read data for " +
                    "users added to it. Add your account under Users and Access on the " +
                    "app's dashboard page, or request extended quota."
            ),
            message ?: "Spotify returned 403."
        )

        404 -> ProviderFailure(
            ProviderAccess.Failed(
                "That playlist is not visible to the signed-in account. Spotify " +
                    "answers 404 for private playlists you do not own, as well as for " +
                    "ones that no longer exist."
            ),
            message ?: "Spotify returned 404."
        )

        429 -> ProviderFailure(
            ProviderAccess.Failed("Spotify is rate limiting this app. Try again shortly."),
            message ?: "Spotify returned 429."
        )

        else -> ProviderFailure(
            ProviderAccess.Failed(message ?: "Spotify returned $code."),
            message ?: "Spotify returned $code."
        )
    }
}
