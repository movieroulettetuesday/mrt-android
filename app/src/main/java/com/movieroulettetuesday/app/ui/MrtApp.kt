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
                        icon = { Icon(dest.icon, contentDescription = null) },
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
            TopLevelDestination.entries.filter { it != TopLevelDestination.Roulette }.forEach { dest ->
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
