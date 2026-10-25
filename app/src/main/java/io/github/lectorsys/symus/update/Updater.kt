package io.github.lectorsys.symus.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val version: String, val url: String, val sha256: String?) : UpdateState
    data class Downloading(val fraction: Float) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * Self-update from this repository's GitHub Releases.
 *
 * The latest release is the source of truth: `scripts/release.sh` on the
 * publishing machine builds both flavours and attaches them as
 * `symus-full.apk` and `symus-lite.apk`, and every installed copy compares its
 * own versionName against the release tag. Each copy fetches its own flavour,
 * so a lite install never gets offered a full APK it cannot install.
 *
 * Android itself refuses an update signed with a different key, so the
 * signature check is the installer's, not ours. The digest check catches a
 * truncated or corrupted download before the installer has to.
 */
@Singleton
class Updater @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val client: OkHttpClient
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    val currentVersion: String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"

    private val apk = File(context.cacheDir, "updates/symus-update.apk")

    suspend fun check(): UpdateState = withContext(Dispatchers.IO) {
        _state.value = UpdateState.Checking
        val result = runCatching {
            val request = Request.Builder().url(LATEST)
                .header("Accept", "application/vnd.github+json").build()
            val json = client.newCall(request).execute().use { response ->
                if (response.code == 404) return@runCatching UpdateState.UpToDate // nothing published yet
                if (!response.isSuccessful) error("GitHub answered HTTP ${response.code}.")
                JSONObject(response.body.string())
            }
            val tag = json.getString("tag_name")
            if (!isNewer(tag, currentVersion)) return@runCatching UpdateState.UpToDate

            val wanted = if (currentVersion.endsWith("-lite")) "symus-lite.apk" else "symus-full.apk"
            val assets = json.getJSONArray("assets")
            val asset = (0 until assets.length()).map(assets::getJSONObject)
                .firstOrNull { it.getString("name") == wanted }
                ?: error("Version $tag has no $wanted attached.")
            UpdateState.Available(
                version = tag.removePrefix("v"),
                url = asset.getString("browser_download_url"),
                sha256 = asset.optString("digest").removePrefix("sha256:").ifBlank { null }
            )
        }.getOrElse { UpdateState.Failed(it.message ?: "Could not reach GitHub.") }
        _state.value = result
        result
    }

    /** Downloads [update] and hands it to the system installer, which asks the user. */
    suspend fun install(update: UpdateState.Available) = withContext(Dispatchers.IO) {
        runCatching {
            apk.parentFile?.mkdirs()
            _state.value = UpdateState.Downloading(0f)
            client.newCall(Request.Builder().url(update.url).build()).execute().use { response ->
                if (!response.isSuccessful) error("The download was refused (HTTP ${response.code}).")
                val total = response.body.contentLength()
                val digest = MessageDigest.getInstance("SHA-256")
                apk.outputStream().use { out ->
                    response.body.byteStream().use { input ->
                        val buffer = ByteArray(1 shl 16)
                        var written = 0L
                        while (true) {
                            yield()
                            val read = input.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            written += read
                            if (total > 0) _state.value = UpdateState.Downloading(written.toFloat() / total)
                        }
                    }
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (update.sha256 != null && !actual.equals(update.sha256, ignoreCase = true)) {
                    apk.delete()
                    error("The download did not match its published checksum and was discarded.")
                }
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
            // The installer shows its own "allow installs from SYmuS" prompt the
            // first time; REQUEST_INSTALL_PACKAGES in the manifest is what lets it.
            context.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            _state.value = update
        }.onFailure { _state.value = UpdateState.Failed(it.message ?: "The update failed.") }
    }

    companion object {
        private const val LATEST = "https://api.github.com/repos/lector-sys/Symus-Android/releases/latest"

        /** `v0.2.0` against `0.1.0-lite`: numeric, per dotted part, flavour suffix ignored. */
        fun isNewer(remote: String, local: String): Boolean {
            fun parts(v: String) = v.removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
            val r = parts(remote)
            val l = parts(local)
            for (i in 0 until maxOf(r.size, l.size)) {
                val a = r.getOrElse(i) { 0 }
                val b = l.getOrElse(i) { 0 }
                if (a != b) return a > b
            }
            return false
        }
    }
}
