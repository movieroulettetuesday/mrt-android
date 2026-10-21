package com.movieroulettetuesday.app.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.movieroulettetuesday.app.R
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.navigation
import com.movieroulettetuesday.app.MrtApplication
import com.movieroulettetuesday.app.catalog.CatalogRepository
import com.movieroulettetuesday.app.episodes.EpisodeDetailScreen
import com.movieroulettetuesday.app.episodes.EpisodesScreen
import com.movieroulettetuesday.app.roulette.RouletteRoute

/** The four iOS tabs (ContentView.swift): Roulette, Episodes, Movies, Stats. */
enum class TopLevelDestination(val route: String, @param:StringRes val label: Int, val icon: ImageVector) {
    Roulette("roulette", R.string.tab_roulette, Icons.Filled.Album),
    Episodes("episodes", R.string.tab_episodes, Icons.Filled.Mic),
    Movies("movies", R.string.tab_movies, Icons.Filled.Movie),
    Stats("stats", R.string.tab_stats, Icons.Filled.BarChart),
}

@Composable
fun MrtApp() {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val current = backStack?.destination
    val app = LocalContext.current.applicationContext as MrtApplication
    val episodesState by app.episodeRepository.state.collectAsStateWithLifecycle()
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
            composable(TopLevelDestination.Roulette.route) {
                RouletteRoute(Modifier.testTag("screen-roulette"))
            }
            episodesGraph(navController, app)
            TopLevelDestination.entries.filter { it == TopLevelDestination.Movies || it == TopLevelDestination.Stats }.forEach { dest ->
                composable(dest.route) { PlaceholderScreen(dest) }
            }
        }
    }
}

@Composable
fun PlaceholderScreen(dest: TopLevelDestination) {
    Box(Modifier.fillMaxSize().testTag("screen-${dest.route}"), contentAlignment = Alignment.Center) {
        Text(stringResource(dest.label))
    }
}

private fun NavGraphBuilder.episodesGraph(nav: NavHostController, app: MrtApplication) {
    navigation(startDestination = "episodes/list", route = TopLevelDestination.Episodes.route) {
        composable("episodes/list") {
            val catalog = app.catalogRepository.state.collectAsStateWithLifecycle().value
            EpisodesScreen(
                repository = app.episodeRepository,
                catalog = (catalog as? CatalogRepository.State.Loaded)?.catalog,
                settings = app.settings,
                onOpenEpisode = { nav.navigate("episodes/episode/${android.net.Uri.encode(it)}") },
                modifier = Modifier.testTag("screen-episodes"),
            )
        }
        composable("episodes/episode/{guid}") { entry ->
            val catalog = app.catalogRepository.state.collectAsStateWithLifecycle().value
            EpisodeDetailScreen(
                guid = entry.arguments?.getString("guid").orEmpty(),
                repository = app.episodeRepository,
                appleLinks = app.appleEpisodeLinks,
                catalog = (catalog as? CatalogRepository.State.Loaded)?.catalog,
                onBack = { nav.popBackStack() },
                onOpenMovie = null,
            )
        }
    }
}
