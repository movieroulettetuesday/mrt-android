package com.movieroulettetuesday.app.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider as FixedColor
import com.movieroulettetuesday.app.BuildConfig
import com.movieroulettetuesday.app.MainActivity
import com.movieroulettetuesday.app.R
import com.movieroulettetuesday.app.catalog.CatalogCache
import com.movieroulettetuesday.app.catalog.CatalogJson
import com.movieroulettetuesday.app.catalog.HexColor
import com.movieroulettetuesday.app.catalog.LocalCatalog
import com.movieroulettetuesday.app.catalog.RemoteCatalog
import com.movieroulettetuesday.app.catalog.displayHex
import com.movieroulettetuesday.app.shared.SharedPalette
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What one widget render shows (iOS `SpinOfTheDayEntry`). */
data class WidgetEntry(
    val pick: SpinOfTheDay.Pick?,
    val latestEpisode: WidgetSnapshot.EpisodeInfo? = null,
    val lastSpin: WidgetSnapshot.SpinInfo? = null,
) {
    companion object {
        /** The app's last good GitHub copy (same build), else the bundled list; snapshot from the app. */
        fun load(context: Context, now: Long = System.currentTimeMillis()): WidgetEntry {
            val app = context.applicationContext
            val catalog = runCatching {
                LocalCatalog.load(CatalogCache(File(app.filesDir, CatalogCache.FILE_NAME), BuildConfig.VERSION_CODE.toString())) {
                    app.assets.open(RemoteCatalog.BUNDLED_ASSET).use { it.readBytes() }
                }
            }.getOrNull()
            val snapshot = WidgetSnapshotStore(File(app.filesDir, WidgetSnapshotStore.FILE_NAME)).load()
            return WidgetEntry(catalog?.let { SpinOfTheDay.pick(now, it) }, snapshot?.latestEpisode, snapshot?.lastSpin)
        }

        val sample = WidgetEntry(
            pick = SpinOfTheDay.Pick(
                CatalogJson.decode(
                    """{"categories":[{"id":"cat-prestige","name":"Prestige","colorHex":"#6D597A","movieIds":["the-outsiders-1983"]}],
                    "movies":[{"id":"the-outsiders-1983","title":"The Outsiders","year":1983,"genre":"Drama","categories":["cat-prestige"]}]}""",
                ).movies[0],
                null,
                "sample",
            ),
            latestEpisode = WidgetSnapshot.EpisodeInfo("Buzzsprout-19913482", "The Outsiders", 4, 34),
        )
    }
}

/**
 * Home-screen "Spin of the Day" widget, a port of iOS `SpinOfTheDayWidget` (small / medium / large).
 * Follows the system light/dark setting like the iOS widget. Tap opens today's pick.
 */
class SpinOfTheDayWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(SMALL, MEDIUM, LARGE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val entry = withContext(Dispatchers.IO) { WidgetEntry.load(context) }
        provideContent { WidgetContent(entry) }
    }

    companion object {
        val SMALL = DpSize(150.dp, 150.dp)
        val MEDIUM = DpSize(300.dp, 150.dp)
        val LARGE = DpSize(300.dp, 300.dp)

        const val ACTION_OPEN_MOVIE = "com.movieroulettetuesday.app.OPEN_MOVIE"
        const val EXTRA_MOVIE_ID = "movieId"

        fun openIntent(context: Context, entry: WidgetEntry): Intent =
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                entry.pick?.let { action = ACTION_OPEN_MOVIE; putExtra(EXTRA_MOVIE_ID, it.movie.id) }
            }
    }
}

/** Clean Pro widget colors from mrt-shared `data/palette.json` (light / dark). */
internal object WidgetColors {
    private fun c(hex: String) = Color(HexColor.parse(hex)!!.argb)
    val accent = ColorProvider(day = c(SharedPalette.CleanPro.headingLight), night = c(SharedPalette.CleanPro.headingDark))
    val primary = ColorProvider(day = Color(0xFF1D2B2E), night = Color(0xFFF2F4F4))
    val secondary = ColorProvider(day = Color(0x991D2B2E), night = Color(0x99F2F4F4))
    val divider = ColorProvider(day = Color(0x1F1D2B2E), night = Color(0x29F2F4F4))
}

@Composable
fun WidgetContent(entry: WidgetEntry) {
    val context = androidx.glance.LocalContext.current
    val size = LocalSize.current
    Box(
        GlanceModifier.fillMaxSize()
            .background(ImageProvider(R.drawable.widget_background))
            .cornerRadius(20.dp)
            .padding(14.dp)
            .clickable(actionStartActivity(SpinOfTheDayWidget.openIntent(context, entry)))
            .semantics { contentDescription = entry.pick?.let { "Spin of the Day: ${it.movie.title}, ${it.movie.year}" } ?: "Movie Roulette Tuesday" },
    ) {
        when {
            size.height >= SpinOfTheDayWidget.LARGE.height && size.width >= SpinOfTheDayWidget.LARGE.width -> Large(entry)
            size.width >= SpinOfTheDayWidget.MEDIUM.width -> Medium(entry)
            else -> Small(entry)
        }
    }
}

@Composable
private fun Small(entry: WidgetEntry) {
    Column(GlanceModifier.fillMaxSize()) {
        PickView(entry.pick, compact = true)
        Spacer(GlanceModifier.defaultWeight())
        entry.latestEpisode?.let {
            Text("New episode · ${it.seasonEpisodeLabel ?: it.title}", style = TextStyle(color = WidgetColors.accent, fontSize = 11.sp, fontWeight = FontWeight.Medium), maxLines = 1)
        }
    }
}

@Composable
private fun Medium(entry: WidgetEntry) {
    Row(GlanceModifier.fillMaxSize()) {
        Box(GlanceModifier.defaultWeight()) { PickView(entry.pick, compact = false) }
        Spacer(GlanceModifier.width(12.dp))
        Box(GlanceModifier.width(1.dp).fillMaxHeight().background(WidgetColors.divider)) {}
        Spacer(GlanceModifier.width(12.dp))
        Box(GlanceModifier.defaultWeight()) { SecondaryView(entry) }
    }
}

@Composable
private fun Large(entry: WidgetEntry) {
    Column(GlanceModifier.fillMaxSize()) {
        PickView(entry.pick, compact = false)
        entry.pick?.let { Text(it.movie.genre, style = TextStyle(color = WidgetColors.secondary, fontSize = 12.sp), maxLines = 1) }
        Divider()
        SecondaryView(entry)
        val spin = entry.lastSpin
        if (entry.latestEpisode != null && spin != null) {
            Divider()
            Heading("YOUR LAST SPIN")
            Text(spin.title, style = TextStyle(color = WidgetColors.primary, fontSize = 15.sp, fontWeight = FontWeight.Medium), maxLines = 2)
            Text(spin.categoryName, style = TextStyle(color = WidgetColors.secondary, fontSize = 11.sp))
        }
    }
}

@Composable
private fun Divider() {
    Spacer(GlanceModifier.height(12.dp))
    Box(GlanceModifier.fillMaxWidth().height(1.dp).background(WidgetColors.divider)) {}
    Spacer(GlanceModifier.height(12.dp))
}

@Composable
private fun Heading(text: String) {
    Text(text, style = TextStyle(color = WidgetColors.accent, fontSize = 11.sp, fontWeight = FontWeight.Bold), maxLines = 1)
}

@Composable
private fun PickView(pick: SpinOfTheDay.Pick?, compact: Boolean) {
    Column {
        Heading("SPIN OF THE DAY")
        Spacer(GlanceModifier.height(4.dp))
        if (pick == null) {
            Text("Tap to spin.", style = TextStyle(color = WidgetColors.primary, fontSize = 12.sp))
            return@Column
        }
        pick.category?.let { cat ->
            val hex = cat.displayHex
            val bg = HexColor.parse(hex) ?: HexColor(0.5, 0.5, 0.5)
            val label = if (bg.prefersDarkText) Color.Black else Color.White
            Box(GlanceModifier.background(Color(bg.argb)).cornerRadius(10.dp).padding(horizontal = 6.dp, vertical = 2.dp)) {
                Text(cat.displayName, style = TextStyle(color = FixedColor(label), fontSize = 11.sp, fontWeight = FontWeight.Bold), maxLines = 1)
            }
            Spacer(GlanceModifier.height(4.dp))
        }
        Text(
            pick.movie.title,
            style = TextStyle(color = WidgetColors.primary, fontSize = if (compact) 17.sp else 20.sp, fontWeight = FontWeight.Medium),
            maxLines = if (compact) 3 else 2,
        )
        Text(pick.movie.year.toString(), style = TextStyle(color = WidgetColors.secondary, fontSize = 12.sp))
    }
}

@Composable
private fun SecondaryView(entry: WidgetEntry) {
    Column {
        val episode = entry.latestEpisode
        val spin = entry.lastSpin
        when {
            episode != null -> {
                Heading("LATEST EPISODE")
                episode.seasonEpisodeLabel?.let { Text(it, style = TextStyle(color = WidgetColors.secondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)) }
                Text(episode.title, style = TextStyle(color = WidgetColors.primary, fontSize = 15.sp, fontWeight = FontWeight.Medium), maxLines = 3)
                episode.pubDate?.let { Text(WidgetDates.short(it), style = TextStyle(color = WidgetColors.secondary, fontSize = 11.sp)) }
            }
            spin != null -> {
                Heading("YOUR LAST SPIN")
                Text(spin.title, style = TextStyle(color = WidgetColors.primary, fontSize = 15.sp, fontWeight = FontWeight.Medium), maxLines = 3)
                Text(spin.categoryName, style = TextStyle(color = WidgetColors.secondary, fontSize = 11.sp))
            }
            else -> {
                Heading("MOVIE ROULETTE TUESDAY")
                Text("Open the app for the latest episode.", style = TextStyle(color = WidgetColors.primary, fontSize = 12.sp))
            }
        }
    }
}

internal object WidgetDates {
    /** "Oct 6" (iOS `.dateTime.month(.abbreviated).day()`). */
    fun short(epochMs: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
        DateTimeFormatter.ofPattern("MMM d", locale).format(Instant.ofEpochMilli(epochMs).atZone(zone))
}
