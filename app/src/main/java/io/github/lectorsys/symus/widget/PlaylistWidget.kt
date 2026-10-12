package io.github.lectorsys.symus.widget

import android.content.Context
import androidx.compose.runtime.Composable
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
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider

/**
 * A one-tap shortcut to a playlist.
 *
 * Holds the playlist's identity and its cover in its own Glance state, so three
 * of these on a home screen are three different playlists rather than three
 * views of one global selection — the per-instance configuration is the whole
 * point of the widget.
 *
 * It never resolves the playlist itself. The title and count it shows are what
 * the app last told it; the tap hands the id to the app, which is where the
 * database is. That is why a deleted playlist has to be handled as a state here
 * ([WidgetKeys.playlistMissing]) rather than as a failed lookup at tap time.
 */
class PlaylistWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Responsive(
        setOf(DpSize(110.dp, 110.dp), DpSize(180.dp, 48.dp))
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { Shortcut() }
    }
}

class PlaylistWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PlaylistWidget()
}

/** Wider than this and the shortcut lays out as a row instead of a tile. */
private val ROW_ABOVE_RATIO = 1.6f

@Composable
private fun Shortcut() {
    val context = LocalContext.current
    val prefs = currentState<Preferences>()
    val snapshot = WidgetSnapshot.read(prefs)
    val config = WidgetConfig.read(prefs)
    val skin = resolveSkin(config.appearance, config.opacity, snapshot)
    val cover = WidgetArt.decode(config.playlistCoverFile)

    val size = LocalSize.current
    val asRow = size.width / size.height > ROW_ABOVE_RATIO

    val clickable = if (config.playlistId != null && !config.playlistMissing) {
        GlanceModifier.clickable(
            WidgetIntents.openPlaylist(context, config.playlistId, config.playOnTap)
        )
    } else {
        // A shortcut to a playlist that no longer exists still opens the app
        // rather than doing nothing, because a tap that does nothing reads as a
        // broken widget.
        GlanceModifier.clickable(WidgetIntents.openNowPlaying(context))
    }

    Box(modifier = GlanceModifier.fillMaxSize().cornerRadius(24.dp).then(clickable)) {
        if (cover != null && !asRow) {
            Image(
                provider = ImageProvider(cover),
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

        if (asRow) {
            RowShortcut(config, skin, cover)
        } else {
            TileShortcut(config, skin, overArt = cover != null)
        }
    }
}

@Composable
private fun RowShortcut(config: WidgetConfig, skin: WidgetSkin, cover: android.graphics.Bitmap?) {
    Row(
        modifier = GlanceModifier.fillMaxSize().padding(horizontal = 10.dp),
        verticalAlignment = Alignment.Vertical.CenterVertically
    ) {
        if (cover != null) {
            Image(
                provider = ImageProvider(cover),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = GlanceModifier.size(40.dp).cornerRadius(10.dp)
            )
            Spacer(modifier = GlanceModifier.width(10.dp))
        }
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                text = config.playlistTitle ?: "Choose a playlist",
                maxLines = 1,
                style = TextStyle(
                    color = ColorProvider(skin.ink),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            )
            Text(
                text = subtitle(config),
                maxLines = 1,
                style = TextStyle(color = ColorProvider(skin.muted), fontSize = 11.sp)
            )
        }
        if (config.playOnTap && !config.playlistMissing) {
            Image(
                provider = ImageProvider(androidx.media3.session.R.drawable.media3_icon_play),
                contentDescription = "Play ${config.playlistTitle ?: "playlist"}",
                colorFilter = ColorFilter.tint(ColorProvider(skin.accentOver(cover != null))),
                modifier = GlanceModifier.size(36.dp).padding(7.dp)
            )
        }
    }
}

@Composable
private fun TileShortcut(config: WidgetConfig, skin: WidgetSkin, overArt: Boolean) {
    Column(
        modifier = GlanceModifier.fillMaxSize().padding(14.dp),
        verticalAlignment = Alignment.Vertical.Bottom
    ) {
        if (config.playOnTap && !config.playlistMissing) {
            Image(
                provider = ImageProvider(androidx.media3.session.R.drawable.media3_icon_play),
                contentDescription = null,
                colorFilter = ColorFilter.tint(ColorProvider(skin.accentOver(overArt))),
                modifier = GlanceModifier.size(28.dp).padding(4.dp)
            )
            Spacer(modifier = GlanceModifier.height(4.dp))
        }
        Text(
            text = config.playlistTitle ?: "Choose a playlist",
            maxLines = 2,
            style = TextStyle(
                color = ColorProvider(skin.ink),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )
        )
        Text(
            text = subtitle(config),
            maxLines = 1,
            style = TextStyle(color = ColorProvider(skin.muted), fontSize = 11.sp)
        )
    }
}

/**
 * The line under the title.
 *
 * Says what happened rather than going blank: a playlist the user deleted is a
 * fact the widget knows and should admit to, since it is the only explanation
 * for why tapping it no longer plays anything.
 */
private fun subtitle(config: WidgetConfig): String = when {
    config.playlistId == null -> "Not configured"
    config.playlistMissing -> "Playlist deleted"
    config.playlistCount == 1 -> "1 track"
    config.playlistCount > 0 -> "${config.playlistCount} tracks"
    else -> if (config.playOnTap) "Tap to play" else "Tap to open"
}
