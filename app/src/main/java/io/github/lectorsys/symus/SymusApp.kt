package io.github.lectorsys.symus

import android.app.Application
import androidx.media3.common.util.UnstableApi
import dagger.hilt.android.HiltAndroidApp
import io.github.lectorsys.symus.data.LibraryRepository
import io.github.lectorsys.symus.media.Downloads
import io.github.lectorsys.symus.media.Extractor
import io.github.lectorsys.symus.media.Playback
import io.github.lectorsys.symus.widget.WidgetUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import javax.inject.Inject

@UnstableApi
@HiltAndroidApp
class SymusApp : Application() {

    @Inject lateinit var client: OkHttpClient
    @Inject lateinit var library: LibraryRepository
    @Inject lateinit var scope: CoroutineScope

    /**
     * Injected to exist, not to be called from here.
     *
     * Both start work in their constructors — the controller connects so a
     * restored session is on screen before anything is tapped, and the download
     * queue drains whatever a previous process left behind. Creating them lazily
     * at first use would mean neither happened until the user went looking.
     */
    @Inject lateinit var playback: Playback
    @Inject lateinit var downloads: Downloads

    @Inject lateinit var widgets: WidgetUpdater

    override fun onCreate() {
        super.onCreate()
        // Static, global, and required before any extractor call — including one
        // made from a service the system started without an Activity, which is
        // why it happens here and not in the first screen.
        Extractor.init(client)

        scope.launch {
            // Search results share a table with the library, so without this the
            // database is a cache that only ever grows.
            runCatching { library.pruneCache() }
        }

        // Playlist shortcuts follow the library for as long as the process is
        // alive. Renames, added tracks and deletions all arrive here as one
        // "playlists changed" and are reconciled against each widget's own
        // stored playlist id. Nothing happens at all when no shortcut has been
        // placed, which is the common case.
        scope.launch {
            library.playlistSummaries().collect { summaries ->
                runCatching { widgets.syncPlaylists(summaries) }
            }
        }
    }
}
