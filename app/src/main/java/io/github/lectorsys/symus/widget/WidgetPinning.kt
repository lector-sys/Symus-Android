package io.github.lectorsys.symus.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * Adding a widget from inside the app.
 *
 * The launcher's own widget tray is the usual route and stays the primary one;
 * this exists because a lot of people have never opened that tray, and because
 * "add it for me" is the only route that works on a launcher whose tray is
 * hidden behind a long-press most users never discover.
 *
 * Honest about its limits: [isSupported] is false on Android 7 and below, where
 * the API does not exist, and false on launchers that decline to implement it —
 * a surprising number of third-party ones do. The caller is expected to hide or
 * disable the entry point rather than offer a button that does nothing.
 */
object WidgetPinning {

    enum class Kind { NOW_PLAYING, PLAYLIST }

    /** Whether this device's current launcher will accept a pin request at all. */
    fun isSupported(context: Context): Boolean = runCatching {
        AppWidgetManager.getInstance(context).isRequestPinAppWidgetSupported
    }.getOrDefault(false)

    /**
     * Asks the launcher to place [kind] on the home screen.
     *
     * Returns false when the request could not even be made. A true return
     * means the launcher was asked, not that the user said yes — the system
     * shows its own confirmation and never tells the app the answer, which is
     * why nothing here waits for a result.
     */
    fun request(context: Context, kind: Kind): Boolean = runCatching {
        val manager = AppWidgetManager.getInstance(context)
        if (!manager.isRequestPinAppWidgetSupported) return false

        val provider = ComponentName(
            context,
            when (kind) {
                Kind.NOW_PLAYING -> SymusWidgetReceiver::class.java
                Kind.PLAYLIST -> PlaylistWidgetReceiver::class.java
            }
        )

        // The callback fires once the widget actually lands. Used to open the
        // configuration screen for the playlist shortcut, which is useless
        // until a playlist has been chosen — the launcher does not run a
        // configure activity for a pinned widget on every version, so the app
        // arranges it rather than assuming.
        val callback = PendingIntent.getActivity(
            context,
            kind.ordinal,
            Intent(context, WidgetConfigActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        manager.requestPinAppWidget(provider, null, callback)
    }.getOrDefault(false)

    /**
     * Widget ids currently placed for [kind], newest last.
     *
     * Used by the in-app list so a placed widget can be reconfigured without
     * going near the launcher — which on Android 11 and below is the only way
     * to reconfigure one at all.
     */
    fun placed(context: Context, kind: Kind): IntArray = runCatching {
        AppWidgetManager.getInstance(context).getAppWidgetIds(
            ComponentName(
                context,
                when (kind) {
                    Kind.NOW_PLAYING -> SymusWidgetReceiver::class.java
                    Kind.PLAYLIST -> PlaylistWidgetReceiver::class.java
                }
            )
        )
    }.getOrDefault(IntArray(0))

    /** Opens the configuration screen for an already-placed widget. */
    fun configure(context: Context, widgetId: Int) {
        runCatching {
            context.startActivity(
                Intent(context, WidgetConfigActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            )
        }
    }
}
