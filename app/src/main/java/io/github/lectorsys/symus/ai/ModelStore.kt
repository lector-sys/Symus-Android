package io.github.lectorsys.symus.ai

import android.content.Context
import android.net.Uri
import android.os.StatFs
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where a model install has got to, in the only terms the screen needs.
 *
 * [Failed] carries a sentence meant to be read by the person who hit it rather
 * than an exception name — "the download stopped after 210 MB" is actionable,
 * `SocketTimeoutException` is not.
 */
sealed interface ModelState {
    data object Absent : ModelState

    data class Downloading(val bytes: Long, val total: Long) : ModelState {
        val fraction: Float get() = if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else 0f
    }

    /** Hashing half a gigabyte is not instant and must not look like a hang. */
    data class Verifying(val bytes: Long, val total: Long) : ModelState

    data class Importing(val bytes: Long) : ModelState

    data class Installed(val spec: ModelSpec?, val file: File, val bytes: Long) : ModelState

    data class Failed(val message: String, val partialBytes: Long = 0) : ModelState
}

/**
 * Installs, verifies, imports and deletes model bundles.
 *
 * **Why the file is verified rather than trusted.** The bundle is handed to a
 * native runtime that memory-maps it and interprets it as a graph. A corrupt or
 * substituted file is not a bad answer, it is a native crash, and the only
 * cheap defence is checking the whole thing against a digest written down
 * before the transfer started.
 *
 * **Why downloads resume rather than restart.** Half a gigabyte over a phone
 * connection fails routinely. A downloader that starts from zero every time is
 * one the user gives up on.
 *
 * Nothing here sits on the playback path: a model that will not install leaves
 * the rest of the app exactly as it was.
 */
@Singleton
class ModelStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val client: OkHttpClient
) {

    private val dir = File(context.filesDir, "models").apply { mkdirs() }

    private val _state = MutableStateFlow<ModelState>(ModelState.Absent)
    val state: StateFlow<ModelState> = _state.asStateFlow()

    /** The bundle currently installed, whatever its provenance. */
    fun installed(): File? =
        dir.listFiles()?.firstOrNull { it.name.endsWith(SUFFIX) && it.length() > 0 }

    /** The catalogue entry the installed file came from, or null when imported. */
    fun installedSpec(): ModelSpec? =
        ModelCatalog.byId(installed()?.name?.removeSuffix(SUFFIX))

    fun refresh() {
        val file = installed()
        _state.value =
            if (file == null) ModelState.Absent
            else ModelState.Installed(installedSpec(), file, file.length())
    }

    /**
     * Fetches [spec], resuming a previous attempt when one is lying around.
     *
     * Cancellation is cooperative and leaves the partial file in place, which is
     * the point: cancelling is not discarding, and the next attempt should not
     * re-transfer what already arrived.
     */
    suspend fun download(spec: ModelSpec): Result<File> = withContext(Dispatchers.IO) {
        val target = File(dir, spec.id + SUFFIX)
        val partial = File(dir, spec.id + PARTIAL)

        runCatching {
            if (target.exists() && target.length() == spec.sizeBytes) {
                refresh()
                return@runCatching target
            }

            val have = if (partial.exists()) partial.length() else 0L
            val needed = spec.sizeBytes - have
            val free = freeBytes()
            require(free > needed + MARGIN) {
                "Not enough space. ${needed / 1_000_000} MB still to download, " +
                    "${free / 1_000_000} MB free."
            }

            _state.value = ModelState.Downloading(have, spec.sizeBytes)

            val request = Request.Builder().url(spec.url).apply {
                if (have > 0) header("Range", "bytes=$have-")
            }.build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    error("The server refused the download (HTTP ${response.code}).")
                }
                // A server that ignores Range answers 200 with the whole file.
                // Appending to the partial then splices two copies together into
                // something that hashes to nothing, so the offset decides the
                // write mode rather than the request doing.
                val resuming = response.code == 206
                val body = response.body
                var written = if (resuming) have else 0L

                FileOutputStream(partial, resuming).use { out ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(BUFFER)
                        while (true) {
                            yield() // cancellation lands here, never mid-write
                            val read = input.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            written += read
                            _state.value = ModelState.Downloading(written, spec.sizeBytes)
                        }
                    }
                }
            }

            if (partial.length() != spec.sizeBytes) {
                error(
                    "The download stopped after ${partial.length() / 1_000_000} MB of " +
                        "${spec.sizeBytes / 1_000_000} MB."
                )
            }

            _state.value = ModelState.Verifying(partial.length(), spec.sizeBytes)
            val digest = sha256(partial)
            if (!digest.equals(spec.sha256, ignoreCase = true)) {
                partial.delete()
                error("The file did not match its published checksum and was discarded.")
            }

            // Only now does it become the installed model. The rename is atomic,
            // so a half-verified file never occupies the real name.
            dir.listFiles()?.forEach { if (it.name.endsWith(SUFFIX)) it.delete() }
            check(partial.renameTo(target)) { "Could not move the model into place." }
            refresh()
            target
        }.onFailure { failure ->
            if (failure is CancellationException) {
                _state.value = ModelState.Failed(
                    "Download paused at ${partial.length() / 1_000_000} MB. " +
                        "Starting it again resumes from there.",
                    partial.length()
                )
                throw failure
            }
            _state.value = ModelState.Failed(
                failure.message ?: "The download failed.",
                if (partial.exists()) partial.length() else 0L
            )
        }
    }

    /**
     * Takes a bundle the user already has, through the system file picker.
     *
     * No checksum exists for a file nobody published, so the only checks
     * possible here are structural: non-empty, plausibly sized, and it fits.
     * Whether it actually loads is proven by loading it, and the UI says so
     * rather than implying a verification that did not happen.
     */
    suspend fun importFrom(uri: Uri, name: String): Result<File> = withContext(Dispatchers.IO) {
        val target = File(dir, IMPORTED + SUFFIX)
        val partial = File(dir, IMPORTED + PARTIAL)
        runCatching {
            _state.value = ModelState.Importing(0)
            val resolver = context.contentResolver
            val declared = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
            if (declared > 0) {
                require(freeBytes() > declared + MARGIN) {
                    "Not enough space for $name (${declared / 1_000_000} MB)."
                }
            }
            var copied = 0L
            resolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "That file could not be opened." }
                FileOutputStream(partial).use { out ->
                    val buffer = ByteArray(BUFFER)
                    while (true) {
                        yield()
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        copied += read
                        _state.value = ModelState.Importing(copied)
                    }
                }
            }
            require(copied > MIN_PLAUSIBLE) {
                "$name is only ${copied / 1_000} kB, which is not a model bundle."
            }
            dir.listFiles()?.forEach { if (it.name.endsWith(SUFFIX)) it.delete() }
            check(partial.renameTo(target)) { "Could not move the model into place." }
            refresh()
            target
        }.onFailure { failure ->
            partial.delete()
            _state.value = ModelState.Failed(failure.message ?: "That file could not be imported.")
        }
    }

    /** Removes the installed model and any partial transfer with it. */
    fun delete() {
        dir.listFiles()?.forEach { it.delete() }
        _state.value = ModelState.Absent
    }

    /** Throws away a paused transfer without touching an installed model. */
    fun discardPartial() {
        dir.listFiles()?.forEach { if (it.name.endsWith(PARTIAL)) it.delete() }
        refresh()
    }

    fun partialBytes(spec: ModelSpec): Long =
        File(dir, spec.id + PARTIAL).let { if (it.exists()) it.length() else 0L }

    fun freeBytes(): Long = runCatching {
        StatFs(context.filesDir.path).availableBytes
    }.getOrDefault(0L)

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val SUFFIX = ".task"
        const val PARTIAL = ".task.part"
        const val IMPORTED = "imported"
        const val BUFFER = 1 shl 16
        const val MARGIN = 128L * 1024 * 1024
        const val MIN_PLAUSIBLE = 8L * 1024 * 1024
    }
}
