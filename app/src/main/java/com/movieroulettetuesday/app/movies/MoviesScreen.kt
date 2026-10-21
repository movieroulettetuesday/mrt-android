package com.movieroulettetuesday.app.movies

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.movieroulettetuesday.app.catalog.HexColor
import com.movieroulettetuesday.app.catalog.Movie
import com.movieroulettetuesday.app.catalog.MovieCatalog
import com.movieroulettetuesday.app.catalog.StreamingInfo
import com.movieroulettetuesday.app.episodes.ArtworkResolver
import com.movieroulettetuesday.app.episodes.PodcastEpisode
import com.movieroulettetuesday.app.roulette.SpinStats
import com.movieroulettetuesday.app.ui.ArtworkImage
import com.movieroulettetuesday.app.ui.theme.BrandColors
import kotlinx.coroutines.launch

data class MoviesInputs(
    val catalog: MovieCatalog?,
    val ratings: PersonalRatings,
    val stats: SpinStats,
    val services: MyServices,
    val episodesByGUID: Map<String, PodcastEpisode> = emptyMap(),
    val showArtwork: String? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoviesScreen(
    inputs: MoviesInputs,
    onOpenMovie: (String) -> Unit,
    onSuggest: () -> Unit,
    onRefresh: suspend () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var categoryId by rememberSaveable { mutableStateOf<String?>(null) }
    var listFilter by rememberSaveable { mutableStateOf(MovieListFilter.All) }
    var sort by rememberSaveable { mutableStateOf(MovieSort.Title) }
    var onMyServices by rememberSaveable { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val catalog = inputs.catalog
    val q = MovieQuery(query, categoryId, listFilter, sort, onMyServices)

    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Movies") },
            actions = {
                CategoryMenu(catalog, categoryId) { categoryId = it }
                ListFilterMenu(listFilter) { listFilter = it }
                SortMenu(sort) { sort = it }
            },
        )
        if (catalog == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Text("Loading catalog…", Modifier.padding(top = 8.dp))
                }
            }
            return@Column
        }
        val rows = q.apply(catalog.movies, inputs.ratings, inputs.stats, inputs.services)
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { scope.launch { refreshing = true; try { onRefresh() } finally { refreshing = false } } },
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(Modifier.fillMaxSize().testTag("movies-list")) {
                item {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Title, genre, episode") },
                        leadingIcon = { Icon(Icons.Filled.Search, null) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(16.dp, 4.dp).testTag("movies-search"),
                    )
                }
                if (catalog.movies.any { it.streaming != null }) {
                    item {
                        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(onMyServices, { onMyServices = !onMyServices }, { Text("On my services") }, Modifier.testTag("chip-my-services"))
                            FilterChip(sort == MovieSort.AvailableFirst, {
                                sort = if (sort == MovieSort.AvailableFirst) MovieSort.Title else MovieSort.AvailableFirst
                            }, { Text("Available first") }, Modifier.testTag("chip-available-first"))
                        }
                        if (onMyServices && inputs.services.isEmpty) {
                            Text("Choose your services in Settings › My Services.", style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(16.dp, 4.dp))
                        }
                    }
                }
                if (q.isPlain) {
                    item {
                        Row(Modifier.fillMaxWidth().clickable(onClick = onSuggest).padding(16.dp, 12.dp).testTag("suggest-row"),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Lightbulb, null, tint = BrandColors.PeachLight)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Suggest a movie", fontWeight = FontWeight.SemiBold)
                                Text("Send the crew a pick for the wheel", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        HorizontalDivider()
                    }
                }
                items(rows, key = { it.id }) { movie ->
                    MovieRow(movie, catalog, inputs, onClick = { onOpenMovie(movie.id) })
                    HorizontalDivider(Modifier.padding(start = 80.dp))
                }
                if (rows.isEmpty()) item { EmptyState(query, listFilter) }
                item {
                    val total = catalog.movies.size
                    Text(
                        if (listFilter == MovieListFilter.All) "${rows.size} of $total movies" else "${rows.size} of $total movies · ${listFilter.label}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(16.dp).testTag("movies-footer"),
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyState(query: String, filter: MovieListFilter) {
    val (title, message) = when {
        query.isNotEmpty() -> "No Results for “$query”" to "Check the spelling or try a new search."
        filter == MovieListFilter.Watchlist -> "Watchlist Is Empty" to "Open a movie and set its status to Want to watch."
        filter == MovieListFilter.Watched -> "Nothing Marked Watched" to "Open a movie to rate it or mark it watched."
        filter == MovieListFilter.Rated -> "No Ratings Yet" to "Open a movie to rate it or mark it watched."
        filter == MovieListFilter.NotSpunYet -> "You’ve Spun Them All" to "Every movie in this view has come up on the wheel."
        else -> "No Matches" to "Try a different category or service filter."
    }
    Column(Modifier.fillMaxWidth().padding(32.dp).testTag("movies-empty"), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Filled.Movie, null)
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun MovieRow(movie: Movie, catalog: MovieCatalog, inputs: MoviesInputs, onClick: () -> Unit) {
    val entry = inputs.ratings.entry(movie.id)
    val spins = inputs.stats.spinCount(movie.id)
    val service = StreamingCatalog.matchingServices(movie, inputs.services).firstOrNull()
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp, 8.dp).testTag("movie-row-${movie.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtworkImage(ArtworkResolver.candidates(movie, inputs.episodesByGUID, inputs.showArtwork), 52.dp, Icons.Filled.Movie, cornerRadius = 10.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(movie.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                movie.categories.take(3).forEach { id ->
                    val c = catalog.category(id)?.colorHex?.let(HexColor::parse)?.let { Color(it.argb) } ?: MaterialTheme.colorScheme.outline
                    Box(Modifier.padding(end = 3.dp).size(8.dp).background(c, CircleShape))
                }
                Text(catalog.subtitle(movie), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 3.dp))
            }
        }
        if (service != null) ProviderLogo(service, 30.dp, Modifier.padding(start = 6.dp))
        entry?.halfStars?.let { h ->
            Row(Modifier.padding(start = 6.dp).semantics(mergeDescendants = true) { contentDescription = "Your rating ${HalfStars.label(h)} of 5" },
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Star, null, Modifier.size(14.dp), tint = Color(0xFFF39C12))
                Text(HalfStars.label(h), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color(0xFFF39C12))
            }
        }
        when (entry?.status) {
            WatchStatus.WantToWatch -> Icon(Icons.Filled.Bookmark, "On your watchlist", Modifier.padding(start = 6.dp).size(16.dp), tint = BrandColors.PeachLight)
            WatchStatus.Watched -> Icon(Icons.Filled.CheckCircle, "Watched", Modifier.padding(start = 6.dp).size(16.dp), tint = BrandColors.TealLight)
            null -> Unit
        }
        if (spins > 0) {
            Text("×$spins", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp))
        }
    }
}

/** TMDB provider logo, or the name on a teal tile while loading / on failure (iOS ProviderLogo). */
@Composable
fun ProviderLogo(provider: StreamingInfo.Provider, size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    Box(modifier.semantics(mergeDescendants = true) { contentDescription = provider.name }) {
        val url = provider.logoURL
        if (url != null) {
            ArtworkImage(listOf(url), size, Icons.Filled.Movie, cornerRadius = size * 0.22f)
        } else {
            Box(Modifier.size(size).background(BrandColors.TealDark, androidx.compose.foundation.shape.RoundedCornerShape(size * 0.22f)),
                contentAlignment = Alignment.Center) {
                Text(provider.name, color = Color.White, style = MaterialTheme.typography.labelSmall, maxLines = 2)
            }
        }
    }
}

@Composable
private fun CategoryMenu(catalog: MovieCatalog?, selected: String?, onSelect: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.testTag("menu-category")) {
            Icon(Icons.Filled.FilterList, selected?.let { catalog?.category(it)?.name } ?: "Category")
        }
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem({ Text("All categories") }, { onSelect(null); open = false },
                leadingIcon = { if (selected == null) Icon(Icons.Filled.Check, null) })
            catalog?.categories?.forEach { cat ->
                DropdownMenuItem({ Text(cat.displayName) }, { onSelect(cat.id); open = false },
                    leadingIcon = { if (selected == cat.id) Icon(Icons.Filled.Check, null) },
                    modifier = Modifier.testTag("category-${cat.id}"))
            }
        }
    }
}

@Composable
private fun ListFilterMenu(selected: MovieListFilter, onSelect: (MovieListFilter) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.testTag("menu-show")) {
            Icon(if (selected == MovieListFilter.All) Icons.Filled.BookmarkBorder else Icons.Filled.Bookmark,
                if (selected == MovieListFilter.All) "Show" else selected.label)
        }
        DropdownMenu(open, { open = false }) {
            MovieListFilter.entries.forEach { f ->
                DropdownMenuItem({ Text(f.label) }, { onSelect(f); open = false },
                    leadingIcon = { if (selected == f) Icon(Icons.Filled.Check, null) }, modifier = Modifier.testTag("show-${f.name}"))
            }
        }
    }
}

@Composable
private fun SortMenu(selected: MovieSort, onSelect: (MovieSort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.testTag("menu-sort")) { Icon(Icons.AutoMirrored.Filled.Sort, "Sort") }
        DropdownMenu(open, { open = false }) {
            MovieSort.entries.forEach { s ->
                DropdownMenuItem({ Text(s.label) }, { onSelect(s); open = false },
                    leadingIcon = { if (selected == s) Icon(Icons.Filled.Check, null) }, modifier = Modifier.testTag("sort-${s.name}"))
            }
        }
    }
}
