package com.movieroulettetuesday.app.stats

import com.movieroulettetuesday.app.catalog.displayColor
import android.text.format.DateUtils
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.movieroulettetuesday.app.catalog.HexColor
import com.movieroulettetuesday.app.catalog.MovieCatalog
import com.movieroulettetuesday.app.movies.HalfStars
import com.movieroulettetuesday.app.movies.PersonalRatings
import com.movieroulettetuesday.app.movies.PersonalRatingsSummary
import com.movieroulettetuesday.app.roulette.SpinHistoryStore
import com.movieroulettetuesday.app.ui.MrtSection
import com.movieroulettetuesday.app.ui.StarRating

/** Stats tab (iOS StatsView): this device's spins, favorites, your ratings, breakdowns, recent spins. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    log: SpinHistoryStore.Log,
    ratings: PersonalRatings,
    catalog: MovieCatalog?,
    modifier: Modifier = Modifier,
    now: Long = System.currentTimeMillis(),
) {
    val snap = StatsSnapshot.of(log, catalog)
    val mine = PersonalRatingsSummary.of(ratings, catalog)
    Column(modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Stats") })
        if (snap.totalSpins == 0 && mine.ratedCount == 0 && mine.watchlistCount == 0 && mine.watchedCount == 0) {
            Box(Modifier.fillMaxSize().padding(24.dp).testTag("stats-empty"), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.BarChart, null)
                    Text("No Stats Yet", style = MaterialTheme.typography.titleMedium)
                    Text("Spin the wheel or rate a movie to see your stats.")
                }
            }
            return@Column
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 700.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                fun catColor(id: String): Color =
                    Color((catalog?.category(id)?.displayColor ?: HexColor.parse(com.movieroulettetuesday.app.catalog.CategoryPalette.hex(id))!!).argb)
                if (snap.totalSpins > 0) {
                    MrtSection("This Device", Modifier.testTag("stats-overview")) {
                        Labeled("Total spins", "${snap.totalSpins}")
                        Labeled("Different movies", "${snap.distinctMovies}")
                    }
                    MrtSection("Most Spun") {
                        snap.mostSpunMovie?.let { top ->
                            Labeled("Movie", "×${top.count}", catalog?.movie(top.id)?.title ?: top.id)
                        }
                        snap.mostSpunCategory?.let { top ->
                            Labeled("Category", "×${top.count}", catalog?.category(top.id)?.displayName ?: top.id, dot = catColor(top.id))
                        }
                    }
                }
                if (mine.ratedCount > 0 || mine.watchlistCount > 0 || mine.watchedCount > 0) {
                    MrtSection("Your Ratings", Modifier.testTag("stats-ratings")) {
                        Labeled("Movies rated", "${mine.ratedCount}")
                        mine.averageStars?.let { Labeled("Your average", "${HalfStars.label(it)} ★") }
                        Labeled("Watched", "${mine.watchedCount}")
                        Labeled("On your watchlist", "${mine.watchlistCount}")
                        mine.topRated.forEach { r ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(r.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                StarRating(r.halfStars, starSize = 14.dp)
                            }
                        }
                        if (mine.topRated.isNotEmpty()) {
                            Text("Your top-rated movies.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (snap.categoryBreakdown.isNotEmpty()) {
                    MrtSection("By Category") {
                        val max = snap.categoryBreakdown.first().count.coerceAtLeast(1)
                        snap.categoryBreakdown.forEach { row ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(10.dp).background(catColor(row.id), CircleShape))
                                Spacer(Modifier.width(8.dp))
                                Text(catalog?.category(row.id)?.displayName ?: row.id, Modifier.weight(1f))
                                Text("×${row.count}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(8.dp))
                                Box(Modifier.width(64.dp)) {
                                    Box(Modifier.width((64f * row.count / max).dp).height(8.dp)
                                        .background(catColor(row.id).copy(alpha = 0.55f), RoundedCornerShape(50)))
                                }
                            }
                        }
                    }
                }
                if (snap.genreBreakdown.isNotEmpty()) {
                    MrtSection("By Genre") { snap.genreBreakdown.take(12).forEach { Labeled(it.id, "×${it.count}") } }
                }
                if (snap.totalSpins > 0 && snap.recent.isNotEmpty()) {
                    MrtSection("Recent Spins", Modifier.testTag("stats-recent")) {
                        snap.recent.forEach { spin ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(8.dp).background(catColor(spin.categoryId), CircleShape))
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(catalog?.movie(spin.movieId)?.title ?: spin.movieId, fontWeight = FontWeight.SemiBold)
                                    Text(catalog?.category(spin.categoryId)?.displayName ?: spin.categoryId,
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(DateUtils.getRelativeTimeSpanString(spin.timestampEpochMs, now, DateUtils.MINUTE_IN_MILLIS).toString(),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Labeled(label: String, value: String, detail: String? = null, dot: Color? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            if (detail != null) {
                Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (dot != null) { Box(Modifier.size(10.dp).background(dot, CircleShape)); Spacer(Modifier.width(8.dp)) }
                    Text(detail)
                }
            } else {
                Text(label)
            }
        }
        Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
