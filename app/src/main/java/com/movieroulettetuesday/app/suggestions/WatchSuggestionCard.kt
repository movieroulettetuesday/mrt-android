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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.movieroulettetuesday.app.catalog.Movie

/** Pick for Me copy (plan §3, Matthew’s picks Nov 17). One place so the wording is easy to change. */
object PickForMeCopy {
    const val BUTTON = "Pick for Me"
    const val SUBTITLE = "Not random: one movie chosen for you, and why it fits."
    const val BUTTON_HINT = "Picks one movie for you on purpose and explains why. Not random."
    const val NOT_READY_BUTTON = "Suggestions aren’t ready yet"
    const val NOT_READY = "Suggestions aren’t ready yet."
    const val CARD_TITLE = "Picked for You"
    const val CARD_HEADER = "PICKED FOR YOU"
    const val WHY = "WHY"
    const val LOADING = "Picking from your watchlist and services…"
    const val CANCEL = "Cancel"
    const val SPIN_INSTEAD = "Spin instead"
}

/**
 * In-app Pick for Me result: title, year, genre, then WHY: the vibe line (or the no-model line) and
 * the facts the app knows. Open Movie / Where to Watch / Spin instead.
 */
@Composable
fun WatchSuggestionCard(
    movie: Movie,
    reason: PickReason,
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
            Text(
                PickForMeCopy.CARD_TITLE,
                Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                PickForMeCopy.CARD_HEADER,
                Modifier.testTag("watch-suggestion-header"),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
            Text(movie.title, Modifier.testTag("watch-suggestion-title"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Text("${movie.year}  ·  ${movie.genre}", Modifier.testTag("watch-suggestion-meta"), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(PickForMeCopy.WHY, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
            reason.headline?.let {
                Text(it, Modifier.testTag("watch-suggestion-why"), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            }
            if (reason.facts.isNotEmpty()) {
                Text(reason.factsLine, Modifier.testTag("watch-suggestion-facts"), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onOpenMovie, Modifier.testTag("watch-suggestion-open")) { Text("Open Movie") }
                OutlinedButton(onWhereToWatch, Modifier.testTag("watch-suggestion-wtw")) { Text("Where to Watch") }
            }
            Button(onSpinInstead, Modifier.testTag("watch-suggestion-spin")) { Text(PickForMeCopy.SPIN_INSTEAD) }
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
            Text(PickForMeCopy.BUTTON, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
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
            Text(PickForMeCopy.BUTTON, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(PickForMeCopy.NOT_READY, textAlign = TextAlign.Center)
            TextButton(onDismiss, Modifier.testTag("watch-suggestion-not-ready-done")) { Text("OK") }
        }
    }
}

/** While the model works. Cancel stops it; Spin instead stops it and spins. */
@Composable
fun SuggestionLoadingCard(
    onCancel: () -> Unit,
    onSpinInstead: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier.fillMaxWidth().testTag("watch-suggestion-loading")) {
        Column(
            Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator()
            Text(PickForMeCopy.LOADING, textAlign = TextAlign.Center)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onSpinInstead, Modifier.testTag("watch-suggestion-loading-spin")) { Text(PickForMeCopy.SPIN_INSTEAD) }
                TextButton(onCancel, Modifier.testTag("watch-suggestion-cancel")) { Text(PickForMeCopy.CANCEL) }
            }
        }
    }
}

/**
 * Roulette-tab entry, with its subtitle so it never reads like a second Spin. Hidden when
 * [visible] is false so the tab looks as it does today. Test tag kept from 1.1 (“what-should-i-watch”).
 */
@Composable
fun PickForMeButton(
    visible: Boolean,
    notReady: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!visible) return
    val label = if (notReady) PickForMeCopy.NOT_READY_BUTTON else PickForMeCopy.BUTTON
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        TextButton(
            onClick,
            Modifier
                .fillMaxWidth()
                .testTag("what-should-i-watch")
                // TalkBack reads the label, then the hint (iOS accessibilityHint).
                .semantics { contentDescription = "$label. ${PickForMeCopy.BUTTON_HINT}" },
            enabled = !notReady,
        ) {
            Text(label)
        }
        Text(
            PickForMeCopy.SUBTITLE,
            Modifier.testTag("pick-for-me-subtitle"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
