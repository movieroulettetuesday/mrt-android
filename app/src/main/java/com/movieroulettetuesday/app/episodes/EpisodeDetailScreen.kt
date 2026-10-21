package com.movieroulettetuesday.app.episodes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.movieroulettetuesday.app.catalog.MovieCatalog
import com.movieroulettetuesday.app.catalog.ShowInfo
import com.movieroulettetuesday.app.ui.ArtworkImage
import com.movieroulettetuesday.app.ui.ExternalLinks
import com.movieroulettetuesday.app.ui.LinkRow
import com.movieroulettetuesday.app.ui.MrtSection

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EpisodeDetailScreen(
    guid: String,
    repository: EpisodeRepository,
    appleLinks: AppleEpisodeLinks,
    catalog: MovieCatalog?,
    onBack: () -> Unit,
    onOpenMovie: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
    /** Community poll section (Option A), when configured. */
    extra: @Composable ColumnScope.(PodcastEpisode) -> Unit = {},
) {
    val state by repository.state.collectAsStateWithLifecycle()
    val episode = state.episode(guid)
    val context = LocalContext.current
    val show = catalog?.showInfo ?: ShowInfo.MRT
    Column(modifier.fillMaxSize().testTag("episode-detail")) {
        TopAppBar(
            title = { Text(episode?.seasonEpisodeLabel ?: "Episode") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
        if (episode == null) {
            Text("Episode Not Found", Modifier.padding(24.dp))
            return@Column
        }
        LaunchedEffect(guid) { appleLinks.ensure(guid, show) }
        val appleMap by appleLinks.trackIds.collectAsStateWithLifecycle()
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Column(Modifier.widthIn(max = 700.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Column(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    ArtworkImage(ArtworkResolver.candidates(episode, state.showArtworkURL), 220.dp, Icons.Filled.Mic,
                        cornerRadius = 22.dp, modifier = Modifier.testTag("episode-artwork"))
                    SelectionContainer {
                        Text(episode.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 16.dp).testTag("episode-title"))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        episode.seasonEpisodeLabel?.let { Text(it, fontWeight = FontWeight.SemiBold) }
                        episode.pubDate?.let { Text(EpisodeDates.short(it), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
                if (episode.displaySummary.isNotEmpty() || episode.fanMailURL != null) {
                    MrtSection("About") {
                        if (episode.displaySummary.isNotEmpty()) {
                            SelectionContainer { Text(episode.displaySummary, Modifier.testTag("episode-about-text")) }
                        }
                        episode.fanMailURL?.let { url ->
                            TextButton(onClick = { ExternalLinks.open(context, url) }, modifier = Modifier.testTag("episode-fan-mail")) {
                                Icon(Icons.Filled.Mail, null, Modifier.padding(end = 8.dp))
                                Text("Send us fan mail")
                            }
                        }
                    }
                }
                val movie = catalog?.movieForEpisodeGuid(episode.guid)
                if (movie != null && onOpenMovie != null) {
                    MrtSection("Movie Discussed") {
                        Row(Modifier.fillMaxWidth().clickable { onOpenMovie(movie.id) }.testTag("episode-movie-link"),
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(movie.title, fontWeight = FontWeight.SemiBold)
                                Text(catalog.subtitle(movie), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Icon(Icons.Filled.ChevronRight, null)
                        }
                    }
                }
                extra(episode)
                MrtSection("Listen") {
                    val links = ListenLinks.forEpisode(episode, appleMap, show)
                    links.forEachIndexed { i, link ->
                        if (i > 0) HorizontalDivider()
                        val icon = when (link.id) {
                            "listen-apple" -> Icons.Filled.Headphones
                            "listen-spotify" -> Icons.Filled.GraphicEq
                            "listen-overcast" -> Icons.Filled.Radio
                            else -> Icons.Filled.Language
                        }
                        LinkRow(link.title, link.detail, icon, link.tileHex, link.id) { ExternalLinks.open(context, link.url) }
                    }
                }
            }
        }
    }
}
