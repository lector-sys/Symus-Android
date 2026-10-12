package io.github.lectorsys.symus.media

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import io.github.lectorsys.symus.data.SettingsRepository
import io.github.lectorsys.symus.data.TrackDao
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException

/**
 * Turns a queued `symus://<videoId>` into something that can actually be read.
 *
 * A downloaded copy wins outright: that is what makes playback work with no
 * network at all, and it also skips an extraction and a CDN round trip. A row
 * pointing at a file that is missing or the wrong size is treated as not
 * downloaded, so a half-written or externally-deleted file falls through to the
 * network rather than playing as silence forever.
 *
 * Runs on ExoPlayer's loading thread, which is allowed to block — that is the
 * whole contract of a resolver, and it is why the suspending cache underneath
 * is entered through runBlocking here and nowhere else.
 */
@UnstableApi
class SymusResolver(
    private val extractor: Extractor,
    private val tracks: TrackDao,
    private val settings: SettingsRepository
) : ResolvingDataSource.Resolver {

    /**
     * Videos whose cached URL has already been thrown away once for this
     * playback attempt, so a stale URL costs one re-resolution rather than an
     * endless pair of them.
     */
    private val refreshed = HashSet<String>()

    override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
        if (dataSpec.uri.scheme != SYMUS_SCHEME) return dataSpec
        val videoId = dataSpec.uri.host
            ?: throw IOException("Malformed stream request: ${dataSpec.uri}")

        localFile(videoId)?.let { return dataSpec.withUri(Uri.fromFile(it)) }

        val refresh = synchronized(refreshed) { refreshed.remove(videoId) }
        val url = try {
            runBlocking { extractor.audioUrl(videoId, settings.current.quality, refresh) }
        } catch (err: Throwable) {
            // Carries the classified message across into ExoPlayer's error, which
            // is the only thing the player will report back to the UI.
            val failure = Extractor.classify(err)
            throw IOException(failure.message, failure)
        }
        return dataSpec.withUri(url.toUri())
    }

    /** Ask for a new URL next time this video is opened. */
    fun invalidate(videoId: String) {
        synchronized(refreshed) { refreshed.add(videoId) }
        runBlocking { extractor.forget(videoId) }
    }

    private fun localFile(videoId: String): File? {
        val row = runBlocking { tracks.byId(videoId) } ?: return null
        val path = row.cachedPath ?: return null
        val file = File(path)
        if (!file.isFile || file.length() == 0L) return null
        if (row.cachedBytes != null && row.cachedBytes != file.length()) return null
        return file
    }

    companion object {
        /**
         * The data source chain: resolve, then read.
         *
         * DefaultDataSource is what lets one chain serve both cases — it handles
         * `file://` itself and hands anything remote to OkHttp, which is the same
         * client the extractor uses, so the connection to googlevideo is already
         * warm when playback starts.
         */
        @UnstableApi
        fun factory(
            context: Context,
            client: OkHttpClient,
            resolver: SymusResolver
        ): DataSource.Factory = ResolvingDataSource.Factory(
            DefaultDataSource.Factory(context, OkHttpDataSource.Factory(client)),
            resolver
        )
    }
}
