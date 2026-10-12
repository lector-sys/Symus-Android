package io.github.lectorsys.symus.media

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.StatFs
import androidx.media3.common.util.UnstableApi
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.lectorsys.symus.data.DownloadDao
import io.github.lectorsys.symus.data.DownloadEntity
import io.github.lectorsys.symus.data.DownloadState
import io.github.lectorsys.symus.data.SettingsRepository
import io.github.lectorsys.symus.data.TrackDao
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * A download failure this class already has a good message for.
 *
 * Distinguished from a bare IOException so [Downloads.reasonFor] can pass the
 * message straight through instead of handing it to the extractor's classifier,
 * which only understands network and YouTube faults and would relabel a storage
 * problem as a connection one.
 */
class DownloadFailed(message: String) : IOException(message)

/** Live progress for one download, for a bar that moves more often than the table does. */
data class DownloadProgress(val videoId: String, val bytes: Long, val total: Long) {
    val fraction: Float get() = if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else 0f
}

/**
 * Downloading audio for offline play.
 *
 * One download at a time, drained from a table rather than held in memory, so a
 * process death loses nothing: an interrupted download resumes from the bytes
 * already on disk, and a queue survives a restart.
 *
 * The desktop app only allows caching a track that has already been played —
 * deliberately a convenience cache rather than a download manager. That rule
 * makes no sense on a phone, where downloading before a journey is the entire
 * point, so here a download saves the track to the library instead.
 */
@UnstableApi
@Singleton
class Downloads @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: OkHttpClient,
    private val extractor: Extractor,
    private val tracks: TrackDao,
    private val downloads: DownloadDao,
    private val settings: SettingsRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Signals the worker that there may be something to do. Conflated: one wake is enough. */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    private val _progress = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    val progress: StateFlow<Map<String, DownloadProgress>> = _progress.asStateFlow()

    val queue: Flow<List<DownloadEntity>> = downloads.all()
    val offlineTracks = tracks.cachedFlow()
    val summary = tracks.cacheSummaryFlow()

    private var running: String? = null
    private var runningJob: Job? = null

    init {
        scope.launch {
            // Anything left RUNNING was killed with the process, not by a failure.
            downloads.requeueInterrupted()
            wake.trySend(Unit)
            worker()
        }
        watchNetwork()
    }

    /**
     * Wakes the worker when the network changes.
     *
     * Without this, "download on wi-fi only" was a queue that waited for the
     * next enqueue rather than for wi-fi: the worker parked on [wake] and
     * nothing ever signalled it, so a download deferred on mobile data stayed
     * deferred until the app was restarted or another track was queued.
     */
    private fun watchNetwork() {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return
        runCatching {
            manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    wake.trySend(Unit)
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    capabilities: NetworkCapabilities
                ) {
                    // Tethering to wi-fi does not re-announce availability, it
                    // changes what the existing network can do.
                    if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) {
                        wake.trySend(Unit)
                    }
                }
            })
        }
    }

    // -- the queue ---------------------------------------------------------

    /** Asks for a track to be downloaded. Already-downloaded tracks are a no-op. */
    suspend fun enqueue(videoId: String) = withContext(Dispatchers.IO) {
        val row = tracks.byId(videoId) ?: return@withContext
        if (row.cachedPath != null && File(row.cachedPath).isFile) return@withContext
        tracks.markSaved(videoId, System.currentTimeMillis())
        downloads.put(DownloadEntity(videoId = videoId, state = DownloadState.QUEUED))
        // The work runs here, in the app process; the service is what stops
        // Android reclaiming that process the moment the screen goes off.
        DownloadService.start(context)
        wake.trySend(Unit)
    }

    suspend fun enqueueAll(videoIds: List<String>) {
        videoIds.forEach { enqueue(it) }
    }

    /** Stops a download and forgets it was asked for. Partial bytes are dropped. */
    suspend fun cancel(videoId: String) = withContext(Dispatchers.IO) {
        // Waited for, not just signalled. The write loop only notices
        // cancellation at a suspension point, so a cancel that returned
        // immediately could delete the partial file underneath a writer that
        // then recreated it — or let the download finish and record itself as
        // available offline after the user had cancelled it.
        if (running == videoId) {
            runningJob?.let { job ->
                job.cancel(CancellationException("cancelled by user"))
                runCatching { job.join() }
            }
        }
        downloads.remove(videoId)
        partFile(videoId).delete()
        _progress.value = _progress.value - videoId
    }

    /** Puts a failed download back in the queue, keeping whatever it managed to fetch. */
    suspend fun retry(videoId: String) = withContext(Dispatchers.IO) {
        downloads.setState(videoId, DownloadState.QUEUED, null)
        DownloadService.start(context)
        wake.trySend(Unit)
    }

    /**
     * Deletes a downloaded file and the claim that it exists.
     *
     * The row is cleared whether or not the file could be removed, so a file that
     * vanished underneath us cannot leave the library saying it is available
     * offline.
     */
    suspend fun remove(videoId: String) = withContext(Dispatchers.IO) {
        val row = tracks.byId(videoId)
        row?.cachedPath?.let { File(it).delete() }
        partFile(videoId).delete()
        tracks.setCached(videoId, null, null)
        downloads.remove(videoId)
        _progress.value = _progress.value - videoId
    }

    suspend fun removeAll(): Int = withContext(Dispatchers.IO) {
        val cached = tracks.cached()
        cached.forEach { remove(it.videoId) }
        downloads.clear()
        // Sweeps any .part files an interrupted download left behind.
        cacheDir().listFiles()?.forEach { it.delete() }
        cached.size
    }

    /** Bytes free on the volume the cache lives on. */
    fun freeBytes(): Long = runCatching {
        val stat = StatFs(cacheDir().absolutePath)
        stat.availableBlocksLong * stat.blockSizeLong
    }.getOrDefault(0L)

    // -- the worker --------------------------------------------------------

    private suspend fun worker() {
        while (scope.isActive) {
            wake.receive()
            while (scope.isActive) {
                val next = downloads.pending().firstOrNull() ?: break
                if (waitingForWifi()) {
                    downloads.setState(
                        next.videoId,
                        DownloadState.QUEUED,
                        "Waiting for wi-fi"
                    )
                    break
                }
                running = next.videoId
                val job = scope.launch { run(next.videoId) }
                runningJob = job
                job.join()
                running = null
                runningJob = null
            }
        }
    }

    private fun waitingForWifi(): Boolean {
        if (!settings.current.downloadOnWifiOnly) return false
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        return manager.isActiveNetworkMetered
    }

    private suspend fun run(videoId: String) {
        downloads.setState(videoId, DownloadState.RUNNING, null)
        try {
            val bytes = download(videoId)
            downloads.remove(videoId)
            _progress.value = _progress.value - videoId
            if (bytes == 0L) {
                // Nothing to record; treated as a failure rather than a silent success.
                downloads.put(
                    DownloadEntity(
                        videoId = videoId,
                        state = DownloadState.FAILED,
                        error = "The download produced no audio."
                    )
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (err: Throwable) {
            downloads.setState(videoId, DownloadState.FAILED, reasonFor(err))
            _progress.value = _progress.value - videoId
        }
    }

    /**
     * What to tell the user about a failed download.
     *
     * Only extraction failures go through the extractor's classifier. Running
     * everything through it meant a full disk or an unwritable cache directory
     * was reported as "No connection to YouTube." — every local failure is an
     * IOException, and that is the one the classifier maps to a network fault.
     * It also threw away the messages written right here, so the low-space case
     * never reached the screen it was written for.
     */
    private fun reasonFor(err: Throwable): String = when (err) {
        is ExtractionFailed -> err.message ?: "That track could not be fetched."
        is DownloadFailed -> err.message ?: "That download failed."
        // Left deliberately vague about which half failed, because at this
        // point it genuinely could be either, and guessing wrong is what the
        // old message did.
        is IOException -> "That download failed. Check your connection and free space."
        else -> Extractor.classify(err).message ?: "That download failed."
    }

    /**
     * Pulls a track to disk a chunk at a time, resuming from whatever is there.
     *
     * googlevideo throttles a single long GET down to roughly playback speed — an
     * 80 MB upload arrives at tens of kilobytes a second — so saving a track
     * would take as long as hearing it. Every fresh ranged request gets an
     * unthrottled burst instead, and a burst comfortably covers a chunk this
     * size. Resuming falls out of the same mechanism for free.
     */
    private suspend fun download(videoId: String): Long {
        val directory = cacheDir().apply { mkdirs() }
        val partial = partFile(videoId)
        var at = if (partial.isFile) partial.length() else 0L
        var total = 0L
        var contentType: String? = null
        val url = extractor.audioUrl(videoId, settings.current.quality)

        while (true) {
            val last = at + CHUNK - 1
            val request = Request.Builder()
                .url(url)
                .header("Range", "bytes=$at-$last")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw DownloadFailed("YouTube returned ${response.code} for that audio.")
                }
                val body = response.body
                if (contentType == null) contentType = response.header("content-type")
                if (total == 0L) {
                    // "bytes 0-8388607/81065857" — the tail is the size of the
                    // whole file. A server that ignored the range answers 200 and
                    // has just sent everything, which ends the loop on its own.
                    total = response.header("content-range")
                        ?.substringAfter('/')
                        ?.toLongOrNull() ?: 0L
                    if (total > 0) ensureRoom(total - at)
                }
                // Append mode whenever there is already something on disk, so a
                // resumed download adds to the partial file instead of
                // truncating it — which is what opening it for writing would do.
                // Opened explicitly rather than inline, so a directory that
                // cannot be written to — no space, wrong permissions — is
                // reported as the storage problem it is.
                val output = try {
                    FileOutputStream(partial, at > 0)
                } catch (err: IOException) {
                    // Deliberately not the exception's own text: it is a full
                    // internal path and an errno, which tells the user nothing
                    // they can act on.
                    throw DownloadFailed("Could not save to storage. Check the device has free space.")
                }
                body.byteStream().use { input ->
                    output.use { sink ->
                        val buffer = ByteArray(BUFFER)
                        var sinceReport = 0L
                        while (true) {
                            // Checked per buffer so a cancel is noticed within
                            // 64 KB rather than at the next progress write,
                            // half a megabyte later.
                            coroutineContext.ensureActive()
                            val read = input.read(buffer)
                            if (read <= 0) break
                            sink.write(buffer, 0, read)
                            at += read
                            sinceReport += read
                            _progress.value = _progress.value +
                                (videoId to DownloadProgress(videoId, at, total))
                            if (sinceReport >= REPORT_EVERY) {
                                sinceReport = 0
                                downloads.setProgress(videoId, at, total)
                            }
                        }
                    }
                }
                if (response.code == 200) total = at
            }
            downloads.setProgress(videoId, at, total)
            if (total == 0L || at >= total) break
        }

        val size = partial.length()
        if (total > 0 && size != total) {
            // A chunk cut short by the CDN would otherwise be stored as a
            // complete track and play as one, silently missing its end.
            throw DownloadFailed("That download stopped early ($size of $total bytes).")
        }
        if (size == 0L) return 0L

        val finalFile = File(directory, videoId + extensionFor(contentType))
        // Renamed only once complete, so an interrupted download can never leave
        // a truncated file the player would treat as the whole track.
        if (finalFile.exists()) finalFile.delete()
        if (!partial.renameTo(finalFile)) throw DownloadFailed("Could not store that download.")
        tracks.setCached(videoId, finalFile.absolutePath, size)
        return size
    }

    /** Refuses a download that would not fit, rather than filling the device. */
    private fun ensureRoom(needed: Long) {
        if (!hasRoomFor(needed, freeBytes())) {
            throw DownloadFailed("Not enough free space for that download.")
        }
    }

    private fun cacheDir(): File = File(context.filesDir, "audio-cache")

    private fun partFile(videoId: String) = File(cacheDir(), "$videoId.part")

    internal companion object {
        /**
         * Whether a download of [needed] bytes should be allowed to start.
         *
         * A pure predicate so the decision is testable: the real path reads
         * StatFs, and a device cannot be safely filled to exercise it.
         *
         * A [free] of zero means StatFs failed rather than "the disk is full",
         * and refusing every download because the check itself broke would be
         * worse than letting the write fail with a real error.
         */
        internal fun hasRoomFor(needed: Long, free: Long): Boolean =
            free <= 0 || free >= needed + HEADROOM

        const val CHUNK = 8L * 1024 * 1024
        const val BUFFER = 64 * 1024
        const val REPORT_EVERY = 512L * 1024
        const val HEADROOM = 32L * 1024 * 1024

        /**
         * The extension is the only place the container is recorded — there is no
         * column for it — so it has to be derivable back into something the
         * player can read when the file is served.
         */
        fun extensionFor(contentType: String?): String {
            val type = contentType.orEmpty().lowercase()
            return when {
                type.contains("webm") -> ".webm"
                type.contains("mp4") || type.contains("m4a") || type.contains("aac") -> ".m4a"
                type.contains("ogg") || type.contains("opus") -> ".opus"
                else -> ".webm"
            }
        }
    }
}
