package com.movieroulettetuesday.app.screenshots

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.movieroulettetuesday.app.MrtApplication
import com.movieroulettetuesday.app.catalog.CatalogJson
import com.movieroulettetuesday.app.catalog.CatalogFixtures
import com.movieroulettetuesday.app.community.FakeCommunityStore
import com.movieroulettetuesday.app.community.CommunityRecordNames
import com.movieroulettetuesday.app.data.InMemoryKeyValueStore
import com.movieroulettetuesday.app.episodes.EpisodeFixtures
import com.movieroulettetuesday.app.episodes.EpisodeRepository
import com.movieroulettetuesday.app.roulette.SpinMotion
import com.movieroulettetuesday.app.roulette.SpinResult
import com.movieroulettetuesday.app.ui.MrtApp
import com.movieroulettetuesday.app.ui.theme.MrtTheme
import com.movieroulettetuesday.app.widget.SpinOfTheDay
import com.movieroulettetuesday.app.widget.SpinOfTheDayWidget
import com.movieroulettetuesday.app.widget.WidgetContent
import com.movieroulettetuesday.app.widget.WidgetEntry
import com.movieroulettetuesday.app.widget.WidgetSnapshot
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Robolectric renders of every main screen for a visual review (not a test of behavior; JVM
 * graphics, placeholder artwork, fake community data). Skipped unless `-Pmrt.screenshots=<dir>`.
 * Phone = 360x800 dp @ 3x = 1080x2400 px; tablet = 10" 800x1280 dp @ 2x = 1600x2560 px.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenshotTour {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val outRoot: String? = System.getProperty("mrt.screenshots")?.takeIf { it.isNotBlank() }

    @Before fun gate() = assumeTrue("set -Pmrt.screenshots=<dir> to render", outRoot != null)

    @Test @Config(qualifiers = "w360dp-h800dp-port-notnight-xxhdpi") fun phoneLight() = tour("phone", dark = false)
    @Test @Config(qualifiers = "w360dp-h800dp-port-night-xxhdpi") fun phoneDark() = tour("phone", dark = true)
    @Test @Config(qualifiers = "w800dp-h1280dp-port-notnight-xhdpi") fun tabletLight() = tour("tablet10", dark = false)
    @Test @Config(qualifiers = "w800dp-h1280dp-port-night-xhdpi") fun tabletDark() = tour("tablet10", dark = true)

    private lateinit var dir: File
    private var dark = false

    private fun tour(device: String, dark: Boolean) {
        this.dark = dark
        dir = File(outRoot!!, "$device-${if (dark) "dark" else "light"}").apply { mkdirs() }
        val app = ApplicationProvider.getApplicationContext<MrtApplication>()
        seed(app)
        compose.setContent { MrtTheme(darkTheme = dark) { MrtApp() } }
        compose.waitUntil(5_000) { app.catalogRepository.catalog != null }
        compose.waitForIdle()
        shot("01-roulette-idle")

        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("spin-hub").performClick()
        compose.mainClock.advanceTimeBy(SpinMotion.spinDurationMs.toLong() + 4_000)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        shot("02-spin-reveal")

        compose.onNodeWithTag("tab-episodes").performClick(); compose.waitForIdle()
        shot("03-episodes")

        compose.onNodeWithTag("tab-movies").performClick(); compose.waitForIdle()
        shot("04-movies-list")

        app.pendingMovie.value = DETAIL_MOVIE
        compose.waitForIdle()
        shot("05-movie-detail")

        compose.onNodeWithTag("tab-stats").performClick(); compose.waitForIdle()
        shot("06-stats")

        compose.onNodeWithTag("tab-roulette").performClick(); compose.waitForIdle()
        compose.onNodeWithTag("open-settings").performClick(); compose.waitForIdle()
        shot("07-settings")

        compose.onNodeWithTag("settings-suggest").performScrollTo().performClick()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        shot("08-suggest")

        widgets(app)
    }

    /** Deterministic content: sample feed, some spins, a few personal + fake listener ratings and poll votes. */
    private fun seed(app: MrtApplication) {
        val bytes = EpisodeFixtures.resource("feed-sample.rss")
        val repo = EpisodeRepository(null, InMemoryKeyValueStore(), { bytes }).also { runBlocking { it.load() } }
        MrtApplication::class.java.getDeclaredField("episodeRepository\$delegate").apply { isAccessible = true }.set(app, lazyOf(repo))
        val catalog = CatalogJson.decode(CatalogFixtures.bundledBytes)
        val base = 1_760_000_000_000L
        catalog.movies.take(40).forEachIndexed { i, m ->
            val cat = catalog.categories.firstOrNull { m.id in it.movieIds } ?: return@forEachIndexed
            repeat(1 + (i * 7) % 4) { r -> app.spinHistory.record(SpinResult(movieId = m.id, categoryId = cat.id, timestampEpochMs = base + (i * 10L + r) * 3_600_000)) }
        }
        val store = FakeCommunityStore(canWrite = true)
        val names = listOf("a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l")
        store.ratings[DETAIL_MOVIE] = names.associate { CommunityRecordNames.rating(DETAIL_MOVIE, it) to listOf(10, 8, 9, 7, 10, 6)[names.indexOf(it) % 6] }.toMutableMap()
        app.community = app.createCommunity(store)
    }

    /** Draws every window (activity + dialogs such as the bottom sheet) in z-order, like the screen shows it. */
    private fun shot(name: String) {
        compose.waitForIdle()
        val root = compose.activity.window.decorView
        val out = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(if (dark) Color.BLACK else Color.WHITE)
        for (v in windowRoots()) {
            if (v.width == 0 || v.height == 0 || !v.isShown) continue
            val loc = IntArray(2).also { v.getLocationOnScreen(it) }
            canvas.save(); canvas.translate(loc[0].toFloat(), loc[1].toFloat()); v.draw(canvas); canvas.restore()
        }
        save(out, name)
    }

    @Suppress("UNCHECKED_CAST")
    private fun windowRoots(): List<View> {
        val wmg = Class.forName("android.view.WindowManagerGlobal")
        val global = wmg.getMethod("getInstance").invoke(null)
        val views = wmg.getDeclaredField("mViews").apply { isAccessible = true }.get(global) as List<View>
        return views.toList().ifEmpty { listOf(compose.activity.window.decorView) }
    }

    private fun save(bmp: Bitmap, name: String) {
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** The Glance widget at its three iOS families, rendered through real RemoteViews. */
    @OptIn(ExperimentalGlanceRemoteViewsApi::class)
    private fun widgets(app: MrtApplication) {
        val catalog = CatalogJson.decode(CatalogFixtures.bundledBytes)
        val entry = WidgetEntry(
            pick = SpinOfTheDay.pick("2026-10-21", catalog),
            latestEpisode = WidgetSnapshot.EpisodeInfo("Buzzsprout-19980357", "The Outsiders", 4, 36),
            lastSpin = null,
        )
        val density = app.resources.displayMetrics.density
        val sizes = listOf("small" to SpinOfTheDayWidget.SMALL, "medium" to SpinOfTheDayWidget.MEDIUM, "large" to SpinOfTheDayWidget.LARGE)
        for ((label, size) in sizes) {
            val rv = runBlocking {
                androidx.glance.appwidget.GlanceRemoteViews().compose(app, size) { WidgetContent(entry) }.remoteViews
            }
            val w = (size.width.value * density).toInt(); val h = (size.height.value * density).toInt()
            val pad = (16 * density).toInt()
            val parent = FrameLayout(compose.activity)
            val view: View = rv.apply(compose.activity, parent)
            parent.addView(view, FrameLayout.LayoutParams(w, h))
            parent.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            parent.layout(0, 0, w, h)
            val bmp = Bitmap.createBitmap(w + 2 * pad, h + 2 * pad, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawColor(if (dark) Color.rgb(32, 33, 36) else Color.rgb(200, 214, 220))
            c.translate(pad.toFloat(), pad.toFloat())
            parent.draw(c)
            save(bmp, "09-widget-$label")
        }
    }

    private companion object { const val DETAIL_MOVIE = "laura-1944" }
}
