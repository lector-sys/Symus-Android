package io.github.lectorsys.symus.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.media3.common.util.UnstableApi
import dagger.hilt.android.AndroidEntryPoint
import io.github.lectorsys.symus.MainActivity
import io.github.lectorsys.symus.R
import io.github.lectorsys.symus.data.DownloadState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Keeps the process alive while downloads are running, and says so.
 *
 * The work itself lives in [Downloads]; this exists purely for the lifetime and
 * the notification. Without a foreground service Android is free to kill the
 * process the moment the app is backgrounded, which is exactly when someone
 * would start a download and put their phone in a pocket.
 *
 * It stops itself the moment the queue is empty, so it is never a service that
 * lingers with nothing to do.
 */
@UnstableApi
@AndroidEntryPoint
class DownloadService : android.app.Service() {

    @Inject lateinit var downloads: Downloads

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, notification("Preparing downloads", 0, 0))

        scope.launch {
            downloads.queue.combine(downloads.progress) { queue, progress -> queue to progress }
                .collect { (queue, progress) ->
                    val live = queue.filter { it.state != DownloadState.FAILED }
                    if (live.isEmpty()) {
                        stopSelf()
                        return@collect
                    }
                    val current = live.firstOrNull { it.state == DownloadState.RUNNING }
                        ?: live.first()
                    val at = progress[current.videoId]
                    // Says what is actually happening. A queue parked on
                    // "wi-fi only" is waiting, not downloading, and a
                    // notification that claims otherwise reads as a stuck
                    // download rather than a deliberate one.
                    val waiting = current.state != DownloadState.RUNNING
                    notify(
                        title = when {
                            waiting -> current.error ?: "Waiting to download"
                            live.size == 1 -> "Downloading 1 track"
                            else -> "Downloading ${live.size} tracks"
                        },
                        bytes = at?.bytes ?: current.bytes,
                        total = if (waiting) 0 else (at?.total ?: current.total)
                    )
                }
        }
    }

    override fun onBind(intent: Intent): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notify(title: String, bytes: Long, total: Long) {
        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID, notification(title, bytes, total))
    }

    private fun notification(title: String, bytes: Long, total: Long): Notification {
        val open = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val percent = if (total > 0) ((bytes * 100) / total).toInt().coerceIn(0, 100) else 0
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle(title)
            .setContentText(if (total > 0) "$percent%" else "Waiting…")
            .setSmallIcon(R.drawable.ic_download)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent, total <= 0)
            .setSilent(true)
            .build()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Progress for tracks being saved for offline play"
                setShowBadge(false)
            }
        )
    }

    companion object {
        private const val CHANNEL = "symus.downloads"
        private const val NOTIFICATION_ID = 1841

        fun start(context: Context) {
            val intent = Intent(context, DownloadService::class.java)
            // Failures are swallowed on purpose: a background-start restriction
            // must not take down whatever the user was actually doing.
            runCatching { context.startForegroundService(intent) }
        }
    }
}
