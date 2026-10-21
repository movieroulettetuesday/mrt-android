package com.movieroulettetuesday.app.community

import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.movieroulettetuesday.app.movies.HalfStars
import com.movieroulettetuesday.app.ui.MrtSection
import com.movieroulettetuesday.app.ui.StarRating
import com.movieroulettetuesday.app.ui.theme.BrandColors
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** Android wording: no iCloud; one vote per app install (pending Matthew, answers Q11). */
object CommunityCopy {
    const val POLL_FOOTER_CHANGE = "Tap another answer to change your vote."
    const val POLL_FOOTER = "One anonymous vote per app install."
    const val RATING_FOOTER = "Shared anonymously; only the movie and star count are stored."
    const val READ_ONLY = "Voting isn’t available in this version of the app."
    const val AUTO_SHARE_FOOTER = "Saved on this device and shared anonymously with other listeners."
}

@Composable
private fun teal() = if (isSystemInDarkTheme()) BrandColors.TealDark else BrandColors.TealLight

@Composable
private fun peach() = if (isSystemInDarkTheme()) BrandColors.PeachDark else BrandColors.PeachLight

private val Warn = Color(0xFFE67E22)

@Composable
private fun Note(text: String, icon: ImageVector, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant, tag: String? = null) {
    Row(if (tag != null) Modifier.testTag(tag) else Modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.padding(end = 4.dp).size(16.dp), tint = tint)
        Text(text, style = MaterialTheme.typography.bodySmall, color = tint)
    }
}

@Composable
private fun Footer(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

/** Port of iOS EpisodePollSection (Episode detail). Results show after voting, or always when this build can't vote. */
@Composable
fun EpisodePollSection(poll: PollDefinition, community: CommunityService) {
    val state by community.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val myVote = state.myVotes[poll.id]
    val canVote = community.canWrite
    val isBusy = poll.id in state.busy
    LaunchedEffect(poll.id) { community.loadPoll(poll) }
    MrtSection("Listener Poll", Modifier.testTag("listener-poll")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(poll.question, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (isBusy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        }
        val results = state.pollResults[poll.id]
        if (community.canSeeResults(poll) && results != null) {
            results.rows.forEach { row ->
                val isMine = row.option.id == myVote
                Column(
                    Modifier.fillMaxWidth()
                        .clickable(enabled = canVote && !isBusy && !isMine) { scope.launch { community.vote(poll, row.option) } }
                        .testTag("poll-result-${row.option.id}"),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(row.option.label, fontWeight = if (isMine) FontWeight.Bold else FontWeight.Normal)
                        if (isMine) {
                            Icon(Icons.Filled.CheckCircle, null,
                                Modifier.padding(start = 6.dp).size(18.dp).semantics { contentDescription = "Your vote" }, tint = teal())
                        }
                        Spacer(Modifier.weight(1f))
                        Text("${(row.fraction * 100).roundToInt()}%", fontWeight = FontWeight.SemiBold)
                        Text(" (${row.count})", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    LinearProgressIndicator(progress = { row.fraction.toFloat() }, modifier = Modifier.fillMaxWidth(), color = if (isMine) teal() else peach())
                }
            }
            Text(if (results.total == 1) "1 vote" else "${results.total} votes", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("poll-total"))
        } else {
            poll.options.forEach { option ->
                OutlinedButton(
                    onClick = { scope.launch { community.vote(poll, option) } },
                    enabled = canVote && !isBusy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp).testTag("poll-option-${option.id}"),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = teal()),
                ) { Text(option.label, fontWeight = FontWeight.SemiBold) }
            }
            if (canVote) Note("Vote to see results", Icons.Filled.VisibilityOff)
        }
        if (!canVote) Note(CommunityCopy.READ_ONLY, Icons.Filled.VisibilityOff)
        state.errors[poll.id]?.let { Note(it, Icons.Filled.Warning, Warn, "poll-error") }
        Footer(if (myVote != null && canVote) CommunityCopy.POLL_FOOTER_CHANGE else CommunityCopy.POLL_FOOTER)
    }
}

/** Port of iOS ListenerRatingSection (Movie detail, below Your Rating). */
@Composable
fun ListenerRatingSection(movieId: String, myRating: Int?, community: CommunityService) {
    val state by community.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val aggregate = state.ratingAggregates[movieId]
    val shared = state.myRatings[movieId]
    val canWrite = community.canWrite
    val isBusy = movieId in state.busy
    LaunchedEffect(movieId) { community.loadRating(movieId) }
    MrtSection("Listener Rating", Modifier.testTag("listener-rating")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val average = aggregate?.average
            when {
                aggregate != null && average != null -> {
                    Text(HalfStars.label(average), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                        modifier = Modifier.testTag("listener-average"))
                    StarRating(aggregate.averageHalfStars, starSize = 18.dp, tint = peach())
                    Spacer(Modifier.weight(1f))
                    Text(if (aggregate.count == 1) "1 rating" else "${aggregate.count} ratings", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("listener-count"))
                }
                aggregate == null && state.errors[movieId] == null -> {
                    Text("Loading listener ratings…", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                }
                else -> Text("No listener ratings yet", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("listener-none"))
            }
        }
        if (shared != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Your listener rating", Modifier.weight(1f))
                StarRating(shared, starSize = 16.dp, tint = peach())
            }
        }
        if (canWrite) {
            if (myRating != null && myRating != shared) {
                TextButton(
                    onClick = { scope.launch { community.submitListenerRating(movieId, myRating) } },
                    enabled = !isBusy,
                    modifier = Modifier.testTag("share-listener-rating"),
                ) {
                    Icon(Icons.Filled.Groups, null, Modifier.padding(end = 8.dp))
                    Text(if (shared == null) "Add my ${HalfStars.label(myRating)} ★ rating to the listener average"
                        else "Update my listener rating to ${HalfStars.label(myRating)} ★")
                }
            } else if (myRating == null && shared == null) {
                Text("Rate it under Your Rating to add your stars.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            Note(CommunityCopy.READ_ONLY, Icons.Filled.VisibilityOff)
        }
        state.errors[movieId]?.let { Note(it, Icons.Filled.Warning, Warn, "rating-error") }
        Footer(CommunityCopy.RATING_FOOTER)
    }
}
