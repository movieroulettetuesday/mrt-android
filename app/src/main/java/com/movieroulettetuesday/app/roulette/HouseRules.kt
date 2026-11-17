package com.movieroulettetuesday.app.roulette

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.movieroulettetuesday.app.R
import com.movieroulettetuesday.app.widget.SpinOfTheDayWidget

/**
 * Android House Rules (plan §3c, with Pick for Me; Matthew Nov 17: also explain the less obvious
 * features that exist in this build). All wording is here so it is easy to change when the iOS
 * copy lands. Shortcut labels, Assistant synonyms and the widget name come from the same string
 * resources as res/xml/shortcuts.xml and the widget receiver; HouseRulesTest fails if a shortcut,
 * synonym, widget or widget size is added without a row here.
 */
object HouseRules {
    data class Section(val title: String, val rows: List<String>, val footer: String? = null)

    /** One static launcher shortcut, as declared in res/xml/shortcuts.xml. */
    data class Shortcut(val id: String, val shortLabel: String, val longLabel: String, val assistantNames: List<String>)

    data class Labels(val appName: String, val widgetName: String, val shortcuts: List<Shortcut>)

    const val TITLE = "House Rules"
    const val FOOTER = "Dinner, discussion, and the wheel. Spin responsibly."

    /** What each static shortcut does, by shortcutId. A new shortcut needs a line here (test-enforced). */
    val shortcutActions: Map<String, String> = mapOf(
        "spin" to "spins the wheel and shows the result",
        "latest" to "opens the latest episode",
        "watchlist" to "opens your watchlist",
        "suggest" to "picks a movie for you, with the reason",
    )

    /** One line per widget size the code renders (SpinOfTheDayWidget.sizeMode). Test-enforced. */
    val widgetSizes: Map<DpSize, String> = mapOf(
        SpinOfTheDayWidget.SMALL to "Small: today’s pick, plus a line when there’s a new episode.",
        SpinOfTheDayWidget.MEDIUM to "Wide: today’s pick beside the latest episode (or your last spin).",
        SpinOfTheDayWidget.LARGE to "Large: today’s pick and its genre, the latest episode, and your last spin.",
    )

    fun sections(labels: Labels): List<Section> {
        val app = labels.appName
        return listOf(
            Section(
                "The Wheel",
                listOf(
                    "Tap SPIN in the center, or anywhere on the wheel, to spin. While it spins, tap STOP in the center to stop early.",
                    "The arrow at the top picks a theme category, like Horror, Criterion, Love Hurts or Listener Picks.",
                    "Then a movie from that category is drawn. Recent spins are less likely to repeat, and every title was discussed on the show.",
                ),
            ),
            Section(
                "Spin or Pick for Me",
                listOf(
                    "Spin is pure chance: the wheel picks a category, then a movie from it.",
                    "Pick for Me is on purpose: it picks one you haven't watched, favouring your watchlist and your services, and tells you why.",
                    "The reason only says what the app knows: your watchlist, your ratings, and where it's streaming.",
                    "Runs on your device with Gemini Nano. Nothing leaves your device. If your phone can't run it, the button is hidden.",
                ),
            ),
            Section(
                "Search",
                listOf(
                    "Search Movies or Episodes by title, year, episode (S4E39), genre or service.",
                    "Or ask a question, like “comedies from the 90s on Netflix”. It becomes filter chips; tap a chip's × to remove it.",
                    "Without Gemini Nano, search matches keywords only.",
                ),
            ),
            Section(
                "Your Lists",
                listOf(
                    "Want to watch puts a movie on your watchlist.",
                    "Rating a movie marks it watched. You can clear a rating any time.",
                    "Tap a star to rate; the left half of a star gives a half star. Drag across the stars to slide, and tap your rating again to clear it.",
                ),
            ),
            Section("Assistant and Shortcuts", assistantRows(labels)),
            Section(
                "Home-Screen Widget",
                listOf(
                    "${labels.widgetName} shows today’s pick from the wheel (the same for everyone that day) and the latest episode. Tap it to open that movie.",
                ) + widgetSizes.values + listOf(
                    "To add it, long-press an empty spot on your home screen, tap Widgets, then find $app.",
                ),
            ),
            Section(
                "Good to Know",
                listOf(
                    "Links that start with mrt:// open the app at a spin, a movie, an episode, your watchlist, a pick or a search, for example mrt://spin.",
                    "On Android 12 and later, movies and episodes are added to your phone’s on-device search, so they can show up in system search if your launcher supports it.",
                ),
                footer = FOOTER,
            ),
        )
    }

    private fun assistantRows(labels: Labels): List<String> {
        val app = labels.appName
        val shorts = labels.shortcuts.map { it.shortLabel }
        val names = labels.shortcuts.flatMap { it.assistantNames }.distinct()
        return buildList {
            add("Long-press the app icon for ${join(shorts)}.")
            labels.shortcuts.forEach { s ->
                add("${s.longLabel}: ${shortcutActions[s.id] ?: "opens $app"}.")
            }
            add("Movies you open from Pick for Me or a link are added there too, up to 3.")
            add("“Spin $app” spins the wheel and opens the result.")
            add("“Pick a movie for me in $app” gets a pick with a reason.")
            add("Google Assistant can also open these by name: ${join(names.map { "“$it”" })}. It can look up a movie by title, too. Support depends on your phone.")
        }
    }

    private fun join(items: List<String>): String = when (items.size) {
        0 -> ""
        1 -> items[0]
        else -> items.dropLast(1).joinToString(", ") + " and " + items.last()
    }

    /** Labels from the string resources res/xml/shortcuts.xml and the widget receiver use. */
    fun labels(context: Context): Labels {
        val r = context.resources
        fun s(id: Int) = r.getString(id)
        return Labels(
            appName = s(R.string.app_name),
            widgetName = s(R.string.widget_name),
            shortcuts = listOf(
                Shortcut("spin", s(R.string.shortcut_spin_short), s(R.string.shortcut_spin_long), listOf(s(R.string.shortcut_spin_feature))),
                Shortcut("latest", s(R.string.shortcut_latest_short), s(R.string.shortcut_latest_long), listOf(s(R.string.shortcut_latest_feature))),
                Shortcut("watchlist", s(R.string.shortcut_watchlist_short), s(R.string.shortcut_watchlist_long), listOf(s(R.string.shortcut_watchlist_feature))),
                Shortcut("suggest", s(R.string.shortcut_suggest_short), s(R.string.shortcut_suggest_long), r.getStringArray(R.array.shortcut_suggest_features).toList()),
            ),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HouseRulesSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val sections = remember { HouseRules.sections(HouseRules.labels(context)) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp)
                .testTag("house-rules"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(HouseRules.TITLE, Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                TextButton(onDismiss, Modifier.testTag("house-rules-done")) { Text("Done") }
            }
            sections.forEachIndexed { i, section ->
                if (i > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text(
                    section.title,
                    Modifier.semantics { heading() }.testTag("house-rules-section"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                section.rows.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                section.footer?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** Roulette overflow (⋮) → House Rules. */
@Composable
fun RouletteOverflowMenu(onHouseRules: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    IconButton({ open = true }, Modifier.testTag("roulette-overflow")) {
        Icon(Icons.Filled.MoreVert, contentDescription = "More options")
    }
    DropdownMenu(open, onDismissRequest = { open = false }) {
        DropdownMenuItem(
            text = { Text(HouseRules.TITLE) },
            onClick = {
                open = false
                onHouseRules()
            },
            modifier = Modifier.testTag("open-house-rules"),
        )
    }
}
