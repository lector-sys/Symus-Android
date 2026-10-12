package io.github.lectorsys.symus.media

import io.github.lectorsys.symus.data.TrackEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request as OkRequest
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.AccountTerminatedException
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ContentNotSupportedException
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException
import org.schabi.newpipe.extractor.exceptions.PaidContentException
import org.schabi.newpipe.extractor.exceptions.PrivateContentException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.exceptions.SoundCloudGoPlusContentException
import org.schabi.newpipe.extractor.exceptions.YoutubeMusicPremiumContentException
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The extractor's HTTP transport.
 *
 * NewPipe hands us a Request and expects a Response; everything else — retries,
 * connection reuse, timeouts — is ours. A 429 has to come back as ReCaptchaException
 * or the extractor cannot tell rate limiting apart from a broken page, and the
 * user gets "something went wrong" for a condition that clears on its own.
 */
class OkHttpDownloader(private val client: OkHttpClient) : Downloader() {

    override fun execute(request: Request): Response {
        val body = request.dataToSend()?.toRequestBody()
        val builder = OkRequest.Builder().method(request.httpMethod(), body).url(request.url())

        for ((name, values) in request.headers()) {
            builder.removeHeader(name)
            values.forEach { builder.addHeader(name, it) }
        }

        client.newCall(builder.build()).execute().use { response ->
            if (response.code == 429) {
                throw ReCaptchaException("reCaptcha challenge requested", request.url())
            }
            return Response(
                response.code,
                response.message,
                response.headers.toMultimap(),
                response.body.string(),
                response.request.url.toString()
            )
        }
    }
}

/**
 * What the app sees of a search result.
 *
 * The channel id is kept even though a track has nowhere to put it: following a
 * channel needs a durable handle, and names are neither unique nor stable.
 */
data class SearchHit(
    val track: TrackEntity,
    val channelId: String?,
    val channelName: String?
)

/** One page of results, plus the cursor for the next one. */
data class SearchPage(
    val hits: List<SearchHit>,
    val nextPage: Page? = null
) {
    val hasMore: Boolean get() = nextPage != null
}

/** A release found through YouTube Music's album search. */
data class AlbumHit(
    val url: String,
    val title: String,
    val artist: String?,
    val thumbnailUrl: String?,
    val trackCount: Long
)

/** What a pasted link turned out to be. `name` is set only for a playlist. */
data class ResolvedLink(val name: String?, val hits: List<SearchHit>)

/** Which content filter a search should use. */
enum class SearchKind { SONGS, VIDEOS, ALBUMS }

/** Preferred audio stream when a video offers several. */
enum class AudioQuality { HIGH, DATA_SAVER }

/**
 * On-device YouTube extraction.
 *
 * This is the whole reason the app needs no server, and the single dependency
 * most likely to break: when YouTube changes, extraction fails, and unlike the
 * desktop app's yt-dlp — a separate binary that updates itself — this one is
 * compiled in, so a fix means shipping an APK. Failures are therefore classified
 * rather than swallowed, so the player can skip a dead track and say why.
 *
 * Every call blocks. They all belong on Dispatchers.IO, which is enforced here
 * rather than trusted to callers.
 */
@Singleton
class Extractor @Inject constructor() {

    private val youtube get() = ServiceList.YouTube

    /**
     * Resolved audio URLs expire — YouTube stamps them with a lifetime of a few
     * hours — so they are cached in memory with that expiry and never persisted.
     * A stored URL would come back after a restart looking valid and 403 on play.
     *
     * Guarded by [lock] together with [inFlight]: reading the cache, finding it
     * stale and starting a resolution is one decision, and a plain concurrent map
     * would let two callers each make it.
     */
    private val lock = Mutex()
    private val cachedUrls = HashMap<String, CachedUrl>()

    /**
     * Resolutions currently running, so two tracks asking for the same URL at
     * once — the player prefetching what the user just tapped — share one
     * network round trip instead of racing.
     *
     * Held on the extractor's own scope rather than a caller's: whoever asked
     * first may be cancelled while others still want the answer.
     */
    private val inFlight = HashMap<String, Deferred<String>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Caps how many page-shaped extractions run at once.
     *
     * Every shelf on Explore is its own extraction, and the desktop app runs them
     * strictly one at a time because each was a yt-dlp process. Here they are
     * HTTP calls, so two in parallel is fine — but a screen with six shelves
     * firing together is a fast route to being rate limited.
     */
    private val browsing = Semaphore(2)

    private data class CachedUrl(val url: String, val expiresAt: Long) {
        fun fresh(): Boolean = expiresAt > System.currentTimeMillis()
    }

    // -- search ------------------------------------------------------------

    suspend fun search(
        query: String,
        kind: SearchKind = SearchKind.SONGS,
        limit: Int = 20
    ): SearchPage = withContext(Dispatchers.IO) {
        browsing.withPermit {
            val handler = handlerFor(query, kind)
            val info = attempt { SearchInfo.getInfo(youtube, handler) }
            SearchPage(streamHits(info.relatedItems, limit), info.nextPage)
        }
    }

    /** The next page of an earlier [search]. The kind must match the one that made it. */
    suspend fun searchMore(
        query: String,
        kind: SearchKind,
        page: Page,
        limit: Int = 20
    ): SearchPage = withContext(Dispatchers.IO) {
        browsing.withPermit {
            val handler = handlerFor(query, kind)
            val more = attempt { SearchInfo.getMoreItems(youtube, handler, page) }
            SearchPage(streamHits(more.items, limit), more.nextPage)
        }
    }

    /** Releases from YouTube Music's own album index — the only place album names exist. */
    suspend fun searchAlbums(query: String, limit: Int = 12): List<AlbumHit> =
        withContext(Dispatchers.IO) {
            browsing.withPermit {
                val handler = handlerFor(query, SearchKind.ALBUMS)
                val info = attempt { SearchInfo.getInfo(youtube, handler) }
                info.relatedItems
                    .filterIsInstance<PlaylistInfoItem>()
                    .take(limit)
                    .map {
                        AlbumHit(
                            url = it.url,
                            title = it.name.orEmpty().ifBlank { "Untitled release" },
                            artist = it.uploaderName,
                            thumbnailUrl = bestThumbnail(it),
                            trackCount = it.streamCount.coerceAtLeast(0)
                        )
                    }
            }
        }

    /** YouTube's own autocomplete, through the extractor. Empty on any failure. */
    suspend fun suggestions(query: String): List<String> = withContext(Dispatchers.IO) {
        runCatching { youtube.suggestionExtractor?.suggestionList(query).orEmpty() }
            .getOrDefault(emptyList())
    }

    // -- browsing ----------------------------------------------------------

    /**
     * A channel's recent uploads.
     *
     * The uploads tab is asked for by name rather than read off ChannelInfo,
     * which would cost a second extraction for a handler we can build ourselves.
     */
    suspend fun channelUploads(channelId: String, limit: Int = 12): List<SearchHit> =
        withContext(Dispatchers.IO) {
            browsing.withPermit {
                val handler = youtube.channelTabLHFactory
                    .fromQuery(channelId, listOf(ChannelTabs.VIDEOS), "")
                val info = attempt { ChannelTabInfo.getInfo(youtube, handler) }
                streamHits(info.relatedItems, limit)
            }
        }

    /**
     * YouTube's own "up next" for a video.
     *
     * Better than searching for something similar: these come from the same
     * source the track does, so autoplay follows a real relation rather than a
     * guess assembled from a genre word.
     */
    suspend fun related(videoId: String, limit: Int = 20): List<SearchHit> =
        withContext(Dispatchers.IO) {
            browsing.withPermit {
                val info = attempt { StreamInfo.getInfo(youtube, watchUrl(videoId)) }
                streamHits(info.relatedItems, limit)
            }
        }

    /** Every track on a playlist or album page, in its own running order. */
    /**
     * A playlist, followed past its first page.
     *
     * YouTube hands a playlist over about a hundred entries at a time with a
     * continuation token for the rest. Reading only `relatedItems` — which is
     * what this used to do — imports the first hundred of a six-hundred track
     * playlist and reports success, which is the quietest possible way to lose
     * someone's data.
     *
     * Bounded twice over: by [limit], and by [MAX_PLAYLIST_PAGES] so a
     * continuation that keeps returning a next page cannot loop forever. A page
     * that fails ends the walk and keeps what already arrived, because most of
     * a playlist beats none of it.
     */
    suspend fun playlistTracks(url: String, limit: Int = 200): ResolvedLink =
        withContext(Dispatchers.IO) {
            browsing.withPermit {
                val info = attempt { PlaylistInfo.getInfo(youtube, url) }
                val items = mutableListOf<InfoItem>()
                items += info.relatedItems

                var page: Page? = info.nextPage
                var pages = 0
                while (page != null && items.size < limit && pages < MAX_PLAYLIST_PAGES) {
                    pages++
                    val more = runCatching {
                        attempt { PlaylistInfo.getMoreItems(youtube, url, page) }
                    }.getOrNull() ?: break
                    if (more.items.isEmpty()) break
                    items += more.items
                    page = more.nextPage
                }

                ResolvedLink(info.name, streamHits(items, limit))
            }
        }

    /**
     * A pasted link or a phrase.
     *
     * Anything that is not a recognisable YouTube video or playlist is treated as
     * something to search for — a truncated paste is better served by a search
     * than by an error.
     */
    suspend fun resolveLink(input: String): ResolvedLink {
        val text = input.trim().trim('<', '>').trim()
        if (text.isEmpty()) throw ExtractionFailed(Reason.UNKNOWN, "Nothing to look up.")

        val url = if (text.startsWith("http://") || text.startsWith("https://")) text
        else if (YOUTUBE_HOST.containsMatchIn(text)) "https://$text"
        else null

        if (url != null) {
            val listId = Regex("""[?&]list=([\w-]+)""").find(url)?.groupValues?.get(1)
            // An autogenerated mix (RD...) never ends, so it is not a playlist
            // worth queueing — fall through to the video inside it.
            if (listId != null && !listId.startsWith("RD")) {
                return playlistTracks("https://www.youtube.com/playlist?list=$listId")
            }
            val videoId = videoIdFrom(url)
            if (videoId != null) return ResolvedLink(null, listOf(streamHit(videoId)))
        }

        val page = search(text, SearchKind.SONGS, limit = 20)
        if (page.hits.isEmpty()) throw ExtractionFailed(Reason.UNAVAILABLE, "No results for \"$text\".")
        return ResolvedLink(null, page.hits)
    }

    /** One video's own metadata, for a link that named it directly. */
    private suspend fun streamHit(videoId: String): SearchHit = withContext(Dispatchers.IO) {
        val info = attempt { StreamInfo.getInfo(youtube, watchUrl(videoId)) }
        SearchHit(
            track = TrackEntity(
                videoId = videoId,
                title = info.name.orEmpty().ifBlank { "Unknown title" },
                artist = info.uploaderName,
                thumbnailUrl = info.thumbnails.maxByOrNull { it.height }?.url,
                duration = info.duration.coerceAtLeast(0)
            ),
            channelId = channelIdFrom(info.uploaderUrl),
            channelName = info.uploaderName
        )
    }

    // -- playback ----------------------------------------------------------

    /**
     * A playable audio URL for one video.
     *
     * Bitrate decides, then container: Opus in WebM at a given bitrate sounds
     * better than the same bitrate in m4a, and ExoPlayer decodes both. On data
     * saver the choice inverts — the cheapest stream that is still music, not
     * the cheapest stream full stop, so a 32 kbit/s mono track is avoided.
     *
     * [refresh] forces a new resolution, which is what the player does when a
     * cached URL turns out to have expired mid-song.
     */
    suspend fun audioUrl(
        videoId: String,
        quality: AudioQuality = AudioQuality.HIGH,
        refresh: Boolean = false
    ): String {
        val pending = lock.withLock {
            if (refresh) {
                cachedUrls.remove(videoId)
            } else {
                cachedUrls[videoId]?.takeIf { it.fresh() }?.let { return it.url }
            }
            inFlight[videoId] ?: scope.async { resolveAudio(videoId, quality) }.also { started ->
                inFlight[videoId] = started
                // The entry clears itself rather than being cleared by whoever
                // asked first. That caller can be cancelled — a track change
                // while a URL is resolving — and a cleanup it never runs would
                // leave a *failed* Deferred in the map, handing the same stale
                // failure to every future caller for the life of the process.
                started.invokeOnCompletion {
                    scope.launch {
                        lock.withLock { if (inFlight[videoId] === started) inFlight.remove(videoId) }
                    }
                }
            }
        }
        return pending.await()
    }

    private suspend fun resolveAudio(videoId: String, quality: AudioQuality): String {
        val info = attempt { StreamInfo.getInfo(youtube, watchUrl(videoId)) }
        if (info.streamType == StreamType.LIVE_STREAM || info.streamType == StreamType.AUDIO_LIVE_STREAM) {
            // The desktop app learned this one the hard way: a "song" that is
            // really a 24/7 radio stream never ends and cannot be cached.
            throw ExtractionFailed(Reason.LIVESTREAM, "That is a livestream, not a track.")
        }

        val streams = info.audioStreams.filter { it.isUrl }
        if (streams.isEmpty()) {
            throw ExtractionFailed(Reason.NO_AUDIO, "No playable audio for that track.")
        }
        val stream = when (quality) {
            AudioQuality.HIGH -> streams.maxByOrNull { it.averageBitrate }
            // Above 48 kbit/s where anything qualifies, so data saver still
            // sounds like music rather than a phone call.
            AudioQuality.DATA_SAVER ->
                streams.filter { it.averageBitrate >= DATA_SAVER_FLOOR }
                    .minByOrNull { it.averageBitrate }
                    ?: streams.maxByOrNull { it.averageBitrate }
        } ?: throw ExtractionFailed(Reason.NO_AUDIO, "No playable audio for that track.")

        val url = stream.content
        // Deliberately short of the real expiry: a URL that dies mid-buffer is a
        // stall the user sees, whereas re-resolving early costs one round trip.
        lock.withLock {
            cachedUrls[videoId] = CachedUrl(url, System.currentTimeMillis() + URL_TTL_MS)
            if (cachedUrls.size > URL_CACHE_CAP) {
                // Bounded, and the oldest entry is the one closest to expiring
                // anyway. Without this a long session accumulates a URL per
                // track played, none of which is ever asked for again.
                cachedUrls.entries
                    .sortedBy { it.value.expiresAt }
                    .take(cachedUrls.size - URL_CACHE_CAP)
                    .forEach { cachedUrls.remove(it.key) }
            }
        }
        return url
    }

    suspend fun forget(videoId: String) {
        lock.withLock { cachedUrls.remove(videoId) }
    }

    /**
     * Blocking resolution, for the one caller that cannot suspend: the player's
     * data source runs on ExoPlayer's loading thread and must hand back a URL.
     */
    fun audioUrlBlocking(videoId: String, quality: AudioQuality, refresh: Boolean): String =
        kotlinx.coroutines.runBlocking { audioUrl(videoId, quality, refresh) }

    // -- plumbing ----------------------------------------------------------

    private fun handlerFor(query: String, kind: SearchKind) =
        YoutubeSearchQueryHandlerFactory.getInstance().fromQuery(
            query,
            listOf(
                when (kind) {
                    SearchKind.SONGS -> YoutubeSearchQueryHandlerFactory.MUSIC_SONGS
                    SearchKind.VIDEOS -> YoutubeSearchQueryHandlerFactory.VIDEOS
                    SearchKind.ALBUMS -> YoutubeSearchQueryHandlerFactory.MUSIC_ALBUMS
                }
            ),
            ""
        )

    private fun streamHits(items: List<InfoItem>, limit: Int): List<SearchHit> =
        items.filterIsInstance<StreamInfoItem>()
            .filter { it.streamType.isPlayableAudio() }
            .take(limit)
            .mapNotNull { it.toHit() }

    /**
     * Runs an extraction, retrying once on a failure that is plausibly transient.
     *
     * Bounded at two attempts on purpose: a second try covers a dropped
     * connection or a truncated response, and anything that fails twice is a
     * condition a third attempt will not fix. Rate limiting is never retried —
     * hammering a 429 is what turns a minute of backoff into an hour of it.
     */
    private suspend fun <T> attempt(block: () -> T): T {
        var last: Throwable? = null
        repeat(MAX_ATTEMPTS) { round ->
            try {
                return block()
            } catch (cancelled: CancellationException) {
                // A search the user navigated away from did not fail, and must
                // not be retried or reported. Caught explicitly because the
                // clause below would otherwise classify it as an unknown —
                // and therefore retryable — extraction error.
                throw cancelled
            } catch (err: Throwable) {
                val failure = classify(err)
                if (!failure.retryable || round == MAX_ATTEMPTS - 1) throw failure
                last = err
                delay(RETRY_DELAY_MS)
            }
        }
        throw classify(last ?: IllegalStateException("extraction failed"))
    }

    private fun StreamInfoItem.toHit(): SearchHit? {
        val id = videoIdFrom(url) ?: return null
        return SearchHit(
            track = TrackEntity(
                videoId = id,
                title = name.orEmpty().ifBlank { "Unknown title" },
                artist = uploaderName,
                thumbnailUrl = thumbnails.maxByOrNull { it.height }?.url,
                duration = duration.coerceAtLeast(0)
            ),
            channelId = uploaderUrl?.let { channelIdFrom(it) },
            channelName = uploaderName
        )
    }

    private fun bestThumbnail(item: InfoItem): String? =
        item.thumbnails.maxByOrNull { it.height }?.url

    companion object {
        private const val URL_TTL_MS = 3 * 60 * 60 * 1000L
        private const val URL_CACHE_CAP = 200
        private const val DATA_SAVER_FLOOR = 48
        private const val MAX_ATTEMPTS = 2
        private const val RETRY_DELAY_MS = 700L

        /**
         * A stop on playlist pagination, independent of the track limit. A
         * continuation that keeps returning a next page for an empty one would
         * otherwise walk until the battery ran out.
         */
        private const val MAX_PLAYLIST_PAGES = 12

        private val YOUTUBE_HOST =
            Regex("""^(www\.|m\.|music\.)?(youtube\.com|youtube-nocookie\.com|youtu\.be)/""")

        fun watchUrl(videoId: String): String = "https://www.youtube.com/watch?v=$videoId"

        /**
         * NewPipe hands back URLs, not ids, so the id the whole library is keyed
         * on comes from parsing one. Covers the three shapes YouTube actually
         * emits: watch links, youtu.be, and shorts.
         */
        fun videoIdFrom(url: String?): String? {
            if (url.isNullOrBlank()) return null
            Regex("""[?&]v=([\w-]{11})""").find(url)?.let { return it.groupValues[1] }
            Regex("""youtu\.be/([\w-]{11})""").find(url)?.let { return it.groupValues[1] }
            Regex("""/shorts/([\w-]{11})""").find(url)?.let { return it.groupValues[1] }
            Regex("""/live/([\w-]{11})""").find(url)?.let { return it.groupValues[1] }
            return null
        }

        fun channelIdFrom(url: String?): String? =
            url?.let { Regex("""/channel/([\w-]+)""").find(it)?.groupValues?.get(1) }

        fun okHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        /** Call once, before anything touches the extractor. */
        fun init(client: OkHttpClient) {
            NewPipe.init(OkHttpDownloader(client))
        }

        /**
         * Turns whatever the extractor threw into something the UI can act on.
         *
         * The distinction that matters is not the exception's name but what the
         * app should do: skip this track and say why, back off, or offer a retry.
         * An unclassified failure would arrive at the player as "something went
         * wrong", which is the one message that helps nobody.
         *
         * Cancellation is rethrown rather than classified. Several callers reach
         * here through a `runCatching` or a `catch (Throwable)`, and every one of
         * those would otherwise turn "the user left this screen" into an error
         * message and a dead coroutine scope that believes it is still alive.
         */
        fun classify(err: Throwable): ExtractionFailed {
            if (err is CancellationException) throw err
            return classified(err)
        }

        private fun classified(err: Throwable): ExtractionFailed = when (err) {
            is ExtractionFailed -> err
            is ReCaptchaException -> ExtractionFailed(
                Reason.RATE_LIMITED,
                "YouTube is rate limiting this device. Give it a minute and try again."
            )
            is AgeRestrictedContentException -> ExtractionFailed(
                Reason.AGE_RESTRICTED,
                "That video is age restricted and cannot be played without signing in."
            )
            is GeographicRestrictionException -> ExtractionFailed(
                Reason.UNAVAILABLE,
                "That video is not available in this country."
            )
            is PrivateContentException -> ExtractionFailed(
                Reason.UNAVAILABLE,
                "That video is private."
            )
            is AccountTerminatedException -> ExtractionFailed(
                Reason.UNAVAILABLE,
                "That channel is gone, and its uploads with it."
            )
            is PaidContentException, is YoutubeMusicPremiumContentException,
            is SoundCloudGoPlusContentException -> ExtractionFailed(
                Reason.UNAVAILABLE,
                "That track needs a paid subscription on YouTube."
            )
            is ContentNotSupportedException -> ExtractionFailed(
                Reason.UNSUPPORTED,
                "SYmuS cannot play that kind of upload."
            )
            is ContentNotAvailableException -> ExtractionFailed(
                Reason.UNAVAILABLE,
                "That video is unavailable — it may have been removed."
            )
            is InterruptedIOException -> ExtractionFailed(
                Reason.NETWORK,
                "That took too long. Check your connection and try again.",
                retryable = true
            )
            is IOException -> ExtractionFailed(
                Reason.NETWORK,
                "No connection to YouTube.",
                retryable = true
            )
            // Everything left is the extractor failing to read a page it used to
            // understand, which is what a YouTube change looks like from here.
            else -> ExtractionFailed(
                Reason.UNKNOWN,
                err.message?.takeIf { it.isNotBlank() }?.let { "Extraction failed: $it" }
                    ?: "Extraction failed. YouTube may have changed.",
                retryable = true
            )
        }
    }
}

private fun StreamType.isPlayableAudio(): Boolean =
    this == StreamType.VIDEO_STREAM || this == StreamType.AUDIO_STREAM

/** Why extraction failed, in the terms the UI needs to decide whether to skip. */
enum class Reason { LIVESTREAM, NO_AUDIO, AGE_RESTRICTED, UNAVAILABLE, UNSUPPORTED, RATE_LIMITED, NETWORK, UNKNOWN }

/**
 * A classified extraction failure. [message] is written to be shown as-is.
 *
 * [retryable] means "trying again later could work", which is what an offer of a
 * Retry button is claiming. A removed video is not retryable, and a button that
 * cannot succeed is worse than none.
 */
class ExtractionFailed(
    val reason: Reason,
    message: String,
    val retryable: Boolean = false
) : Exception(message)
