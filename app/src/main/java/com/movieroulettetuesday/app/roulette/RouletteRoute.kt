package com.movieroulettetuesday.app.roulette

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.movieroulettetuesday.app.MrtApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Roulette tab wired to the app's catalog repository and on-device spin history. */
@Composable
fun RouletteRoute(
    modifier: Modifier = Modifier,
    onOpenMovie: ((String) -> Unit)? = null,
    topBar: @Composable () -> Unit = {},
) {
    val app = LocalContext.current.applicationContext as MrtApplication
    val vm: RouletteViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                RouletteViewModel(
                    catalogState = app.catalogRepository.state,
                    history = app.spinHistory,
                    scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
                    // System "Remove animations" ≈ iOS Reduce Motion: same timeline, only 1–2 turns.
                    feedback = app.spinSound,
                    soundEnabled = { app.preferences.tickSound.value },
                    jackpot = RatingWeightedSelector(app.listenerAverages),
                    onSpinRecorded = { movie, category, at -> app.widgetBridge.publishSpin(movie, category, at) },
                    reduceMotion = {
                        Settings.Global.getFloat(app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
                    },
                )
            }
        },
    )
    androidx.compose.runtime.DisposableEffect(vm) { onDispose { vm.stopFeedback() } }
    LaunchedEffect(Unit) {
        app.catalogRepository.load()
        app.catalogRepository.refresh()
    }
    val episodes by app.episodeRepository.state.collectAsStateWithLifecycle()
    val haptics by app.preferences.haptics.collectAsStateWithLifecycle()
    RouletteScreen(vm, modifier, episodesByGUID = episodes.byGuid, onOpenMovie = onOpenMovie, topBar = topBar, haptics = haptics)
}
