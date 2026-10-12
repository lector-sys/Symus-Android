package io.github.lectorsys.symus.library

/**
 * A playlist as a provider hands it over, before anything has been matched.
 *
 * [nextPage] is an opaque continuation the adapter understands and nobody else
 * needs to. Both providers paginate, both cap a page at 100, and a 900-track
 * playlist that silently imports its first hundred is the failure this exists
 * to prevent.
 */
data class ProviderPlaylist(
    val title: String,
    val owner: String? = null,
    val artworkUrl: String? = null,
    val tracks: List<SourceTrack>,
    val totalTracks: Int = tracks.size,
    val nextPage: String? = null
)

/**
 * Exactly where a provider stands, so the screen can say something true.
 *
 * "Unsupported" would collapse five different situations into one unhelpful
 * word. A user whose token expired, a user who never configured anything, and
 * a user whose Spotify app is still in development mode all need different
 * sentences, and only one of them is a dead end.
 */
sealed interface ProviderAccess {
    /** Configured and usable right now. */
    data class Ready(val account: String? = null) : ProviderAccess

    /** The adapter works; the user has not supplied what it needs. */
    data class NeedsConfiguration(val what: String, val how: String) : ProviderAccess

    /** Configured, but the user has not signed in or the sign-in lapsed. */
    data class NeedsSignIn(val why: String) : ProviderAccess

    /**
     * The provider itself refused. Distinct from the above because no amount of
     * configuring fixes it — a Spotify app in development mode cannot read a
     * playlist for a user it has not been given access to.
     */
    data class Restricted(val reason: String, val remedy: String) : ProviderAccess

    data class Failed(val message: String) : ProviderAccess
}

/**
 * What an adapter can do, independent of whether it is currently allowed to.
 *
 * Kept as an interface with two real implementations rather than a `when` over
 * the provider enum so the fixture tests can drive the parsing and pagination
 * without a network, which is the only part that can be verified here.
 */
interface ProviderAdapter {
    val provider: Provider

    /** Configuration state, read from stored credentials. Never a network call. */
    suspend fun access(): ProviderAccess

    /**
     * One page of a playlist. [page] is null for the first and otherwise
     * whatever the previous [ProviderPlaylist.nextPage] said.
     */
    suspend fun page(sourceId: String, page: String? = null): Result<ProviderPlaylist>
}

/**
 * Errors worth telling apart, because each has a different thing to do next.
 */
class ProviderFailure(
    val access: ProviderAccess,
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)
