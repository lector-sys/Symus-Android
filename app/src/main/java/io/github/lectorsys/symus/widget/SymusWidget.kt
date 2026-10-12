package io.github.lectorsys.symus.widget

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionSendBroadcast
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaButtonReceiver
import dagger.hilt.android.AndroidEntryPoint
import io.github.lectorsys.symus.media.Playback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * The now-playing widget.
 *
 * Renders from Glance's persisted state and nothing else — no controller, no
 * repository, no injection. That is not minimalism for its own sake: the
 * launcher asks for this after a reboot, with no app process anywhere, and
 * anything that needed the app running would draw an empty rectangle. See
 * [WidgetKeys] for why the snapshot lives where it does.
 */
class SymusWidget : GlanceAppWidget() {

    /**
     * Two layouts rather than one that stretches.
     *
     * A 4x1 strip and a 4x2 card want genuinely different compositions — the
     * strip has no room for artwork behind text and the card looks empty
     * without it — and Responsive lets the launcher pick without the widget
     * measuring anything at runtime.
     */
    override val sizeMode = SizeMode.Responsive(
        setOf(DpSize(180.dp, 48.dp), DpSize(180.dp, 110.dp))
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { NowPlaying() }
    }
}

/**
 * What the system instantiates; the manifest points at this, not at the widget.
 *
 * onUpdate is the only moment the app is told a widget has appeared — placed,
 * resized, restored after a reboot. Without the push below, a widget added
 * while a queue is already loaded renders "Nothing playing" over music that is
 * audibly coming out of the phone, and stays that way until the next track
 * change happens to write to it.
 *
 * Asking the controller rather than the service because this is a broadcast:
 * there may be no service, and connecting is what makes a widget placed after a
 * reboot show the restored queue instead of a blank.
 */
@AndroidEntryPoint
class SymusWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SymusWidget()

    @Inject lateinit var updater: WidgetUpdater
    @Inject lateinit var playback: Playback
    @Inject lateinit var scope: CoroutineScope

    override fun onUpdate(
        context: Context,
        appWidgetManager: android.appwidget.AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        scope.launch {
            // Waits for a track, not merely for a connection. The controller
            // reports connected the moment it binds, which is before the
            // service has restored its queue — pushing at that point sends an
            // empty snapshot. Nothing is pushed at all if none arrives:
            // whatever the widget already holds is better than blank.
            val state = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                playback.state.first { it.connected && it.current != null }
            } ?: return@launch
            runCatching { updater.push(snapshotOf(state), state.current?.artworkUrl) }
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 4_000L
    }
}

/** Below this the widget is a strip and drops the artwork backdrop. */
private val COMPACT_BELOW = 96.dp

@Composable
private fun NowPlaying() {
    val context = LocalContext.current
    val prefs = currentState<Preferences>()
    val snapshot = WidgetSnapshot.read(prefs)
    val config = WidgetConfig.read(prefs)
    val skin = resolveSkin(config.appearance, config.opacity, snapshot)

    // Decoded once per artwork rather than once per recomposition, and never on
    // the compact layout, which has no room to show it: a bitmap decoded here
    // is a bitmap sent over IPC to the launcher whether it is drawn or not.
    val compact = LocalSize.current.height < COMPACT_BELOW
    val art = if (compact) null else WidgetArt.decode(snapshot.artworkFile)
    val thumb = if (compact) WidgetArt.decode(snapshot.artworkFile) else null

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .cornerRadius(24.dp)
            .clickable(openNowPlaying(context))
    ) {
        if (art != null) {
            Image(
                provider = ImageProvider(art),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = GlanceModifier.fillMaxSize()
            )
            Box(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(skin.background.copy(alpha = skin.scrimOverArt))
            ) {}
        } else {
            Box(modifier = GlanceModifier.fillMaxSize().background(skin.background)) {}
        }

        if (compact) {
            CompactRow(context, snapshot, skin, thumb, overArt = false)
        } else {
            TallCard(context, snapshot, skin, overArt = art != null)
        }
    }
}

/** The 4x1 strip: thumbnail, one line of text, transport. */
@Composable
private fun CompactRow(
    context: Context,
    snapshot: WidgetSnapshot,
    skin: WidgetSkin,
    thumb: Bitmap?,
    overArt: Boolean
) {
    Row(
        modifier = GlanceModifier.fillMaxSize().padding(horizontal = 10.dp),
        verticalAlignment = Alignment.Vertical.CenterVertically
    ) {
        if (thumb != null) {
            Image(
                provider = ImageProvider(thumb),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = GlanceModifier.size(40.dp).cornerRadius(10.dp)
            )
            Spacer(modifier = GlanceModifier.width(10.dp))
        }
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                text = snapshot.title ?: "Nothing playing",
                maxLines = 1,
                style = TextStyle(
                    color = ColorProvider(skin.ink),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            )
            if (!snapshot.isEmpty) {
                Text(
                    text = snapshot.artist ?: "Unknown artist",
                    maxLines = 1,
                    style = TextStyle(color = ColorProvider(skin.muted), fontSize = 11.sp)
                )
            }
        }
        Transport(context, snapshot, skin, compact = true, overArt = overArt)
    }
}

/** The 4x2 and larger card: artwork behind, text and transport over it. */
@Composable
private fun TallCard(
    context: Context,
    snapshot: WidgetSnapshot,
    skin: WidgetSkin,
    overArt: Boolean
) {
    Column(
        modifier = GlanceModifier.fillMaxSize().padding(16.dp),
        verticalAlignment = Alignment.Vertical.Bottom
    ) {
        Text(
            text = snapshot.title ?: "Nothing playing",
            maxLines = 2,
            style = TextStyle(
                color = ColorProvider(skin.ink),
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )
        )
        Text(
            text = snapshot.artist
                ?: if (snapshot.isEmpty) "Tap to open SYmuS" else "Unknown artist",
            maxLines = 1,
            style = TextStyle(color = ColorProvider(skin.muted), fontSize = 12.sp)
        )
        Spacer(modifier = GlanceModifier.height(8.dp))
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.Vertical.CenterVertically
        ) {
            Transport(context, snapshot, skin, compact = false, overArt = overArt)
        }
    }
}

@Composable
private fun Transport(
    context: Context,
    snapshot: WidgetSnapshot,
    skin: WidgetSkin,
    compact: Boolean,
    overArt: Boolean
) {
    val playing = snapshot.playingNow
    Control(
        context = context,
        icon = androidx.media3.session.R.drawable.media3_icon_previous,
        label = "Previous track",
        intent = WidgetControlReceiver.intent(context, WidgetControlReceiver.ACTION_PREVIOUS),
        tint = skin.ink,
        enabled = snapshot.hasPrevious,
        compact = compact
    )
    Spacer(modifier = GlanceModifier.width(if (compact) 2.dp else 8.dp))
    Control(
        context = context,
        // One toggle rather than separate play and pause buttons: the snapshot
        // can be out of date, and a button showing "play" while audio is coming
        // out of the phone should still stop it. PLAY_PAUSE asks the session,
        // which knows.
        icon = if (playing) {
            androidx.media3.session.R.drawable.media3_icon_pause
        } else {
            androidx.media3.session.R.drawable.media3_icon_play
        },
        label = if (playing) "Pause" else "Play",
        // Still the media button path: this is the one command that has to work
        // with no process and no service, and Media3's receiver is built for it.
        intent = mediaButton(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE),
        // The one place colour is spent, and only when there is something to
        // press: a coloured play button over an empty queue is an invitation
        // to a dead end.
        tint = if (snapshot.isEmpty) skin.ink else skin.accentOver(overArt),
        enabled = !snapshot.isEmpty,
        compact = compact
    )
    Spacer(modifier = GlanceModifier.width(if (compact) 2.dp else 8.dp))
    Control(
        context = context,
        icon = androidx.media3.session.R.drawable.media3_icon_next,
        label = "Next track",
        intent = WidgetControlReceiver.intent(context, WidgetControlReceiver.ACTION_NEXT),
        tint = skin.ink,
        enabled = snapshot.hasNext,
        compact = compact
    )
}

/**
 * One transport button.
 *
 * [label] is the content description and is never null: for some users the
 * widget is the only control they reach, and three unlabelled images are three
 * identical "button"s to a screen reader.
 */
@Composable
private fun Control(
    context: Context,
    icon: Int,
    label: String,
    intent: Intent,
    tint: Color,
    enabled: Boolean,
    compact: Boolean
) {
    // 40dp even in the strip: below Android's 40dp minimum a control on a home
    // screen is a coin toss, and the strip has the width to spare.
    val base = GlanceModifier.size(40.dp).padding(if (compact) 9.dp else 8.dp)
    Image(
        provider = ImageProvider(icon),
        contentDescription = label,
        colorFilter = ColorFilter.tint(ColorProvider(if (enabled) tint else tint.copy(alpha = 0.3f))),
        modifier = if (enabled) {
            base.clickable(actionSendBroadcast(intent))
        } else {
            base
        }
    )
}

/**
 * A media button press, addressed to Media3's own receiver.
 *
 * Deliberately not a service start. A widget tap arrives with the app in the
 * background and often with no process at all; starting a foreground service
 * from there is restricted, and it would promise a notification the player is
 * not yet ready to show. The media button path is the one Android reserves for
 * exactly this, and it is the same path a headset button takes — so it also
 * works when the service has to be brought back from nothing, which after a
 * night on the home screen is the normal case.
 */
private fun mediaButton(context: Context, keyCode: Int): Intent =
    Intent(Intent.ACTION_MEDIA_BUTTON).apply {
        component = ComponentName(context, MediaButtonReceiver::class.java)
        putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
    }
