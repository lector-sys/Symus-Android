package io.github.lectorsys.symus

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.media3.common.util.UnstableApi
import dagger.hilt.android.AndroidEntryPoint
import io.github.lectorsys.symus.ui.SymusRoot
import io.github.lectorsys.symus.library.SpotifyAdapter
import io.github.lectorsys.symus.ui.SymusViewModel
import io.github.lectorsys.symus.widget.WidgetIntents
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import javax.inject.Inject

/**
 * The only Activity.
 *
 * It owns no playback state of any kind: the service owns the player, the
 * application owns the controller, and this owns a window. That is what makes
 * leaving the app and coming back a non-event.
 */
@UnstableApi
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val model: SymusViewModel by viewModels()

    /**
     * Injected here rather than reached through a ViewModel because the OAuth
     * callback arrives as an Intent, which is an Activity concern and can land
     * before any screen that would own it exists.
     */
    @Inject
    lateinit var spotify: SpotifyAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Drawn behind the system bars; every screen pads for them itself.
        enableEdgeToEdge()
        handleWidgetIntent(intent)
        handleOAuthCallback(intent)
        setContent { SymusRoot(model) }
    }

    /**
     * The launch mode is singleTask, so a second tap on a widget arrives here
     * rather than in onCreate. Both paths go through the same handler; only
     * routing one of them is the classic version of "the widget works once".
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleWidgetIntent(intent)
        handleOAuthCallback(intent)
    }

    /**
     * Finishes a Spotify sign-in.
     *
     * The user is sent to Spotify in a browser and comes back to
     * `symus://spotify-callback`. An `error` instead of a `code` is the user
     * declining, which is a normal outcome and not a failure to report.
     */
    private fun handleOAuthCallback(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "symus" || data.host != "spotify-callback") return
        val code = data.getQueryParameter("code") ?: return
        lifecycleScope.launch {
            val result = spotify.completeSignIn(code)
            model.say(
                if (result.isSuccess) "Signed in to Spotify."
                else result.exceptionOrNull()?.message ?: "That Spotify sign-in did not complete."
            )
        }
    }

    private fun handleWidgetIntent(intent: Intent?) {
        val destination = intent?.getStringExtra(WidgetIntents.EXTRA_DESTINATION) ?: return
        model.openFromWidget(
            destination = destination,
            playlistId = intent.getLongExtra(WidgetIntents.EXTRA_PLAYLIST_ID, 0L)
        )
    }
}
