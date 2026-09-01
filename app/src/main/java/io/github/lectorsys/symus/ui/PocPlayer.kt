package io.github.lectorsys.symus.ui

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A single ExoPlayer, just enough to prove extraction produces something
 * playable. Replaced in the next step by the MediaSessionService — this one has
 * no notification, no session and no lifecycle beyond the app process.
 *
 * Audio focus is on from the start rather than bolted on later: a player that
 * talks over a phone call is not a bug you find at the end.
 */
@Singleton
class PocPlayer @Inject constructor(
    @ApplicationContext context: Context,
    client: OkHttpClient
) {
    private val player: ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(
            // The same OkHttp client the extractor uses, so the connection to
            // googlevideo is already warm when playback starts.
            DefaultMediaSourceFactory(OkHttpDataSource.Factory(client))
        )
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .setUsage(C.USAGE_MEDIA)
                .build(),
            /* handleAudioFocus = */ true
        )
        .build()

    fun play(url: String) {
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
        player.play()
    }

    fun togglePause() {
        if (player.isPlaying) player.pause() else player.play()
    }

    fun release() = player.release()
}
