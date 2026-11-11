package com.movieroulettetuesday.app.suggestions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.movieroulettetuesday.app.catalog.Movie

/**
 * In-app “What should I watch?” card (iOS 1.1 mockup): title, year, genre, a short why, and
 * Open Movie / Where to Watch / Spin instead. Shown only after a successful on-device suggestion.
 */
@Composable
fun WatchSuggestionCard(
    movie: Movie,
    why: String,
    onOpenMovie: () -> Unit,
    onWhereToWatch: () -> Unit,
    onSpinInstead: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier.fillMaxWidth().testTag("watch-suggestion-card")) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("What should I watch?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(movie.title, Modifier.testTag("watch-suggestion-title"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Text("${movie.year}  ·  ${movie.genre}", Modifier.testTag("watch-suggestion-meta"), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(why, Modifier.testTag("watch-suggestion-why"), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onOpenMovie, Modifier.testTag("watch-suggestion-open")) { Text("Open Movie") }
                OutlinedButton(onWhereToWatch, Modifier.testTag("watch-suggestion-wtw")) { Text("Where to Watch") }
            }
            Button(onSpinInstead, Modifier.testTag("watch-suggestion-spin")) { Text("Spin instead") }
            TextButton(onDismiss, Modifier.testTag("watch-suggestion-done")) { Text("Done") }
        }
    }
}

@Composable
fun SuggestionUnavailableCard(
    message: String,
    onSpin: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier.fillMaxWidth().testTag("watch-suggestion-unavailable")) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("What should I watch?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(message, Modifier.testTag("watch-suggestion-unavailable-message"), textAlign = TextAlign.Center)
            Button(onSpin, Modifier.testTag("watch-suggestion-offer-spin")) { Text("Spin") }
            TextButton(onDismiss, Modifier.testTag("watch-suggestion-unavailable-done")) { Text("Not now") }
        }
    }
}

@Composable
fun SuggestionNotReadyCard(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier.fillMaxWidth().testTag("watch-suggestion-not-ready")) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("What should I watch?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("Suggestions aren’t ready yet.", textAlign = TextAlign.Center)
            TextButton(onDismiss, Modifier.testTag("watch-suggestion-not-ready-done")) { Text("OK") }
        }
    }
}

@Composable
fun SuggestionLoadingCard(modifier: Modifier = Modifier) {
    Card(modifier.fillMaxWidth().testTag("watch-suggestion-loading")) {
        Column(
            Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator()
            Text("Picking a movie…")
        }
    }
}

/** Roulette-tab entry. Hidden when [visible] is false so the tab looks as it does today. */
@Composable
fun WhatShouldIWatchButton(
    visible: Boolean,
    notReady: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!visible) return
    val label = if (notReady) "Suggestions aren’t ready yet" else "What should I watch?"
    TextButton(onClick, modifier.fillMaxWidth().testTag("what-should-i-watch"), enabled = !notReady) {
        Text(label)
    }
}
