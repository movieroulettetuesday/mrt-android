package com.movieroulettetuesday.app.search.nl

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.movieroulettetuesday.app.movies.HalfStars
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** One removable chip per set field (title, year range, genre, …). `kind` has no chip: it only picks the list. */
data class NLChip(val field: Field, val label: String) {
    enum class Field { Title, Year, Genre, Category, Provider, Free, Rating, Watched, Episode, MyServices }
}

fun NLSearchFilter.chips(): List<NLChip> = buildList {
    title?.let { add(NLChip(NLChip.Field.Title, "Title: $it")) }
    if (season != null || episode != null) {
        add(NLChip(NLChip.Field.Episode, listOfNotNull(season?.let { "S$it" }, episode?.let { "E$it" }).joinToString("")))
    }
    when {
        yearMin != null && yearMax != null -> add(NLChip(NLChip.Field.Year, if (yearMin == yearMax) "$yearMin" else "$yearMin–$yearMax"))
        yearMin != null -> add(NLChip(NLChip.Field.Year, "$yearMin or later"))
        yearMax != null -> add(NLChip(NLChip.Field.Year, "$yearMax or earlier"))
    }
    genre?.let { add(NLChip(NLChip.Field.Genre, it)) }
    category?.let { add(NLChip(NLChip.Field.Category, it)) }
    provider?.let { add(NLChip(NLChip.Field.Provider, "On $it")) }
    if (freeOnly == true) add(NLChip(NLChip.Field.Free, "Free"))
    val ratingLabel = when {
        unrated == true -> "Unrated"
        ratingMin != null && ratingMax != null && ratingMin == ratingMax -> "Rated ${HalfStars.label(ratingMin)}★"
        ratingMin != null && ratingMax != null -> "Rated ${HalfStars.label(ratingMin)}–${HalfStars.label(ratingMax)}★"
        ratingMin != null -> "Rated ${HalfStars.label(ratingMin)}★ or more"
        ratingMax != null -> "Rated ${HalfStars.label(ratingMax)}★ or less"
        unrated == false -> "Rated"
        else -> null
    }
    ratingLabel?.let { add(NLChip(NLChip.Field.Rating, it)) }
    watched?.let {
        add(NLChip(NLChip.Field.Watched, when (it) {
            NLSearchFilter.Watched.Watched -> "Watched"
            NLSearchFilter.Watched.Unwatched -> "Unwatched"
            NLSearchFilter.Watched.Watchlist -> "Watchlist"
        }))
    }
    if (onMyServices == true) add(NLChip(NLChip.Field.MyServices, "On my services"))
}

/** The removable chips, shown under the search field only when a parse succeeded on a supported device. */
@Composable
fun NLChipsRow(filter: NLSearchFilter, onRemove: (NLChip.Field) -> Unit, modifier: Modifier = Modifier) {
    val chips = filter.chips()
    if (chips.isEmpty()) return
    Row(
        modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 2.dp).testTag("nl-chips"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        chips.forEach { chip ->
            InputChip(
                selected = true,
                onClick = { onRemove(chip.field) },
                label = { Text(chip.label) },
                trailingIcon = { Icon(Icons.Filled.Close, "Remove ${chip.label}") },
                modifier = Modifier.testTag("nl-chip-${chip.field.name}"),
            )
        }
    }
}

/** A query and the filter parsed from it (chips). Saved across rotation and compact ↔ regular. */
data class NLResolved(val query: String, val filter: NLSearchFilter)

private val savedJson = Json { ignoreUnknownKeys = true }

private val ResolvedSaver = listSaver<MutableState<NLResolved?>, String>(
    save = { s -> s.value?.let { listOf(it.query, it.filter.toJson().toString()) } ?: emptyList() },
    restore = { list ->
        val restored = if (list.size == 2) {
            runCatching { NLResolved(list[0], NLSearchFilter.fromSavedJson(savedJson.parseToJsonElement(list[1]).jsonObject)) }.getOrNull()
        } else {
            null
        }
        mutableStateOf(restored)
    },
)

/**
 * Per-list NL search state: model status (only AVAILABLE shows chips), the parsed filter for the current
 * query, chip removal, and the list-switch hand-off.
 */
@Stable
class NLSearchController internal constructor(
    private val engine: NLSearchEngine,
    private val list: NLSearchFilter.Kind,
    private val resolvedState: MutableState<NLResolved?>,
    private val onDownloadable: () -> Unit,
) {
    var status by mutableStateOf(NLModelStatus.Unavailable)
        private set

    /** Any AI affordance (chips, list switch) may show. False on unsupported / not-ready devices. */
    val aiVisible: Boolean get() = status == NLModelStatus.Available

    /** The non-empty filter to apply for [query], or null for keyword CatalogSearch. */
    fun filterFor(query: String): NLSearchFilter? =
        resolvedState.value?.takeIf { aiVisible && it.query == query }?.filter?.takeIf { !it.isEmpty }

    suspend fun refreshStatus() {
        status = engine.status()
        if (status == NLModelStatus.Downloadable) onDownloadable()
    }

    /**
     * Text placed in the field from outside. A list switch hands over its chips ([filter]): show them without
     * parsing again. An `mrt://search` deep link has no chips (null) and stays keyword search, as before 1.1 NL.
     */
    fun seed(query: String, filter: NLSearchFilter?) {
        resolvedState.value = NLResolved(query, filter ?: NLSearchFilter.EMPTY)
    }

    fun clear() { resolvedState.value = null }

    fun remove(field: NLChip.Field) {
        val r = resolvedState.value ?: return
        resolvedState.value = r.copy(filter = r.filter.removing(field))
    }

    /**
     * Debounce, parse and validate [query]. Run in `LaunchedEffect(query)` so typing cancels it. A
     * `kind` naming the other list calls [onSwitch] instead of showing chips here (shared switch rule).
     */
    suspend fun run(query: String, onSwitch: (NLSearchFilter.Kind, String, NLSearchFilter) -> Unit) {
        if (query.isBlank() || resolvedState.value?.query == query) return
        resolvedState.value = null
        if (!aiVisible) {
            // Unsupported or not-ready: re-check once per debounced query (a download can finish mid-session).
            kotlinx.coroutines.delay(engine.debounceMs)
            refreshStatus()
            if (!aiVisible) return
        }
        val outcome = engine.resolve(query)
        if (outcome is NLOutcome.Keyword && outcome.reason == NLOutcome.Reason.ModelUnavailable) refreshStatus()
        val filter = (outcome as? NLOutcome.Parsed)?.filter ?: return
        val target = NLListSwitch.target(filter.kind, list)
        if (target != null) {
            onSwitch(target, query, filter)
        } else {
            resolvedState.value = NLResolved(query, filter)
        }
    }
}

@Composable
fun rememberNLSearch(
    model: NLSearchModel,
    providerNames: List<String>,
    list: NLSearchFilter.Kind,
    onDownloadable: () -> Unit = {},
): NLSearchController {
    val resolved = rememberSaveable(saver = ResolvedSaver) { mutableStateOf<NLResolved?>(null) }
    val names by androidx.compose.runtime.rememberUpdatedState(providerNames)
    return remember(model, list) {
        NLSearchController(NLSearchEngine(model, { names }), list, resolved, onDownloadable)
    }
}
