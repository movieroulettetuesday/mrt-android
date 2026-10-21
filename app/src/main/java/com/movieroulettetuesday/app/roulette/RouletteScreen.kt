package com.movieroulettetuesday.app.roulette

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
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
    Column(
        modifier.fillMaxSize().padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        topBar()
        Spacer(Modifier.height(16.dp))
        Text("Movie Roulette Tuesday", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "Dinner, discussion, and the wheel",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        val catalog = state.catalog
        when {
            catalog != null -> Box(Modifier.widthIn(max = 520.dp), contentAlignment = Alignment.Center) {
                RouletteWheel(
                    categories = catalog.categories,
                    geometry = state.geometry,
                    rotation = state.rotation,
                    spin = state.spin,
                    canSpin = state.canSpin,
                    onSpin = viewModel::spin,
                    onSpinFinished = viewModel::spinAnimationEnded,
                    haptics = haptics,
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
        Spacer(Modifier.height(16.dp))
        val status = state.notice ?: when {
            state.isSpinning -> "Spinning…"
            state.lastCategory != null -> "Landed on ${state.lastCategory!!.displayName}"
            else -> "Tap to spin"
        }
        Text(status, Modifier.testTag("roulette-status"), style = MaterialTheme.typography.titleMedium)
    }

    state.reveal?.let { reveal ->
        RevealSheet(
            reveal = reveal,
            onDismiss = viewModel::dismissReveal,
            onSpinAgain = viewModel::spinAgain,
            onShare = {
                onShare(reveal)
                SpinShare.share(context, reveal.movie)
            },
            episodesByGUID = episodesByGUID,
            onOpenMovie = onOpenMovie?.let { open -> { viewModel.dismissReveal(); open(reveal.movie.id) } },
        )
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
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                if (reveal.isJackpot) "💀 INFAMOUS JACKPOT 💀" else "TONIGHT'S SPIN",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val catColor = HexColor.parse(reveal.category.colorHex)
            Text(
                reveal.category.displayName,
                Modifier
                    .background(catColor?.let { Color(it.argb) } ?: Color.Gray, RoundedCornerShape(50))
                    .padding(horizontal = 14.dp, vertical = 6.dp)
                    .testTag("reveal-category"),
                color = if (catColor?.prefersDarkText == true) BrandColors.Ink else Color.White,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                reveal.movie.title,
                Modifier.testTag("reveal-title"),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Text("${reveal.movie.year}", style = MaterialTheme.typography.titleMedium)
            Text(EpisodeLabel.discussedText(reveal.movie, episodesByGUID), textAlign = TextAlign.Center)
            Text(
                if (reveal.spinCount == 1) "First spin on this device" else "Spun ${reveal.spinCount} times on this device",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onShare, modifier = Modifier.testTag("reveal-share")) { Text("Share") }
                Button(onClick = onSpinAgain, modifier = Modifier.testTag("reveal-spin-again")) { Text("Spin Again") }
            }
            if (onOpenMovie != null) {
                OutlinedButton(onClick = onOpenMovie, modifier = Modifier.testTag("reveal-movie-page")) { Text("Movie page") }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("reveal-done")) { Text("Done") }
            Spacer(Modifier.height(12.dp))
        }
    }
}
