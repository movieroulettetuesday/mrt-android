package com.movieroulettetuesday.app.episodes

import com.movieroulettetuesday.app.ui.selectedRow

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.movieroulettetuesday.app.catalog.MovieCatalog
import com.movieroulettetuesday.app.data.KeyValueStore
import com.movieroulettetuesday.app.ui.ArtworkImage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

object EpisodeDates {
    private val fmt = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)
    fun short(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String = fmt.format(Instant.ofEpochMilli(epochMs).atZone(zone))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EpisodesScreen(
    repository: EpisodeRepository,
    catalog: MovieCatalog?,
    settings: KeyValueStore,
    onOpenEpisode: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** Highlighted row in the two-pane (regular width) layout. */
    selectedGuid: String? = null,
) {
    val state by repository.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var groupingKey by remember { mutableStateOf(settings.getString(EpisodeRepository.GROUPING_KEY)) }
    var expansion by remember { mutableStateOf(EpisodeGroupExpansion.parse(settings.getString(EpisodeRepository.EXPANDED_KEY).orEmpty())) }
    val grouping = EpisodeGrouping.fromKey(groupingKey)

    LaunchedEffect(Unit) { repository.load() }
    LaunchedEffect(state.episodes) { repository.markViewed() }

    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Episodes") },
            actions = {
                if (state.loadState == EpisodeRepository.LoadState.Loaded(fromCache = true)) {
                    Text("Offline", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(end = 16.dp).testTag("episodes-offline"))
                }
            },
        )
        val ls = state.loadState
        when {
            state.episodes.isEmpty() && (ls is EpisodeRepository.LoadState.Idle || ls is EpisodeRepository.LoadState.Loading) ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.padding(4.dp))
                        Text("Loading episodes…")
                    }
                }
            state.episodes.isEmpty() && ls is EpisodeRepository.LoadState.Failed ->
                Box(Modifier.fillMaxSize().padding(24.dp).testTag("episodes-failed"), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Filled.WifiOff, null)
                        Text("Couldn’t Load Episodes", style = MaterialTheme.typography.titleMedium)
                        Text(ls.message, style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = { scope.launch { repository.load(forceNetwork = true) } }) { Text("Try again") }
                    }
                }
            else -> PullToRefreshBox(
                isRefreshing = ls is EpisodeRepository.LoadState.Loading,
                onRefresh = { scope.launch { repository.load(forceNetwork = true) } },
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyColumn(Modifier.fillMaxSize().testTag("episodes-list")) {
                    item {
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(16.dp, 4.dp).testTag("episodes-grouping")) {
                            EpisodeGrouping.entries.forEachIndexed { i, g ->
                                SegmentedButton(
                                    selected = g == grouping,
                                    onClick = { groupingKey = g.key; settings.putString(EpisodeRepository.GROUPING_KEY, g.key) },
                                    shape = SegmentedButtonDefaults.itemShape(i, EpisodeGrouping.entries.size),
                                    modifier = Modifier.testTag("grouping-${g.key}"),
                                ) { Text(g.label) }
                            }
                        }
                    }
                    state.lastError?.let { err ->
                        item {
                            Row(Modifier.padding(16.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.WarningAmber, null, Modifier.padding(end = 8.dp))
                                Text(err, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    val groups = EpisodeGrouper.groups(state.episodes, grouping)
                    groups.forEachIndexed { index, group ->
                        val isNewest = index == 0
                        val expanded = grouping == EpisodeGrouping.All || expansion.isExpanded(group.id, isNewest)
                        if (grouping != EpisodeGrouping.All) {
                            item(key = "header-${group.id}") {
                                GroupHeader(group, expanded) {
                                    expansion = expansion.toggled(group.id, isNewest)
                                    settings.putString(EpisodeRepository.EXPANDED_KEY, expansion.serialized)
                                }
                            }
                        }
                        if (expanded) {
                            items(group.episodes, key = { "${group.id}/${it.guid}" }) { ep ->
                                EpisodeRow(ep, catalog, state.showArtworkURL, selected = ep.guid == selectedGuid) { onOpenEpisode(ep.guid) }
                                HorizontalDivider(Modifier.padding(start = 84.dp))
                            }
                        }
                    }
                    item {
                        val totals = EpisodeTotals.of(state.episodes, catalog)
                        Column(Modifier.padding(16.dp)) {
                            Text("${totals.feedEpisodes} episodes", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("episodes-count"))
                            state.lastFetchedAt?.let {
                                Text("Updated " + DateUtils.getRelativeTimeSpanString(it), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupHeader(group: EpisodeGroup, expanded: Boolean, onToggle: () -> Unit) {
    val countText = if (group.episodes.size == 1) "1 episode" else "${group.episodes.size} episodes"
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(16.dp, 12.dp).testTag("episode-group-${group.id}")
            .semantics(mergeDescendants = true) {
                heading()
                contentDescription = "${group.title}, $countText"
                stateDescription = if (expanded) "Expanded" else "Collapsed"
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(group.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Text(countText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Icon(Icons.Filled.ChevronRight, null, Modifier.rotate(if (expanded) 90f else 0f))
    }
}

@Composable
private fun EpisodeRow(episode: PodcastEpisode, catalog: MovieCatalog?, showArtwork: String?, selected: Boolean = false, onClick: () -> Unit) {
    val movie = catalog?.movieForEpisodeGuid(episode.guid)
    Row(Modifier.fillMaxWidth().selectedRow(selected, MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)).clickable(onClick = onClick).padding(16.dp, 10.dp).testTag("episode-row-${episode.guid}")) {
        ArtworkImage(ArtworkResolver.candidates(episode, showArtwork), 56.dp, Icons.Filled.Mic, cornerRadius = 10.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row {
                Text(episode.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                episode.seasonEpisodeLabel?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
                }
            }
            episode.pubDate?.let { Text(EpisodeDates.short(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (movie != null) {
                Text(catalog.subtitle(movie), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}
