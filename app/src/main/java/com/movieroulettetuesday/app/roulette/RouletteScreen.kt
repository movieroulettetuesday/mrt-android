package com.movieroulettetuesday.app.roulette

import com.movieroulettetuesday.app.catalog.displayColor
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import com.movieroulettetuesday.app.ui.LocalWindowLayout
import com.movieroulettetuesday.app.ui.RouletteLayout
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.movieroulettetuesday.app.catalog.CatalogRepository
import com.movieroulettetuesday.app.catalog.HexColor
import com.movieroulettetuesday.app.ui.theme.BrandColors

@Composable
fun RouletteScreen(
    viewModel: RouletteViewModel,
    modifier: Modifier = Modifier,
    onShare: (RouletteViewModel.Reveal) -> Unit = {},
    episodesByGUID: Map<String, com.movieroulettetuesday.app.episodes.PodcastEpisode> = emptyMap(),
    onOpenMovie: ((String) -> Unit)? = null,
    topBar: @Composable () -> Unit = {},
    haptics: Boolean = false,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val window = LocalWindowLayout.current
    val share: (RouletteViewModel.Reveal) -> Unit = { reveal ->
        onShare(reveal)
        SpinShare.share(context, reveal.movie)
    }
    val openMovie: ((RouletteViewModel.Reveal) -> Unit)? = onOpenMovie?.let { open -> { r -> viewModel.dismissReveal(); open(r.movie.id) } }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val w = maxWidth.value.toDouble()
        val arrangement = RouletteLayout.arrangement(w, maxHeight.value.toDouble(), window.isRegularWidth)
        if (arrangement == RouletteLayout.Arrangement.SideBySide) {
            // iOS sideBySide: wheel + hint on the left, header on the right. The reveal sits in the
            // right column next to the wheel instead of covering it.
            Row(Modifier.fillMaxSize().testTag("roulette-side-by-side")) {
                WheelSlot(state, viewModel, haptics, arrangement, isRegularWidth = true, Modifier.weight(1f).fillMaxHeight())
                Column(
                    Modifier
                        .width(RouletteLayout.sideBySideControlsWidth(w).dp)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(end = 20.dp, bottom = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    topBar()
                    RouletteHeader()
                    state.reveal?.let { reveal ->
                        Spacer(Modifier.height(24.dp))
                        Card(Modifier.fillMaxWidth().testTag("reveal-inline")) {
                            RevealContent(
                                reveal, episodesByGUID,
                                onDismiss = viewModel::dismissReveal,
                                onSpinAgain = viewModel::spinAgain,
                                onShare = { share(reveal) },
                                onOpenMovie = openMovie?.let { { it(reveal) } },
                            )
                        }
                    }
                }
            }
        } else {
            Column(Modifier.fillMaxSize().testTag("roulette-stacked"), horizontalAlignment = Alignment.CenterHorizontally) {
                Column(Modifier.padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    topBar()
                    RouletteHeader()
                }
                WheelSlot(state, viewModel, haptics, arrangement, window.isRegularWidth, Modifier.weight(1f).fillMaxWidth())
            }
            state.reveal?.let { reveal ->
                RevealSheet(
                    reveal = reveal,
                    onDismiss = viewModel::dismissReveal,
                    onSpinAgain = viewModel::spinAgain,
                    onShare = { share(reveal) },
                    episodesByGUID = episodesByGUID,
                    onOpenMovie = openMovie?.let { { it(reveal) } },
                )
            }
        }
    }
}

@Composable
private fun RouletteHeader() {
    Spacer(Modifier.height(16.dp))
    Text("Movie Roulette Tuesday", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    Text(
        "Dinner, discussion, and the wheel",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(6.dp))
}

/** Wheel sized by iOS `RouletteLayout.wheelDiameter` for the slot it gets, with the hint under it. */
@Composable
private fun WheelSlot(
    state: RouletteViewModel.RouletteState,
    viewModel: RouletteViewModel,
    haptics: Boolean,
    arrangement: RouletteLayout.Arrangement,
    isRegularWidth: Boolean,
    modifier: Modifier,
) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val d = RouletteLayout.wheelDiameter(
            maxWidth.value.toDouble(), maxHeight.value.toDouble(), arrangement, isRegularWidth, RouletteLayout.HINT_HEIGHT,
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // Room for the half of the pointer that sits above the rim (budgeted in wheelDiameter).
            Spacer(Modifier.height((RouletteLayout.TOP_GAP + RouletteLayout.pointerSize(d).second / 2).dp))
            val catalog = state.catalog
            Box(Modifier.size(d.dp).testTag("wheel-slot"), contentAlignment = Alignment.Center) {
                when {
                    catalog != null -> {
                        RouletteWheel(
                            categories = catalog.categories,
                            geometry = state.geometry,
                            rotation = state.rotation,
                            spin = state.spin,
                            canSpin = state.canSpin,
                            onSpin = viewModel::spin,
                            onSpinFinished = viewModel::spinAnimationEnded,
                            haptics = haptics,
                            onStop = viewModel::stopEarly,
                        )
                        state.rareBanner?.let {
                            Text(
                                it,
                                Modifier
                                    .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(12.dp))
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                                    .testTag("rare-banner"),
                                color = Color.White,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Black,
                            )
                        }
                    }
                    state.load is CatalogRepository.State.Failed ->
                        Text((state.load as CatalogRepository.State.Failed).message, textAlign = TextAlign.Center)
                    else -> CircularProgressIndicator()
                }
            }
            Spacer(Modifier.height(RouletteLayout.HINT_SPACING.dp))
            val status = state.notice ?: when {
                state.isSpinning -> "Spinning…"
                state.lastCategory != null -> "Landed on ${state.lastCategory!!.displayName}"
                else -> "Tap to spin"
            }
            Box(Modifier.height(RouletteLayout.HINT_HEIGHT.dp), contentAlignment = Alignment.TopCenter) {
                Text(status, Modifier.testTag("roulette-status"), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            }
        }
    }
}

/** Result sheet (Roulette/RevealSheet.swift): category first, then the movie, with Share and Spin Again. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RevealSheet(
    reveal: RouletteViewModel.Reveal,
    onDismiss: () -> Unit,
    onSpinAgain: () -> Unit,
    onShare: () -> Unit,
    episodesByGUID: Map<String, com.movieroulettetuesday.app.episodes.PodcastEpisode> = emptyMap(),
    onOpenMovie: (() -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, modifier = Modifier.testTag("reveal-sheet")) {
        RevealContent(reveal, episodesByGUID, onDismiss, onSpinAgain, onShare, onOpenMovie, pinActions = true)
    }
}

/**
 * The sheet's top title (iOS 1.0.1 99accd7: the reveal's navigation bar title). A jackpot shows the
 * gold "Infamous Jackpot" capsule as the title itself; otherwise the plain "Tonight’s Movie" title.
 * The capsule is drawn in fixed brand colors (gold on ink) in light and dark themes (iOS 6b88813).
 */
@Composable
fun RevealTitleBar(isJackpot: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 24.dp).testTag("reveal-title-bar"),
        contentAlignment = Alignment.Center,
    ) {
        if (isJackpot) {
            Text(
                "Infamous Jackpot",
                Modifier
                    .background(BrandColors.JackpotGold, CircleShape)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .semantics { heading(); contentDescription = "Infamous jackpot" }
                    .testTag("reveal-jackpot-banner"),
                color = BrandColors.Ink,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Black,
                letterSpacing = 0.5.sp,
                maxLines = 1,
            )
        } else {
            Text(
                "Tonight’s Movie",
                Modifier.semantics { heading() }.testTag("reveal-sheet-title"),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}

/** Movie title lines (iOS 78d72a7): two on a compact phone, three on regular width (tablet). */
internal fun revealTitleMaxLines(isRegularWidth: Boolean): Int = if (isRegularWidth) 3 else 2

/**
 * The reveal itself: in the bottom sheet (stacked) or next to the wheel (side by side).
 * [pinActions] (the sheet): the details scroll in the space left over, so Share, Spin Again and
 * Movie page always stay on screen however long the title or large the font scale (iOS 78d72a7 only
 * shrinks the title; Android also lets the details scroll as a fallback).
 */
@Composable
fun RevealContent(
    reveal: RouletteViewModel.Reveal,
    episodesByGUID: Map<String, com.movieroulettetuesday.app.episodes.PodcastEpisode>,
    onDismiss: () -> Unit,
    onSpinAgain: () -> Unit,
    onShare: () -> Unit,
    onOpenMovie: (() -> Unit)?,
    pinActions: Boolean = false,
) {
    val isRegularWidth = LocalWindowLayout.current.isRegularWidth
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        RevealTitleBar(reveal.isJackpot)
        Column(
            Modifier
                .fillMaxWidth()
                // Only the sheet scrolls here; side by side, the whole column already scrolls.
                .then(if (pinActions) Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()) else Modifier)
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .testTag("reveal-details"),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val catColor = reveal.category.displayColor
            Text(
                reveal.category.displayName,
                Modifier
                    .background(Color(catColor.argb), RoundedCornerShape(50))
                    .padding(horizontal = 14.dp, vertical = 6.dp)
                    .testTag("reveal-category"),
                color = if (catColor.prefersDarkText) BrandColors.Ink else Color.White,
                fontWeight = FontWeight.SemiBold,
            )
            val titleStyle = MaterialTheme.typography.headlineMedium
            Text(
                reveal.movie.title,
                Modifier.fillMaxWidth().testTag("reveal-title"),
                style = titleStyle,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = revealTitleMaxLines(isRegularWidth),
                overflow = TextOverflow.Ellipsis,
                // iOS minimumScaleFactor 0.5: shrink to fit the line limit before ellipsizing.
                autoSize = TextAutoSize.StepBased(minFontSize = titleStyle.fontSize * 0.5f, maxFontSize = titleStyle.fontSize, stepSize = 1.sp),
            )
            Text("${reveal.movie.year}", style = MaterialTheme.typography.titleMedium)
            Text(EpisodeLabel.discussedText(reveal.movie, episodesByGUID), textAlign = TextAlign.Center)
            Text(
                if (reveal.spinCount == 1) "First spin on this device" else "Spun ${reveal.spinCount} times on this device",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(
            Modifier.padding(horizontal = 24.dp).testTag("reveal-actions"),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onShare, modifier = Modifier.testTag("reveal-share")) { Text("Share") }
                Button(onClick = onSpinAgain, modifier = Modifier.testTag("reveal-spin-again")) { Text("Spin Again") }
            }
            if (onOpenMovie != null) {
                OutlinedButton(onClick = onOpenMovie, modifier = Modifier.testTag("reveal-movie-page")) { Text("Movie page") }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("reveal-done")) { Text("Done") }
        }
        Spacer(Modifier.height(12.dp))
    }
}
