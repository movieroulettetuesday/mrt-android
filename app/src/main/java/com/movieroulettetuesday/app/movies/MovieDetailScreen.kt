package com.movieroulettetuesday.app.movies

import com.movieroulettetuesday.app.catalog.displayColor
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.movieroulettetuesday.app.catalog.HexColor
import com.movieroulettetuesday.app.catalog.Movie
import com.movieroulettetuesday.app.catalog.MovieCatalog
import com.movieroulettetuesday.app.catalog.StreamingInfo
import com.movieroulettetuesday.app.episodes.ArtworkResolver
import com.movieroulettetuesday.app.episodes.PodcastEpisode
import com.movieroulettetuesday.app.episodes.PodcastLinks
import com.movieroulettetuesday.app.roulette.EpisodeLabel
import com.movieroulettetuesday.app.ui.ArtworkImage
import com.movieroulettetuesday.app.ui.ExternalLinks
import com.movieroulettetuesday.app.ui.MrtSection
import com.movieroulettetuesday.app.ui.StarRating
import com.movieroulettetuesday.app.ui.theme.BrandColors

/** Movie page (iOS MoviePageView): header, episodes, Where to Watch, Your Rating, categories, Listen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MovieDetailScreen(
    movieId: String,
    inputs: MoviesInputs,
    ratingsStore: PersonalRatingsStore,
    onBack: (() -> Unit)?,
    onOpenEpisode: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
    /** Footer for Your Rating (changes when auto-share is on). */
    ratingFooter: String = "Saved on this device.",
    onRated: (Int?) -> Unit = {},
    /** Community listener-rating section (Option A), when configured. */
    extra: @Composable ColumnScope.(Movie) -> Unit = {},
) {
    val catalog = inputs.catalog
    val movie = catalog?.movie(movieId)
    val context = LocalContext.current
    Column(modifier.fillMaxSize().testTag("movie-detail")) {
        TopAppBar(
            title = { Text(movie?.title ?: "Movie", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
        if (movie == null || catalog == null) {
            Text("Movie Not Found", Modifier.padding(24.dp))
            return@Column
        }
        val spins = inputs.stats.spinCount(movie.id)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 700.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Column(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    ArtworkImage(ArtworkResolver.candidates(movie, inputs.episodesByGUID, inputs.showArtwork), 240.dp,
                        Icons.Filled.Movie, cornerRadius = 22.dp)
                    SelectionContainer {
                        Text(movie.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 14.dp).testTag("movie-title"))
                    }
                    Text(catalog.subtitle(movie), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center)
                    if (spins > 0) {
                        Text(if (spins == 1) "Spun 1 time" else "Spun $spins times", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("movie-spin-count"))
                    }
                }

                MrtSection("Episodes") {
                    if (movie.episodeIds.isEmpty()) {
                        Text("Not discussed on the show yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    movie.episodeIds.forEachIndexed { i, guid ->
                        if (i > 0) HorizontalDivider()
                        EpisodeLinkRow(guid, movie, inputs, onOpenEpisode)
                    }
                }

                WhereToWatchSection(movie, inputs.services, onOpen = { ExternalLinks.open(context, it) })

                MrtSection("Your Rating") {
                    val half = inputs.ratings.halfStars(movie.id)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Your rating", Modifier.weight(1f))
                        StarRating(half, Modifier.testTag("my-rating-stars")) {
                            ratingsStore.setHalfStars(it, movie.id)
                            onRated(it)
                        }
                    }
                    if (half != null) {
                        Text("${HalfStars.label(half)} of 5", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("my-rating-label"))
                    }
                    val status = inputs.ratings.status(movie.id)
                    val options = listOf<Pair<WatchStatus?, String>>(null to "None", WatchStatus.WantToWatch to "Want to watch", WatchStatus.Watched to "Watched")
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        options.forEachIndexed { i, (value, label) ->
                            SegmentedButton(status == value, { ratingsStore.setStatus(value, movie.id) },
                                SegmentedButtonDefaults.itemShape(i, options.size), Modifier.testTag("status-${value?.raw ?: "none"}")) {
                                Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    Text(ratingFooter, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                extra(movie)

                MrtSection("Categories") {
                    movie.categories.forEach { id ->
                        val cat = catalog.category(id)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val c = cat?.let { Color(it.displayColor.argb) } ?: MaterialTheme.colorScheme.outline
                            Box(Modifier.size(10.dp).background(c, CircleShape))
                            Spacer(Modifier.width(10.dp))
                            Text(cat?.displayName ?: id)
                        }
                    }
                }

                ListenSection(movie, catalog, inputs.episodesByGUID) { ExternalLinks.open(context, it) }
            }
        }
    }
}

@Composable
private fun EpisodeLinkRow(guid: String, movie: Movie, inputs: MoviesInputs, onOpenEpisode: ((String) -> Unit)?) {
    val ep = inputs.episodesByGUID[guid]
    val rowMod = if (onOpenEpisode != null) Modifier.clickable { onOpenEpisode(guid) } else Modifier
    Row(Modifier.fillMaxWidth().then(rowMod).testTag("movie-episode-$guid"), verticalAlignment = Alignment.CenterVertically) {
        if (ep != null) {
            ArtworkImage(ArtworkResolver.candidates(ep, inputs.showArtwork), 44.dp, Icons.Filled.Mic, cornerRadius = 8.dp)
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(EpisodeLabel.title(guid, movie, inputs.episodesByGUID), fontWeight = FontWeight.SemiBold)
            Text(ep?.seasonEpisodeLabel ?: "Episode", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (onOpenEpisode != null) Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.outline)
    }
}

/** Stream / Free / Rent or buy rows, attribution, TMDB link (always shown). */
@Composable
fun WhereToWatchSection(movie: Movie, services: MyServices, onOpen: (String) -> Unit) {
    val display = WhereToWatchDisplay.of(movie)
    val s = movie.streaming ?: StreamingInfo()
    Column(Modifier.testTag("where-to-watch")) {
        MrtSection("Where to Watch · ${display.region}") {
            if (display.showsEmptyMessage) {
                Text(WhereToWatchDisplay.EMPTY_MESSAGE, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("streaming-empty"))
            } else {
                ProviderRow("Stream", s.stream, services, "stream")
                ProviderRow("Free", s.free, services, "free")
                ProviderRow("Rent or buy", StreamingCatalog.rentOrBuy(s), services, "rent-buy")
            }
            display.link?.let { link ->
                TextButton(onClick = { onOpen(link) }, modifier = Modifier.testTag("streaming-tmdb-link")) {
                    Text("All options on TMDB", Modifier.weight(1f))
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(16.dp))
                }
            }
        }
        display.footer?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp, start = 4.dp))
        }
    }
}

@Composable
private fun ProviderRow(title: String, providers: List<StreamingInfo.Provider>, services: MyServices, id: String) {
    if (providers.isEmpty()) return
    Column(Modifier.testTag("streaming-$id"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (providers.any { services.contains(it.id) }) {
                Text("Your service", color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.background(BrandColors.TealDark, RoundedCornerShape(50)).padding(10.dp, 4.dp))
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            providers.forEach { p ->
                Column(Modifier.width(64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    ProviderLogo(p, 44.dp)
                    Text(p.name, style = MaterialTheme.typography.labelSmall, maxLines = 2, textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ListenSection(movie: Movie, catalog: MovieCatalog, episodesByGUID: Map<String, PodcastEpisode>, onOpen: (String) -> Unit) {
    val show = catalog.showInfo
    val title = movie.episodeTitle ?: movie.episodeIds.firstOrNull()?.let { EpisodeLabel.title(it, movie, episodesByGUID) }
    MrtSection("Listen") {
        if (title != null) {
            PodcastLinks.appleSearch(title, show)?.let { url ->
                TextButton({ onOpen(url) }, Modifier.testTag("movie-listen-apple")) { Icon(Icons.Filled.Headphones, null); Text("  Find on Apple Podcasts") }
            }
            PodcastLinks.spotifySearch(title, show)?.let { url ->
                TextButton({ onOpen(url) }, Modifier.testTag("movie-listen-spotify")) { Icon(Icons.Filled.GraphicEq, null); Text("  Find on Spotify") }
            }
        }
        movie.episodeIds.firstOrNull()?.let { PodcastLinks.buzzsproutEpisode(it, show) }?.let { url ->
            TextButton({ onOpen(url) }, Modifier.testTag("movie-listen-buzzsprout")) { Icon(Icons.Filled.Language, null); Text("  Open on Buzzsprout") }
        }
    }
}
