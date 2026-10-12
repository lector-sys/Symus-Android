package io.github.lectorsys.symus.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.draw.blur
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.NavType
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import coil3.compose.AsyncImage
import io.github.lectorsys.symus.data.ActiveTheme
import io.github.lectorsys.symus.ui.assistant.AssistantScreen
import io.github.lectorsys.symus.ui.stats.ListeningScreen
import io.github.lectorsys.symus.ui.importer.ImportScreen
import io.github.lectorsys.symus.ui.explore.ChartsScreen
import io.github.lectorsys.symus.ui.explore.ExploreScreen
import io.github.lectorsys.symus.ui.explore.MoodsScreen
import io.github.lectorsys.symus.ui.explore.PodcastsScreen
import io.github.lectorsys.symus.ui.home.HomeScreen
import io.github.lectorsys.symus.ui.library.AlbumScreen
import io.github.lectorsys.symus.ui.library.ArtistScreen
import io.github.lectorsys.symus.ui.library.DownloadsScreen
import io.github.lectorsys.symus.ui.library.GenreScreen
import io.github.lectorsys.symus.ui.library.HistoryScreen
import io.github.lectorsys.symus.ui.library.LibraryScreen
import io.github.lectorsys.symus.ui.library.LikedScreen
import io.github.lectorsys.symus.ui.library.PlaylistScreen
import io.github.lectorsys.symus.ui.library.SavedScreen
import io.github.lectorsys.symus.ui.player.MiniPlayer
import io.github.lectorsys.symus.ui.player.NowPlayingScreen
import io.github.lectorsys.symus.ui.player.rememberArtworkAccent
import io.github.lectorsys.symus.ui.search.SearchScreen
import io.github.lectorsys.symus.ui.settings.ModelScreen
import io.github.lectorsys.symus.ui.settings.SettingsScreen
import io.github.lectorsys.symus.ui.sheets.TrackSheetHost
import io.github.lectorsys.symus.ui.theme.AppearanceScreen
import io.github.lectorsys.symus.ui.theme.SymusTheme

private data class Destination(val route: String, val label: String, val icon: ImageVector)

/**
 * The sides of a display cutout that content has to avoid.
 *
 * Only the horizontal ones: the top is already covered by the status bar
 * padding every screen applies, and taking the cutout's top inset here as well
 * would push every header down by a second status bar's worth.
 */
private val cutoutSides
    @Composable get() = WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal)

/**
 * Four destinations, and nothing else in the bar.
 *
 * Everything the app can show is reachable from one of these — downloads,
 * podcasts, moods, charts and settings all hang off a screen that already has a
 * reason to mention them, rather than competing for a fifth slot that would make
 * every tap target smaller.
 */
private val destinations = listOf(
    Destination("home", "Home", Icons.Rounded.Home),
    Destination("search", "Search", Icons.Rounded.Search),
    Destination("explore", "Explore", Icons.Rounded.Explore),
    Destination("library", "Library", Icons.Rounded.LibraryMusic)
)

@UnstableApi
@Composable
fun SymusRoot(model: SymusViewModel) {
    val settings by model.settings.collectAsStateWithLifecycle()
    val playback by model.playbackState.collectAsStateWithLifecycle()
    val theme by model.activeTheme.collectAsStateWithLifecycle()
    val artworkAccent = rememberArtworkAccent(playback.current?.artworkUrl, settings.artworkColors)

    // A theme the user built from a photograph is a deliberate choice and wins
    // over the colour sampled from whatever happens to be playing. With no such
    // theme the artwork keeps driving the accent, as it always did.
    val accent = if (theme.isCustom) theme.accent else artworkAccent

    SymusTheme(choice = settings.theme, accent = accent) {
        // Mirrored out to the home screen whenever it changes, so a widget set
        // to follow the app is following what is actually on screen rather than
        // what was on screen when it was placed.
        // The scheme's primary, not the raw artwork colour: the widget uses it
        // to tint a control, and the raw value is a backdrop tone that is
        // invisible as a foreground.
        val widgetAccent = MaterialTheme.colorScheme.primary
        val surface = MaterialTheme.colorScheme.background
        LaunchedEffect(widgetAccent, surface) { model.mirrorTheme(widgetAccent, surface) }

        val nav = rememberNavController()
        val snackbar = remember { SnackbarHostState() }

        LaunchedEffect(model) {
            model.messages.collect { message ->
                // One at a time, newest wins: a queue of stale messages about
                // tracks that have already been skipped is noise.
                snackbar.currentSnackbarData?.dismiss()
                snackbar.showSnackbar(message)
            }
        }

        NotificationPermission(playing = playback.current != null)

        Wallpaper(theme) {
        TrackSheetHost(
            model = model,
            onNavigate = { nav.navigate(it) },
            onRemoveFromPlaylist = { id, track -> model.removeFromPlaylist(id, track) }
        ) {
            val entry by nav.currentBackStackEntryAsState()
            val route = entry?.destination?.route
            val fullScreen = route == "nowplaying"

            // A tap on a home-screen widget lands here. Consumed once, so a
            // rotation does not re-navigate to somewhere the user has since
            // left.
            val widgetRoute by model.widgetRoute.collectAsStateWithLifecycle()
            LaunchedEffect(widgetRoute) {
                widgetRoute?.let {
                    nav.navigate(it)
                    model.widgetRouteHandled()
                }
            }

            Scaffold(
                // Transparent when a wallpaper is showing, or the Scaffold's
                // own opaque fill would paint straight over it.
                containerColor = if (theme.hasWallpaper) {
                    Color.Transparent
                } else {
                    MaterialTheme.colorScheme.background
                },
                snackbarHost = { SnackbarHost(snackbar) },
                bottomBar = {
                    if (!fullScreen) {
                        // Horizontal cutout only. In landscape on a device with a
                        // camera cutout the mini player's artwork and the first
                        // navigation item otherwise sit underneath it; in portrait
                        // this inset is zero and nothing moves.
                        Column(Modifier.windowInsetsPadding(cutoutSides)) {
                            MiniPlayer(
                                state = playback,
                                accent = accent,
                                onOpen = { nav.navigate("nowplaying") },
                                onTogglePlay = model.playback::togglePlay,
                                onNext = model.playback::next
                            )
                            BottomBar(nav = nav, current = route)
                        }
                    }
                }
            ) { padding ->
                NavHost(
                    navController = nav,
                    startDestination = "home",
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = padding.calculateBottomPadding())
                        // Every screen's own statusBarsPadding() handles the top;
                        // this is the one inset none of them can see, and the
                        // reason a landscape title used to start under the camera.
                        //
                        // The player is exempt because it insets itself: its
                        // artwork gradient is meant to reach the edge of the
                        // screen, and padding it here would leave a black band
                        // down the cutout side instead.
                        .windowInsetsPadding(if (fullScreen) WindowInsets(0) else cutoutSides),
                    // Short and horizontal: enough to say "this is a new page",
                    // not enough to be waited on.
                    enterTransition = {
                        slideIntoContainer(
                            AnimatedContentTransitionScope.SlideDirection.Start,
                            tween(220)
                        ) + fadeIn(tween(220))
                    },
                    exitTransition = { fadeOut(tween(140)) },
                    popEnterTransition = { fadeIn(tween(160)) },
                    popExitTransition = {
                        slideOutOfContainer(
                            AnimatedContentTransitionScope.SlideDirection.End,
                            tween(220)
                        ) + fadeOut(tween(220))
                    }
                ) {
                    composable("home") {
                        HomeScreen(model = model, onNavigate = { nav.navigate(it) })
                    }
                    composable("search") {
                        SearchScreen(model = model, onNavigate = { nav.navigate(it) })
                    }
                    composable("explore") {
                        ExploreScreen(model = model, onNavigate = { nav.navigate(it) })
                    }
                    composable("library") {
                        LibraryScreen(model = model, onNavigate = { nav.navigate(it) })
                    }
                    composable("nowplaying") {
                        NowPlayingScreen(
                            model = model,
                            onClose = { nav.popBackStack() },
                            onNavigate = { nav.navigate(it) }
                        )
                    }
                    composable("liked") { LikedScreen(model, nav::popBackStack) }
                    composable("saved") { SavedScreen(model, nav::popBackStack) }
                    composable("downloads") { DownloadsScreen(model, nav::popBackStack) }
                    composable("history") { HistoryScreen(model, nav::popBackStack) }
                    composable("moods") {
                        MoodsScreen(
                            model = model,
                            onBack = nav::popBackStack,
                            onNavigate = { nav.navigate(it) }
                        )
                    }
                    composable("charts") {
                        ChartsScreen(
                            model = model,
                            onBack = nav::popBackStack,
                            onNavigate = { nav.navigate(it) }
                        )
                    }
                    composable("podcasts") { PodcastsScreen(model, nav::popBackStack) }
                    composable("settings") {
                        SettingsScreen(
                            model = model,
                            onBack = nav::popBackStack,
                            onNavigate = { nav.navigate(it) }
                        )
                    }
                    composable("import") {
                        ImportScreen(
                            onBack = nav::popBackStack,
                            onOpenPlaylist = { nav.navigate("playlist/$it") }
                        )
                    }
                    composable("assistant") {
                        AssistantScreen(model = model, onBack = nav::popBackStack)
                    }
                    composable("listening") {
                        ListeningScreen(model = model, onBack = nav::popBackStack)
                    }
                    composable("model") {
                        ModelScreen(onBack = nav::popBackStack)
                    }
                    composable("appearance") {
                        AppearanceScreen(
                            model = hiltViewModel(),
                            onBack = nav::popBackStack
                        )
                    }
                    composable("playlist/{id}") { backStack ->
                        val id = backStack.arguments?.getString("id")?.toLongOrNull() ?: 0L
                        PlaylistScreen(model, id, nav::popBackStack)
                    }
                    composable("genre/{name}") { backStack ->
                        GenreScreen(
                            model = model,
                            genre = decode(backStack.arguments?.getString("name")),
                            onBack = nav::popBackStack
                        )
                    }
                    composable("artist/{name}") { backStack ->
                        ArtistScreen(
                            model = model,
                            artist = decode(backStack.arguments?.getString("name")),
                            onBack = nav::popBackStack,
                            onNavigate = { nav.navigate(it) }
                        )
                    }
                    composable(
                        route = "album/{name}?artist={artist}",
                        arguments = listOf(
                            navArgument("artist") {
                                type = NavType.StringType
                                nullable = true
                                defaultValue = null
                            }
                        )
                    ) { backStack ->
                        AlbumScreen(
                            model = model,
                            album = decode(backStack.arguments?.getString("name")),
                            artist = backStack.arguments?.getString("artist")?.let(::decode),
                            onBack = nav::popBackStack
                        )
                    }
                }
            }
        }
        }
    }
}

/**
 * The photo behind the app, when a theme asks for one.
 *
 * Three things have to be true at once or this is unusable: the picture has to
 * be visible enough to be worth having, the app's own text has to stay
 * readable over it, and neither can depend on what the photograph happens to
 * contain. The scrim does the work — its floor is set in the editor, not here —
 * and the blur is a bonus on Android 12+ and silently absent below it, where
 * RenderEffect does not exist.
 *
 * With no wallpaper this is a plain Box and costs nothing.
 */
@Composable
private fun Wallpaper(theme: ActiveTheme, content: @Composable () -> Unit) {
    // The shape of this composition never changes, and that is not a style
    // preference. [content] is the entire app — the navigation host and every
    // screen's state. Emitting it at a different position, or skipping the
    // wrapper entirely when there is no wallpaper, moves its slot; Compose
    // disposes and rebuilds the subtree; every DisposableEffect in the app
    // fires its cleanup. The first version of this did exactly that, and
    // turning the wallpaper toggle on tore down the very editor that was
    // showing the preview, which promptly cancelled it. So: always a Box,
    // always two children, and only what is drawn inside the first one varies.
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            val file = theme.wallpaper
            if (file != null && theme.hasWallpaper) {
                AsyncImage(
                    model = file,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            // RenderEffect is Android 12+. Below it the dim
                            // alone carries the readability, which is why the
                            // dim has a floor and the blur does not.
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                                theme.blur > 0f
                            ) {
                                Modifier.blur((theme.blur * MAX_BLUR).dp)
                            } else {
                                Modifier
                            }
                        )
                )
                // A flat scrim cannot serve a photo that is dark at the top and
                // bright at the bottom. The ink colour is chosen once for the
                // whole image, so on the half that disagrees the chrome goes
                // dark-on-dark — which is exactly what a dusk photo does to the
                // header. The floor stays flat, and the bands where chrome
                // actually sits get more on top of it.
                val base = MaterialTheme.colorScheme.background
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(base.copy(alpha = theme.dim))
                        .background(
                            Brush.verticalGradient(
                                0f to base.copy(alpha = EDGE_SCRIM),
                                0.18f to Color.Transparent,
                                0.78f to Color.Transparent,
                                1f to base.copy(alpha = EDGE_SCRIM)
                            )
                        )
                )
            }
        }
        content()
    }
}

/** Blur radius at full strength. Beyond this the photo is just a colour. */
private const val MAX_BLUR = 40

/**
 * Extra scrim at the very top and bottom, on top of the theme's own dim.
 *
 * Those two bands are where the chrome lives — the screen header, the bottom
 * bar, the mini player — and they are the only places a wallpaper has to lose.
 * A dusk photograph is dark at the top and bright at the bottom, and one ink
 * colour chosen for the whole image is wrong on one half of it; this puts a
 * floor under the contrast in both bands without flattening the middle, which
 * is the part the user chose the photo for.
 */
private const val EDGE_SCRIM = 0.38f

/**
 * Below this window height the navigation bar drops its labels and shrinks.
 *
 * In landscape on a phone the mini player and a full-height navigation bar
 * together took roughly a third of the screen, which left the library showing
 * about one and a half rows. Compacting the bar gives that back. The icons keep
 * their content descriptions, so nothing is lost to a screen reader — only to
 * sighted users who can see the selected tab highlighted anyway.
 *
 * ponytail: a NavigationRail down the side is the better answer in landscape
 * and a larger change; this is the version that fits in the space it saves.
 */
private const val COMPACT_HEIGHT_DP = 480

@Composable
private fun BottomBar(nav: NavHostController, current: String?) {
    val compact = LocalConfiguration.current.screenHeightDp < COMPACT_HEIGHT_DP
    NavigationBar(
        modifier = if (compact) Modifier.height(56.dp) else Modifier,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 0.dp
    ) {
        destinations.forEach { destination ->
            val selected = current == destination.route
            NavigationBarItem(
                selected = selected,
                onClick = { nav.switchTo(destination.route) },
                icon = {
                    Icon(destination.icon, contentDescription = if (compact) destination.label else null)
                },
                label = if (compact) null else {
                    { Text(destination.label, maxLines = 1) }
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        }
    }
}

/**
 * Switches tabs without stacking them.
 *
 * Pressing back from a tab should leave the app, not walk backwards through
 * every tab visited — so each switch pops to the start destination and each
 * tab keeps its own scroll position.
 */
private fun NavController.switchTo(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/**
 * Asks for notification permission the first time something plays.
 *
 * Never on launch — a permission prompt before the app has done anything is
 * the one that gets denied — and never as a gate: playback works without it,
 * only the media notification is missing, so nothing here blocks on the answer.
 */
@Composable
private fun NotificationPermission(playing: Boolean) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Denied is fine: playback continues without a notification. */ }

    LaunchedEffect(playing) {
        if (!playing) return@LaunchedEffect
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

private fun decode(value: String?): String =
    runCatching { java.net.URLDecoder.decode(value.orEmpty(), "UTF-8") }
        .getOrDefault(value.orEmpty())
