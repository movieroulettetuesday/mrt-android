package com.movieroulettetuesday.app.ui

import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.movieroulettetuesday.app.notifications.NewEpisodeAlertToggle
import com.movieroulettetuesday.app.notifications.NewEpisodeSchedule
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import com.movieroulettetuesday.app.MrtApplication
import com.movieroulettetuesday.app.catalog.ShowInfo
import com.movieroulettetuesday.app.community.CommunityCopy
import com.movieroulettetuesday.app.community.EpisodePollSection
import com.movieroulettetuesday.app.community.ListenerRatingSection
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.movieroulettetuesday.app.BuildConfig
import com.movieroulettetuesday.app.R
import com.movieroulettetuesday.app.movies.StreamingCatalog
import com.movieroulettetuesday.app.settings.CreditsScreen
import com.movieroulettetuesday.app.settings.MyServicesScreen
import com.movieroulettetuesday.app.settings.SettingsActions
import com.movieroulettetuesday.app.settings.SettingsScreen
import com.movieroulettetuesday.app.settings.SettingsState
import com.movieroulettetuesday.app.stats.StatsScreen
import com.movieroulettetuesday.app.suggest.SuggestMovieSheet
import com.movieroulettetuesday.app.suggest.SuggestionRateLimit
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.IconButton
import com.movieroulettetuesday.app.catalog.CatalogRepository
import com.movieroulettetuesday.app.episodes.EpisodeDetailScreen
import com.movieroulettetuesday.app.episodes.EpisodesScreen
import com.movieroulettetuesday.app.movies.MovieDetailScreen
import com.movieroulettetuesday.app.movies.MoviesInputs
import com.movieroulettetuesday.app.movies.MoviesScreen
import com.movieroulettetuesday.app.roulette.RouletteRoute
import com.movieroulettetuesday.app.roulette.SpinStats

/** The four iOS tabs (ContentView.swift): Roulette, Episodes, Movies, Stats. */
enum class TopLevelDestination(val route: String, @param:StringRes val label: Int, val icon: ImageVector) {
    Roulette("roulette", R.string.tab_roulette, Icons.Filled.Album),
    Episodes("episodes", R.string.tab_episodes, Icons.Filled.Mic),
    Movies("movies", R.string.tab_movies, Icons.Filled.Movie),
    Stats("stats", R.string.tab_stats, Icons.Filled.BarChart),
}

object Routes {
    fun episode(prefix: String, guid: String) = "$prefix/episode/${Uri.encode(guid)}"
    fun movie(prefix: String, id: String) = "$prefix/movie/${Uri.encode(id)}"
}

/** Live inputs shared by the Movies and Episodes screens. */
@Composable
fun rememberMoviesInputs(app: MrtApplication): MoviesInputs {
    val catalogState by app.catalogRepository.state.collectAsStateWithLifecycle()
    val ratings by app.personalRatings.ratings.collectAsStateWithLifecycle()
    val log by app.spinHistory.log.collectAsStateWithLifecycle()
    val episodes by app.episodeRepository.state.collectAsStateWithLifecycle()
    val services by app.myServices.collectAsStateWithLifecycle()
    return MoviesInputs(
        catalog = (catalogState as? CatalogRepository.State.Loaded)?.catalog,
        ratings = ratings,
        stats = remember(log) { SpinStats(log) },
        services = services,
        episodesByGUID = episodes.byGuid,
        showArtwork = episodes.showArtworkURL,
    )
}

/** Selection shown in the detail pane of the two-pane Episodes / Movies layouts (regular width). */
class PaneSelection(episode: String? = null, movie: String? = null) {
    var episode by mutableStateOf(episode)
    var movie by mutableStateOf(movie)

    companion object {
        val Saver = androidx.compose.runtime.saveable.listSaver<PaneSelection, String?>(
            save = { listOf(it.episode, it.movie) },
            restore = { PaneSelection(it[0], it[1]) },
        )
    }
}

@Composable
fun MrtApp() {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val layout = remember(maxWidth, maxHeight) { WindowLayout(maxWidth.value, maxHeight.value) }
        CompositionLocalProvider(LocalWindowLayout provides layout) { MrtAppContent(layout) }
    }
}

@Composable
private fun MrtAppContent(layout: WindowLayout) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val current = backStack?.destination
    val app = LocalContext.current.applicationContext as MrtApplication
    val episodesState by app.episodeRepository.state.collectAsStateWithLifecycle()
    var suggestOpen by remember { mutableStateOf(false) }
    val panes = rememberSaveable(saver = PaneSelection.Saver) { PaneSelection() }
    val regular = layout.isRegularWidth
    LaunchedEffect(Unit) {
        app.catalogRepository.load()
        app.episodeRepository.load()
        if (app.catalogRepository.refresh()) app.widgetBridge.catalogUpdated()
    }
    fun openTab(dest: TopLevelDestination) {
        navController.navigate(dest.route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
    // Deep links (widget, notification): a pushed page on phones, the detail pane on tablets.
    val pendingMovie by app.pendingMovie.collectAsStateWithLifecycle()
    LaunchedEffect(pendingMovie) {
        val id = pendingMovie ?: return@LaunchedEffect
        app.pendingMovie.value = null
        openTab(TopLevelDestination.Movies)
        if (regular) {
            navController.popBackStack("movies/list", inclusive = false)
            panes.movie = id
        } else {
            navController.navigate(Routes.movie("movies", id))
        }
    }
    val pendingEpisode by app.pendingEpisode.collectAsStateWithLifecycle()
    LaunchedEffect(pendingEpisode) {
        val guid = pendingEpisode ?: return@LaunchedEffect
        app.pendingEpisode.value = null
        openTab(TopLevelDestination.Episodes)
        if (regular) {
            navController.popBackStack("episodes/list", inclusive = false)
            panes.episode = guid
        } else {
            navController.navigate(Routes.episode("episodes", guid))
        }
    }
    // iOS keeps the selection when the size class changes: a split-view pick becomes a pushed page.
    LaunchedEffect(regular) {
        if (regular) return@LaunchedEffect
        val route = navController.currentDestination?.route
        if (route == "movies/list") panes.movie?.let { navController.navigate(Routes.movie("movies", it)) }
        if (route == "episodes/list") panes.episode?.let { navController.navigate(Routes.episode("episodes", it)) }
        panes.movie = null
        panes.episode = null
    }
    val badge = episodesState.newEpisodeCount
    val host: @Composable (Modifier) -> Unit = { mod ->
        NavHost(
            navController = navController,
            startDestination = TopLevelDestination.Roulette.route,
            modifier = mod,
        ) {
            navigation(startDestination = "roulette/home", route = TopLevelDestination.Roulette.route) {
                composable("roulette/home") {
                    RouletteRoute(
                        Modifier.testTag("screen-roulette"),
                        onOpenMovie = { navController.navigate(Routes.movie("roulette", it)) },
                        topBar = {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                IconButton({ navController.navigate("roulette/settings") }, Modifier.testTag("open-settings")) {
                                    Icon(Icons.Filled.Settings, contentDescription = "Settings")
                                }
                            }
                        },
                    )
                }
                settingsRoutes(navController, app, onSuggest = { suggestOpen = true })
                movieDetail("roulette", navController, app, linkEpisodes = true)
                episodeDetail("roulette", navController, app, linkMovie = false)
            }
            episodesGraph(navController, app, panes, regular)
            moviesGraph(navController, app, onSuggest = { suggestOpen = true }, panes, regular)
            composable(TopLevelDestination.Stats.route) {
                val inputs = rememberMoviesInputs(app)
                val log by app.spinHistory.log.collectAsStateWithLifecycle()
                StatsScreen(log, inputs.ratings, inputs.catalog, Modifier.testTag("screen-stats"))
            }
        }
    }
    if (regular) {
        // Medium / expanded: iPad-style top tab bar (iPadOS 18 `TabView` at regular width).
        Scaffold(
            topBar = {
                TopTabBar(
                    isSelected = { dest -> current?.hierarchy?.any { it.route == dest.route } == true },
                    onSelect = ::openTab,
                    newEpisodes = badge,
                )
            },
        ) { padding -> host(Modifier.padding(padding)) }
    } else {
        Scaffold(
            bottomBar = {
                NavigationBar(Modifier.testTag("nav-bar")) {
                    TopLevelDestination.entries.forEach { dest ->
                        NavigationBarItem(
                            selected = current?.hierarchy?.any { it.route == dest.route } == true,
                            onClick = { openTab(dest) },
                            icon = { TabIcon(dest, badge) },
                            label = { Text(stringResource(dest.label)) },
                            modifier = Modifier.testTag("tab-${dest.route}"),
                        )
                    }
                }
            },
        ) { padding -> host(Modifier.padding(padding)) }
    }
    if (suggestOpen) {
        val catalog = rememberMoviesInputs(app).catalog
        val community = app.community?.takeIf { it.canWrite }
        SuggestMovieSheet(
            categories = catalog?.categories.orEmpty(),
            onDismiss = { suggestOpen = false },
            sendInApp = community?.let { c -> { s -> c.submitSuggestion(s, app.appVersion) } },
            rateLimit = { SuggestionRateLimit(app.settings.getLong(SuggestionRateLimit.STORAGE_KEY)) },
            recordSend = { app.settings.putLong(SuggestionRateLimit.STORAGE_KEY, System.currentTimeMillis()) },
        )
    }
}

@Composable
private fun TabIcon(dest: TopLevelDestination, newEpisodes: Int) {
    if (dest == TopLevelDestination.Episodes && newEpisodes > 0) {
        BadgedBox(badge = { Badge(Modifier.testTag("episodes-badge")) { Text("$newEpisodes") } }) {
            Icon(dest.icon, contentDescription = null)
        }
    } else {
        Icon(dest.icon, contentDescription = null)
    }
}

private fun NavGraphBuilder.settingsRoutes(nav: NavHostController, app: MrtApplication, onSuggest: () -> Unit) {
    composable("roulette/settings") {
        val prefs = app.preferences
        val appearance by prefs.appearance.collectAsStateWithLifecycle()
        val haptics by prefs.haptics.collectAsStateWithLifecycle()
        val tickSound by prefs.tickSound.collectAsStateWithLifecycle()
        val alerts by prefs.newEpisodeAlerts.collectAsStateWithLifecycle()
        val autoShare by prefs.autoShareRatings.collectAsStateWithLifecycle()
        val canShare = app.community?.canWrite == true
        val episodes by app.episodeRepository.state.collectAsStateWithLifecycle()
        var alertNote by remember { mutableStateOf<String?>(null) }
        val context = LocalContext.current
        fun finish(granted: Boolean) {
            alertNote = NewEpisodeAlertToggle.onPermissionResult(granted, episodes.episodes, prefs, app.episodeWatermarks) {
                NewEpisodeSchedule.schedule(context)
            }
        }
        val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            finish(granted && app.episodeNotifier.canPost())
        }
        SettingsScreen(
            state = SettingsState(appearance, haptics, alerts, alertNote = alertNote, tickSound = tickSound,
                autoShareRatings = if (canShare) autoShare else null, communityEnabled = canShare),
            actions = SettingsActions(
                setAppearance = prefs::setAppearance,
                setHaptics = prefs::setHaptics,
                setTickSound = prefs::setTickSound,
                setNewEpisodeAlerts = { on ->
                    alertNote = null
                    when {
                        !on -> NewEpisodeAlertToggle.turnOff(prefs) { NewEpisodeSchedule.cancel(context) }
                        app.episodeNotifier.canPost() -> finish(true)
                        Build.VERSION.SDK_INT >= 33 -> permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else -> finish(false) // notifications blocked in system settings
                    }
                },
                setAutoShareRatings = prefs::setAutoShareRatings,
                openMyServices = { nav.navigate("roulette/settings/services") },
                openCredits = { nav.navigate("roulette/settings/credits") },
                suggest = onSuggest,
                close = { nav.popBackStack() },
            ),
        )
    }
    composable("roulette/settings/services") {
        val inputs = rememberMoviesInputs(app)
        MyServicesScreen(
            providers = inputs.catalog?.let { StreamingCatalog.services(it.movies) }.orEmpty(),
            selected = inputs.services,
            onToggle = { app.setMyServices(app.myServices.value.toggled(it)) },
            onBack = { nav.popBackStack() },
        )
    }
    composable("roulette/settings/credits") {
        CreditsScreen("${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})") { nav.popBackStack() }
    }
}

private fun NavGraphBuilder.movieDetail(prefix: String, nav: NavHostController, app: MrtApplication, linkEpisodes: Boolean) {
    composable("$prefix/movie/{id}") { entry ->
        MovieDetailContent(
            app,
            movieId = entry.arguments?.getString("id").orEmpty(),
            onBack = { nav.popBackStack() },
            onOpenEpisode = if (linkEpisodes) ({ nav.navigate(Routes.episode(prefix, it)) }) else null,
        )
    }
}

/** Movie page with the community sections; a pushed route on phones, the detail pane on tablets. */
@Composable
private fun MovieDetailContent(app: MrtApplication, movieId: String, onBack: (() -> Unit)?, onOpenEpisode: ((String) -> Unit)?) {
    val inputs = rememberMoviesInputs(app)
    val community = app.community
    val autoShare by app.preferences.autoShareRatings.collectAsStateWithLifecycle()
    val sharing = autoShare && community?.canWrite == true
    val scope = rememberCoroutineScope()
    MovieDetailScreen(
        movieId = movieId,
        inputs = inputs,
        ratingsStore = app.personalRatings,
        onBack = onBack,
        onOpenEpisode = onOpenEpisode,
        ratingFooter = if (sharing) CommunityCopy.AUTO_SHARE_FOOTER else "Saved on this device.",
        onRated = { half -> if (sharing && half != null) scope.launch { community?.submitListenerRating(movieId, half) } },
        extra = { movie -> community?.let { ListenerRatingSection(movie.id, inputs.ratings.halfStars(movie.id), it) } },
    )
}

private fun NavGraphBuilder.episodeDetail(prefix: String, nav: NavHostController, app: MrtApplication, linkMovie: Boolean) {
    composable("$prefix/episode/{guid}") { entry ->
        EpisodeDetailContent(
            app,
            guid = entry.arguments?.getString("guid").orEmpty(),
            onBack = { nav.popBackStack() },
            onOpenMovie = if (linkMovie) ({ nav.navigate(Routes.movie(prefix, it)) }) else null,
        )
    }
}

/** Episode page with the listener poll; a pushed route on phones, the detail pane on tablets. */
@Composable
private fun EpisodeDetailContent(app: MrtApplication, guid: String, onBack: (() -> Unit)?, onOpenMovie: ((String) -> Unit)?) {
    val inputs = rememberMoviesInputs(app)
    EpisodeDetailScreen(
        guid = guid,
        repository = app.episodeRepository,
        appleLinks = app.appleEpisodeLinks,
        catalog = inputs.catalog,
        onBack = onBack,
        onOpenMovie = onOpenMovie,
        extra = { episode ->
            app.community?.let { c -> EpisodePollSection(c.poll(episode.guid, inputs.catalog?.showInfo ?: ShowInfo.MRT), c) }
        },
    )
}

private fun NavGraphBuilder.episodesGraph(nav: NavHostController, app: MrtApplication, panes: PaneSelection, regular: Boolean) {
    navigation(startDestination = "episodes/list", route = TopLevelDestination.Episodes.route) {
        composable("episodes/list") {
            val list: @Composable () -> Unit = {
                EpisodesScreen(
                    repository = app.episodeRepository,
                    catalog = rememberMoviesInputs(app).catalog,
                    settings = app.settings,
                    onOpenEpisode = { if (regular) panes.episode = it else nav.navigate(Routes.episode("episodes", it)) },
                    modifier = Modifier.testTag("screen-episodes"),
                    selectedGuid = if (regular) panes.episode else null,
                )
            }
            if (regular) {
                val guid = panes.episode
                ListDetailPane(
                    list = list,
                    detail = guid?.let { g ->
                        {
                            key(g) {
                                EpisodeDetailContent(app, g, onBack = null, onOpenMovie = { nav.navigate(Routes.movie("episodes", it)) })
                            }
                        }
                    },
                    empty = { EmptyDetail("Pick an Episode", Icons.Filled.Mic, "Choose an episode to see its details and the listener poll.") },
                )
            } else {
                list()
            }
        }
        // Episode -> its movie; that movie page doesn't link back to episodes (no endless push loop).
        episodeDetail("episodes", nav, app, linkMovie = true)
        movieDetail("episodes", nav, app, linkEpisodes = false)
    }
}

private fun NavGraphBuilder.moviesGraph(
    nav: NavHostController,
    app: MrtApplication,
    onSuggest: () -> Unit,
    panes: PaneSelection,
    regular: Boolean,
) {
    navigation(startDestination = "movies/list", route = TopLevelDestination.Movies.route) {
        composable("movies/list") {
            val list: @Composable () -> Unit = {
                MoviesScreen(
                    inputs = rememberMoviesInputs(app),
                    onOpenMovie = { if (regular) panes.movie = it else nav.navigate(Routes.movie("movies", it)) },
                    onSuggest = onSuggest,
                    onRefresh = { app.catalogRepository.refresh() },
                    modifier = Modifier.testTag("screen-movies"),
                    selectedMovieId = if (regular) panes.movie else null,
                )
            }
            if (regular) {
                val id = panes.movie
                ListDetailPane(
                    list = list,
                    detail = id?.let { m ->
                        {
                            key(m) {
                                MovieDetailContent(app, m, onBack = null, onOpenEpisode = { nav.navigate(Routes.episode("movies", it)) })
                            }
                        }
                    },
                    empty = { EmptyDetail("Pick a Movie", Icons.Filled.Movie, "Choose a film from the list to see its page, episodes and ratings.") },
                )
            } else {
                list()
            }
        }
        movieDetail("movies", nav, app, linkEpisodes = true)
        episodeDetail("movies", nav, app, linkMovie = false)
    }
}

@Composable
fun PlaceholderScreen(dest: TopLevelDestination) {
    Box(Modifier.fillMaxSize().testTag("screen-${dest.route}"), contentAlignment = Alignment.Center) {
        Text(stringResource(dest.label))
    }
}
