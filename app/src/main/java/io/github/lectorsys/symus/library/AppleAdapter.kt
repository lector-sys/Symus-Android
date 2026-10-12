package io.github.lectorsys.symus.library

import android.util.Base64
import io.github.lectorsys.symus.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads an Apple Music playlist, with a token the user supplies.
 *
 * **Why the token is not in the app.** Apple Music's API authenticates with a
 * developer token: a JWT signed ES256 with a private key issued to a paid Apple
 * Developer Program membership. That private key must never leave a server the
 * developer controls — an APK is not such a place, and a key extracted from one
 * is a key that gets revoked and, until it is, used by strangers. This adapter
 * therefore **accepts a finished token and never signs one**, and
 * [rejectsPrivateKey] refuses a pasted key outright rather than helpfully
 * offering to sign with it.
 *
 * **What each token unlocks.** The developer token alone reads Apple's public
 * catalogue playlists — the `pl.` links people share. A user's *own* library
 * playlists additionally need a Music User Token, which only MusicKit can mint
 * after the user authorises it; where one is present this adapter uses it, and
 * where it is not it says so instead of failing obscurely.
 *
 * **Storefront.** Catalogue URLs are storefront-scoped. The same `pl.` id
 * resolves in one storefront and 404s in another, so it is configurable and
 * defaults to the one in the pasted link where there is one.
 */
@Singleton
class AppleAdapter @Inject constructor(
    private val client: OkHttpClient,
    private val settings: SettingsRepository
) : ProviderAdapter {

    override val provider = Provider.APPLE

    override suspend fun access(): ProviderAccess {
        val token = settings.get(DEVELOPER_TOKEN)?.takeIf { it.isNotBlank() }
            ?: return ProviderAccess.NeedsConfiguration(
                what = "an Apple Music developer token",
                how = "Generate a developer token (an ES256 JWT) from your Apple " +
                    "Developer account on a machine you control, and paste the token " +
                    "here. Never paste the private key — this app will refuse it."
            )

        val expiry = expiryOf(token)
        if (expiry != null && expiry < System.currentTimeMillis()) {
            return ProviderAccess.NeedsConfiguration(
                what = "a current developer token",
                how = "That token expired. Apple caps developer tokens at six months; " +
                    "generate a new one and paste it in."
            )
        }
        return ProviderAccess.Ready(settings.get(STOREFRONT) ?: DEFAULT_STOREFRONT)
    }

    /**
     * Stores a developer token, refusing anything that is a private key.
     *
     * A user following a half-remembered tutorial will paste the contents of
     * the `.p8` file, because both are "the Apple Music key". Accepting it would
     * put a signing key in a settings table; saying exactly what went wrong
     * costs one check.
     */
    suspend fun setDeveloperToken(token: String): Result<Unit> = runCatching {
        val text = token.trim()
        require(!rejectsPrivateKey(text)) {
            "That is a private signing key, not a developer token. Never paste a " +
                ".p8 key into an app — generate the token on a machine you control " +
                "and paste the token."
        }
        require(text.count { it == '.' } == 2) {
            "A developer token is a JWT with three dot-separated parts."
        }
        require(headerAlgorithm(text) == "ES256") {
            "Apple Music developer tokens are signed ES256. That one is not."
        }
        settings.put(DEVELOPER_TOKEN, text)
    }

    suspend fun setMusicUserToken(token: String) =
        settings.put(MUSIC_USER_TOKEN, token.trim())

    suspend fun setStorefront(storefront: String) =
        settings.put(STOREFRONT, storefront.trim().lowercase().ifEmpty { DEFAULT_STOREFRONT })

    suspend fun signOut() {
        listOf(DEVELOPER_TOKEN, MUSIC_USER_TOKEN).forEach { settings.remove(it) }
    }

    /**
     * One page of a playlist.
     *
     * A `pl.u-` id is a user's own library playlist and needs the user token; a
     * plain `pl.` is catalogue. Getting this wrong produces a 404 that reads
     * like the playlist does not exist, so it is decided up front.
     */
    override suspend fun page(sourceId: String, page: String?): Result<ProviderPlaylist> =
        withContext(Dispatchers.IO) {
            runCatching {
                val developer = settings.get(DEVELOPER_TOKEN)
                    ?: throw ProviderFailure(
                        ProviderAccess.NeedsConfiguration(
                            "an Apple Music developer token",
                            "Add one in Import settings."
                        ),
                        "No Apple Music developer token is configured."
                    )
                val userToken = settings.get(MUSIC_USER_TOKEN)?.takeIf { it.isNotBlank() }
                val storefront = settings.get(STOREFRONT) ?: DEFAULT_STOREFRONT
                val library = sourceId.startsWith("pl.u-")

                if (library && userToken == null) {
                    throw ProviderFailure(
                        ProviderAccess.NeedsSignIn(
                            "That is a personal library playlist. It needs a Music User " +
                                "Token, which MusicKit issues after you authorise it."
                        ),
                        "No Music User Token for a library playlist."
                    )
                }

                val url = page ?: if (library) {
                    "https://api.music.apple.com/v1/me/library/playlists/$sourceId/tracks?limit=100"
                } else {
                    "https://api.music.apple.com/v1/catalog/$storefront/playlists/$sourceId" +
                        "?include=tracks&limit[tracks]=100"
                }

                val json = get(url, developer, userToken)
                parsePage(json, page != null)
            }
        }

    /**
     * Turns one Apple response into a page.
     *
     * Internal so the fixture tests can drive it: Apple nests a playlist's
     * tracks two different ways depending on whether the response is the
     * playlist resource or a continuation of its relationship, and that
     * asymmetry is the most likely place for this to break.
     */
    internal fun parsePage(json: JSONObject, continuation: Boolean): ProviderPlaylist {
        val data = json.optJSONArray("data") ?: JSONArray()

        val (rows, next, title, total) = if (continuation) {
            // A relationship continuation is already the track array.
            Quad(
                data,
                json.optString("next").takeIf { it.isNotEmpty() },
                null,
                json.optInt("total", data.length())
            )
        } else {
            val playlist = data.optJSONObject(0)
            val attributes = playlist?.optJSONObject("attributes")
            val tracks = playlist?.optJSONObject("relationships")?.optJSONObject("tracks")
            Quad(
                tracks?.optJSONArray("data") ?: JSONArray(),
                tracks?.optString("next")?.takeIf { it.isNotEmpty() },
                attributes?.optString("name")?.takeIf { it.isNotEmpty() },
                tracks?.optInt("total", 0) ?: 0
            )
        }

        val parsed = buildList {
            for (index in 0 until rows.length()) {
                val attributes = rows.getJSONObject(index).optJSONObject("attributes") ?: continue
                val name = attributes.optString("name").takeIf { it.isNotEmpty() } ?: continue
                add(
                    SourceTrack(
                        position = index,
                        title = name,
                        artist = attributes.optString("artistName").takeIf { it.isNotEmpty() },
                        album = attributes.optString("albumName").takeIf { it.isNotEmpty() },
                        durationSec = attributes.optLong("durationInMillis") / 1000,
                        isrc = attributes.optString("isrc").takeIf { it.isNotEmpty() },
                        sourceTrackId = rows.getJSONObject(index)
                            .optString("id").takeIf { it.isNotEmpty() }
                    )
                )
            }
        }

        return ProviderPlaylist(
            title = title ?: "Apple Music playlist",
            tracks = parsed,
            totalTracks = maxOf(total, parsed.size),
            // Apple returns a path, not a URL.
            nextPage = next?.let {
                if (it.startsWith("http")) it else "https://api.music.apple.com$it"
            }
        )
    }

    private fun get(url: String, developer: String, userToken: String?): JSONObject {
        val request = Request.Builder().url(url)
            .header("Authorization", "Bearer $developer")
            .apply { userToken?.let { header("Music-User-Token", it) } }
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) throw appleFailureFor(response.code, text)
            return JSONObject(text)
        }
    }

    private data class Quad(
        val rows: JSONArray,
        val next: String?,
        val title: String?,
        val total: Int
    )

    companion object {
        const val DEFAULT_STOREFRONT = "us"

        private const val DEVELOPER_TOKEN = "appleDeveloperToken"
        private const val MUSIC_USER_TOKEN = "appleMusicUserToken"
        private const val STOREFRONT = "appleStorefront"

        /** A PEM private key, in any of the forms people paste it. */
        internal fun rejectsPrivateKey(text: String): Boolean =
            text.contains("BEGIN PRIVATE KEY") ||
                text.contains("BEGIN EC PRIVATE KEY") ||
                text.contains("BEGIN RSA PRIVATE KEY")

        internal fun headerAlgorithm(jwt: String): String? = runCatching {
            val header = jwt.substringBefore('.')
            val json = JSONObject(
                String(Base64.decode(header, Base64.URL_SAFE or Base64.NO_WRAP))
            )
            json.optString("alg").takeIf { it.isNotEmpty() }
        }.getOrNull()

        /** `exp` in millis, or null when the token does not carry one. */
        internal fun expiryOf(jwt: String): Long? = runCatching {
            val payload = jwt.split('.').getOrNull(1) ?: return@runCatching null
            val json = JSONObject(
                String(Base64.decode(payload, Base64.URL_SAFE or Base64.NO_WRAP))
            )
            json.optLong("exp").takeIf { it > 0 }?.times(1000)
        }.getOrNull()
    }
}

/** Apple's codes, mapped to the situations that differ. */
internal fun appleFailureFor(code: Int, body: String): ProviderFailure {
    val message = runCatching {
        JSONObject(body).optJSONArray("errors")?.optJSONObject(0)?.optString("detail")
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    return when (code) {
        401 -> ProviderFailure(
            ProviderAccess.NeedsConfiguration(
                "a valid developer token",
                "Apple rejected the token. Developer tokens expire after at most " +
                    "six months; generate a new one."
            ),
            message ?: "Apple Music rejected the developer token."
        )

        403 -> ProviderFailure(
            ProviderAccess.NeedsSignIn(
                "Apple accepted the developer token but not the user authorisation. " +
                    "A library playlist needs a current Music User Token."
            ),
            message ?: "Apple Music returned 403."
        )

        404 -> ProviderFailure(
            ProviderAccess.Failed(
                "That playlist is not in this storefront. Apple's catalogue is " +
                    "per-country, and the same link resolves in one storefront and " +
                    "not another — check the storefront setting."
            ),
            message ?: "Apple Music returned 404."
        )

        429 -> ProviderFailure(
            ProviderAccess.Failed("Apple Music is rate limiting this token."),
            message ?: "Apple Music returned 429."
        )

        else -> ProviderFailure(
            ProviderAccess.Failed(message ?: "Apple Music returned $code."),
            message ?: "Apple Music returned $code."
        )
    }
}
