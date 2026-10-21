package com.movieroulettetuesday.app.ui

import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
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

@Composable
fun MrtApp() {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val current = backStack?.destination
    val app = LocalContext.current.applicationContext as MrtApplication
    val episodesState by app.episodeRepository.state.collectAsStateWithLifecycle()
    var suggestOpen by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        app.catalogRepository.load()
        app.episodeRepository.load()
        app.catalogRepository.refresh()
    }
    Scaffold(
        bottomBar = {
            NavigationBar {
                TopLevelDestination.entries.forEach { dest ->
                    val selected = current?.hierarchy?.any { it.route == dest.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(dest.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            val badge = if (dest == TopLevelDestination.Episodes) episodesState.newEpisodeCount else 0
                            if (badge > 0) {
                                BadgedBox(badge = { Badge(Modifier.testTag("episodes-badge")) { Text("$badge") } }) {
                                    Icon(dest.icon, contentDescription = null)
                                }
                            } else {
                                Icon(dest.icon, contentDescription = null)
                            }
                        },
                        label = { Text(stringResource(dest.label)) },
                        modifier = Modifier.testTag("tab-${dest.route}"),
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = TopLevelDestination.Roulette.route,
            modifier = Modifier.padding(padding),
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
            episodesGraph(navController, app)
            moviesGraph(navController, app, onSuggest = { suggestOpen = true })
            composable(TopLevelDestination.Stats.route) {
                val inputs = rememberMoviesInputs(app)
                val log by app.spinHistory.log.collectAsStateWithLifecycle()
                StatsScreen(log, inputs.ratings, inputs.catalog, Modifier.testTag("screen-stats"))
            }
        }
    }
    if (suggestOpen) {
        val catalog = rememberMoviesInputs(app).catalog
        SuggestMovieSheet(categories = catalog?.categories.orEmpty(), onDismiss = { suggestOpen = false })
    }
}

private fun NavGraphBuilder.settingsRoutes(nav: NavHostController, app: MrtApplication, onSuggest: () -> Unit) {
    composable("roulette/settings") {
        val prefs = app.preferences
        val appearance by prefs.appearance.collectAsStateWithLifecycle()
        val haptics by prefs.haptics.collectAsStateWithLifecycle()
        val alerts by prefs.newEpisodeAlerts.collectAsStateWithLifecycle()
        SettingsScreen(
            state = SettingsState(appearance, haptics, alerts),
            actions = SettingsActions(
                setAppearance = prefs::setAppearance,
                setHaptics = prefs::setHaptics,
                setNewEpisodeAlerts = prefs::setNewEpisodeAlerts,
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
        MovieDetailScreen(
            movieId = entry.arguments?.getString("id").orEmpty(),
            inputs = rememberMoviesInputs(app),
            ratingsStore = app.personalRatings,
            onBack = { nav.popBackStack() },
            onOpenEpisode = if (linkEpisodes) ({ nav.navigate(Routes.episode(prefix, it)) }) else null,
        )
    }
}

private fun NavGraphBuilder.episodeDetail(prefix: String, nav: NavHostController, app: MrtApplication, linkMovie: Boolean) {
    composable("$prefix/episode/{guid}") { entry ->
        val inputs = rememberMoviesInputs(app)
        EpisodeDetailScreen(
            guid = entry.arguments?.getString("guid").orEmpty(),
            repository = app.episodeRepository,
            appleLinks = app.appleEpisodeLinks,
            catalog = inputs.catalog,
            onBack = { nav.popBackStack() },
            onOpenMovie = if (linkMovie) ({ nav.navigate(Routes.movie(prefix, it)) }) else null,
        )
    }
}

private fun NavGraphBuilder.episodesGraph(nav: NavHostController, app: MrtApplication) {
    navigation(startDestination = "episodes/list", route = TopLevelDestination.Episodes.route) {
        composable("episodes/list") {
            EpisodesScreen(
                repository = app.episodeRepository,
                catalog = rememberMoviesInputs(app).catalog,
                settings = app.settings,
                onOpenEpisode = { nav.navigate(Routes.episode("episodes", it)) },
                modifier = Modifier.testTag("screen-episodes"),
            )
        }
        // Episode -> its movie; that movie page doesn't link back to episodes (no endless push loop).
        episodeDetail("episodes", nav, app, linkMovie = true)
        movieDetail("episodes", nav, app, linkEpisodes = false)
    }
}

private fun NavGraphBuilder.moviesGraph(nav: NavHostController, app: MrtApplication, onSuggest: () -> Unit) {
    navigation(startDestination = "movies/list", route = TopLevelDestination.Movies.route) {
        composable("movies/list") {
            MoviesScreen(
                inputs = rememberMoviesInputs(app),
                onOpenMovie = { nav.navigate(Routes.movie("movies", it)) },
                onSuggest = onSuggest,
                onRefresh = { app.catalogRepository.refresh() },
                modifier = Modifier.testTag("screen-movies"),
            )
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
