package io.github.lectorsys.symus.media

import io.github.lectorsys.symus.data.TrackEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request as OkRequest
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
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
                response.body?.string(),
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
     */
    private val audioUrls = HashMap<String, CachedUrl>()

    private data class CachedUrl(val url: String, val expiresAt: Long)

    suspend fun search(query: String, limit: Int = 12): List<SearchHit> = withContext(Dispatchers.IO) {
        val handler = YoutubeSearchQueryHandlerFactory.getInstance()
            .fromQuery(query, listOf(YoutubeSearchQueryHandlerFactory.VIDEOS), "")
        SearchInfo.getInfo(youtube, handler)
            .relatedItems
            .filterIsInstance<StreamInfoItem>()
            .filter { it.streamType.isPlayableAudio() }
            .take(limit)
            .mapNotNull { it.toHit() }
    }

    /**
     * A playable audio URL for one video.
     *
     * Bitrate decides, then container: Opus in WebM at a given bitrate sounds
     * better than the same bitrate in m4a, and ExoPlayer decodes both. Progressive
     * streams are preferred over DASH-only ones because they play from a single
     * URL with ranged requests, which is exactly what the cache wants later.
     */
    suspend fun audioUrl(videoId: String): String = withContext(Dispatchers.IO) {
        audioUrls[videoId]?.takeIf { it.expiresAt > System.currentTimeMillis() }?.let { return@withContext it.url }

        val info = StreamInfo.getInfo(youtube, watchUrl(videoId))
        if (info.streamType == StreamType.LIVE_STREAM || info.streamType == StreamType.AUDIO_LIVE_STREAM) {
            // The desktop app learned this one the hard way: a "song" that is
            // really a 24/7 radio stream never ends and cannot be cached.
            throw ExtractionFailed(Reason.LIVESTREAM, "That is a livestream, not a track.")
        }

        val stream = info.audioStreams
            .filter { it.isUrl && it.content != null }
            .maxByOrNull { it.averageBitrate }
            ?: throw ExtractionFailed(Reason.NO_AUDIO, "No playable audio for that track.")

        // Deliberately short of the real expiry: a URL that dies mid-buffer is a
        // stall the user sees, whereas re-resolving early costs one round trip.
        audioUrls[videoId] = CachedUrl(stream.content, System.currentTimeMillis() + URL_TTL_MS)
        stream.content
    }

    fun forget(videoId: String) {
        audioUrls.remove(videoId)
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

    companion object {
        private const val URL_TTL_MS = 3 * 60 * 60 * 1000L

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
    }
}

private fun StreamType.isPlayableAudio(): Boolean =
    this == StreamType.VIDEO_STREAM || this == StreamType.AUDIO_STREAM

/** Why extraction failed, in the terms the UI needs to decide whether to skip. */
enum class Reason { LIVESTREAM, NO_AUDIO, AGE_RESTRICTED, UNAVAILABLE, RATE_LIMITED, NETWORK, UNKNOWN }

class ExtractionFailed(val reason: Reason, message: String) : Exception(message)
