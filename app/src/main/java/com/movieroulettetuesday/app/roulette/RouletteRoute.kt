package com.movieroulettetuesday.app.roulette

import android.provider.Settings
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.movieroulettetuesday.app.MrtApplication
import com.movieroulettetuesday.app.actions.ActionSpeech
import com.movieroulettetuesday.app.actions.MrtShortcuts
import com.movieroulettetuesday.app.suggestions.SuggestionAvailability
import com.movieroulettetuesday.app.suggestions.SuggestionOutcome
import com.movieroulettetuesday.app.suggestions.SuggestionLoadingCard
import com.movieroulettetuesday.app.suggestions.SuggestionNotReadyCard
import com.movieroulettetuesday.app.suggestions.SuggestionUnavailableCard
import com.movieroulettetuesday.app.suggestions.WatchSuggester
import com.movieroulettetuesday.app.suggestions.WatchSuggestionCard
import com.movieroulettetuesday.app.suggestions.WhatShouldIWatchButton
import com.movieroulettetuesday.app.catalog.Movie
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private sealed class SuggestUi {
    data object None : SuggestUi()
    data object Loading : SuggestUi()
    data object NotReady : SuggestUi()
    data class Ready(val movie: Movie, val why: String) : SuggestUi()
    data class OfferSpin(val message: String) : SuggestUi()
}

/** Roulette tab wired to the app's catalog repository and on-device spin history. */
@Composable
fun RouletteRoute(
    modifier: Modifier = Modifier,
    onOpenMovie: ((String) -> Unit)? = null,
    onWhereToWatch: ((String) -> Unit)? = null,
    topBar: @Composable () -> Unit = {},
) {
    val app = LocalContext.current.applicationContext as MrtApplication
    val context = LocalContext.current
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
    val state by vm.state.collectAsStateWithLifecycle()

    val pendingSpin by app.pendingSpin.collectAsStateWithLifecycle()
    LaunchedEffect(pendingSpin, state.canSpin, state.reveal, state.isSpinning, state.isAwaitingForcedRespin) {
        if (pendingSpin && vm.requestExternalSpin()) app.pendingSpin.value = false
    }

    var availability by remember { mutableStateOf(SuggestionAvailability.Unavailable) }
    LaunchedEffect(app.suggestionModel) {
        availability = runCatching { app.suggestionModel.availability() }.getOrDefault(SuggestionAvailability.Unavailable)
    }

    var suggestUi by remember { mutableStateOf<SuggestUi>(SuggestUi.None) }
    val scope = rememberCoroutineScope()
    val suggester = remember(app.suggestionModel) {
        WatchSuggester(app.suggestionModel) { app.catalogRepository.catalog }
    }

    fun runSuggestion(fromShortcut: Boolean) {
        scope.launch {
            suggestUi = SuggestUi.Loading
            val ratings = app.personalRatings.ratings.value
            val recent = WatchSuggester.recentIds(app.spinHistory.results.asReversed().map { it.movieId })
            val ctx = suggester.buildContext(ratings, recent, app.myServices.value)
            val outcome = if (ctx == null) {
                if (fromShortcut) SuggestionOutcome.OfferSpin else SuggestionOutcome.Hidden
            } else {
                suggester.suggest(ctx, fromShortcut)
            }
            suggestUi = when (outcome) {
                SuggestionOutcome.Hidden -> {
                    if (fromShortcut) {
                        val msg = ActionSpeech.SUGGESTIONS_UNAVAILABLE
                        app.speak(msg)
                        SuggestUi.OfferSpin(msg)
                    } else {
                        SuggestUi.None
                    }
                }
                SuggestionOutcome.NotReady -> {
                    val msg = ActionSpeech.SUGGESTIONS_NOT_READY
                    if (fromShortcut) app.speak(msg)
                    SuggestUi.NotReady
                }
                SuggestionOutcome.OfferSpin -> {
                    val msg = if (availability == SuggestionAvailability.Unavailable) {
                        ActionSpeech.SUGGESTIONS_UNAVAILABLE
                    } else {
                        ActionSpeech.SUGGESTIONS_FAILED
                    }
                    app.speak(msg)
                    SuggestUi.OfferSpin(msg)
                }
                is SuggestionOutcome.Ready -> {
                    app.speak(ActionSpeech.suggestion(outcome.movie, outcome.why))
                    MrtShortcuts.rememberMovie(context, outcome.movie)
                    SuggestUi.Ready(outcome.movie, outcome.why)
                }
            }
        }
    }

    val pendingSuggest by app.pendingSuggest.collectAsStateWithLifecycle()
    LaunchedEffect(pendingSuggest) {
        if (!pendingSuggest) return@LaunchedEffect
        app.pendingSuggest.value = false
        runSuggestion(fromShortcut = true)
    }

    val showButton = availability == SuggestionAvailability.Available ||
        availability == SuggestionAvailability.Downloadable ||
        availability == SuggestionAvailability.Downloading
    val notReady = availability == SuggestionAvailability.Downloadable ||
        availability == SuggestionAvailability.Downloading

    RouletteScreen(
        vm, modifier,
        episodesByGUID = episodes.byGuid,
        onOpenMovie = onOpenMovie,
        topBar = topBar,
        haptics = haptics,
        headerExtra = {
            WhatShouldIWatchButton(
                visible = showButton,
                notReady = notReady,
                onClick = { runSuggestion(fromShortcut = false) },
            )
            val cardMod = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            when (val ui = suggestUi) {
                SuggestUi.None -> Unit
                SuggestUi.Loading -> SuggestionLoadingCard(cardMod)
                SuggestUi.NotReady -> SuggestionNotReadyCard(onDismiss = { suggestUi = SuggestUi.None }, modifier = cardMod)
                is SuggestUi.Ready -> WatchSuggestionCard(
                    movie = ui.movie,
                    why = ui.why,
                    onOpenMovie = {
                        suggestUi = SuggestUi.None
                        onOpenMovie?.invoke(ui.movie.id)
                    },
                    onWhereToWatch = {
                        suggestUi = SuggestUi.None
                        (onWhereToWatch ?: onOpenMovie)?.invoke(ui.movie.id)
                    },
                    onSpinInstead = {
                        suggestUi = SuggestUi.None
                        vm.requestExternalSpin()
                    },
                    onDismiss = { suggestUi = SuggestUi.None },
                    modifier = cardMod,
                )
                is SuggestUi.OfferSpin -> SuggestionUnavailableCard(
                    message = ui.message,
                    onSpin = {
                        suggestUi = SuggestUi.None
                        vm.requestExternalSpin()
                    },
                    onDismiss = { suggestUi = SuggestUi.None },
                    modifier = cardMod,
                )
            }
        },
    )
}
