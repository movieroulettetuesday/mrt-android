package com.movieroulettetuesday.app.widget

import android.content.Intent
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasText
import androidx.glance.testing.unit.hasTextEqualTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.movieroulettetuesday.app.catalog.CatalogJson
import com.movieroulettetuesday.app.episodes.PodcastEpisode
import com.movieroulettetuesday.app.shared.SharedVectors
import com.movieroulettetuesday.app.shared.objs
import com.movieroulettetuesday.app.shared.obj
import com.movieroulettetuesday.app.shared.arr
import com.movieroulettetuesday.app.shared.str
import java.nio.file.Files
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SpinOfTheDayTest {
    private val bundled = CatalogJson.decode(SharedVectors.bundledBytes)

    @Test fun matchesIosDailyPicks() {
        val cases = SharedVectors.load("stats.json").obj("spinOfTheDay").arr("cases").objs()
        assertTrue(cases.size >= 5)
        for (c in cases) {
            val pick = SpinOfTheDay.pick(c.str("dayKey")!!, bundled)!!
            assertEquals(c.str("dayKey"), c.str("movieId"), pick.movie.id)
            assertEquals(c.str("dayKey"), c.str("categoryId"), pick.category?.id)
        }
    }

    @Test fun dayKeyAndMidnightUseTheLocalCalendar() {
        val ny = ZoneId.of("America/New_York")
        val t = Instant.parse("2026-10-21T03:30:00Z").toEpochMilli() // 23:30 on the 20th in New York
        assertEquals("2026-10-20", SpinOfTheDay.dayKey(t, ny))
        assertEquals("2026-10-21", SpinOfTheDay.dayKey(t, ZoneOffset.UTC))
        assertEquals(Instant.parse("2026-10-21T04:00:00Z").toEpochMilli(), SpinOfTheDay.nextRefresh(t, ny))
    }

    @Test fun emptyCatalogHasNoPickAndEmptyCategoryFallsBackToAllMovies() {
        assertNull(SpinOfTheDay.pick("2026-10-21", bundled.copy(categories = emptyList(), movies = emptyList())))
        val noCats = bundled.copy(categories = emptyList())
        val p = SpinOfTheDay.pick("2026-10-21", noCats)!!
        assertEquals(p.movie.categories.firstOrNull()?.let { null }, p.category) // no categories to resolve
    }

    @Test fun snapshotStoreSkipsNoOpWritesAndBridgeRefreshesOnlyOnChange() {
        val dir = Files.createTempDirectory("w").toFile()
        val store = WidgetSnapshotStore(File(dir, WidgetSnapshotStore.FILE_NAME))
        var refreshes = 0
        val bridge = WidgetBridge(store, refresh = { refreshes++ }, clock = { 1_000L })
        val eps = listOf(
            PodcastEpisode("a", "Older", pubDate = 10),
            PodcastEpisode("b", "Newest", season = 4, episode = 34, pubDate = 20),
            PodcastEpisode("c", "Same date later in feed", pubDate = 20),
        )
        bridge.publishLatestEpisode(eps)
        assertEquals("b", store.load()!!.latestEpisode!!.guid)
        assertEquals("S4E34", store.load()!!.latestEpisode!!.seasonEpisodeLabel)
        bridge.publishLatestEpisode(eps)
        assertEquals(1, refreshes)
        val movie = bundled.movies[0]
        val cat = bundled.category(movie.categories[0])!!
        bridge.publishSpin(movie, cat, at = 5)
        assertEquals(2, refreshes)
        assertEquals(movie.title, store.load()!!.lastSpin!!.title)
        assertFalse(store.update(2_000L) { it })
        bridge.publishLatestEpisode(emptyList())
        assertEquals(2, refreshes)
    }

    @Test fun tapOpensTodaysMovie() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val i = SpinOfTheDayWidget.openIntent(ctx, WidgetEntry.sample)
        assertEquals(SpinOfTheDayWidget.ACTION_OPEN_MOVIE, i.action)
        assertEquals("the-outsiders-1983", i.getStringExtra(SpinOfTheDayWidget.EXTRA_MOVIE_ID))
        assertTrue(i.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertNull(SpinOfTheDayWidget.openIntent(ctx, WidgetEntry(null)).action)
    }

    private val entry = WidgetEntry(
        pick = SpinOfTheDay.pick("2026-10-20", bundled),
        latestEpisode = WidgetSnapshot.EpisodeInfo("Buzzsprout-19913482", "The Outsiders", 4, 34, Instant.parse("2026-10-06T16:00:00Z").toEpochMilli()),
        lastSpin = WidgetSnapshot.SpinInfo("x", "Tremors", 1990, "Horror Hall of Fame", "#C25B5E", 0),
    )

    @Test fun smallShowsPickAndNewEpisodeLine() = runGlanceAppWidgetUnitTest {
        setContext(ApplicationProvider.getApplicationContext())
        setAppWidgetSize(DpSize(160.dp, 160.dp))
        provideComposable { WidgetContent(entry) }
        onNode(hasText("SPIN OF THE DAY")).assertExists()
        onNode(hasText(entry.pick!!.movie.title)).assertExists()
        onNode(hasText("New episode · S4E34")).assertExists()
        onNode(hasText("LATEST EPISODE")).assertDoesNotExist()
    }

    @Test fun mediumShowsLatestEpisode() = runGlanceAppWidgetUnitTest {
        setContext(ApplicationProvider.getApplicationContext())
        setAppWidgetSize(DpSize(320.dp, 160.dp))
        provideComposable { WidgetContent(entry) }
        onNode(hasText("LATEST EPISODE")).assertExists()
        onNode(hasText("S4E34")).assertExists()
        onNode(hasText("YOUR LAST SPIN")).assertDoesNotExist()
    }

    @Test fun largeAddsGenreAndLastSpin() = runGlanceAppWidgetUnitTest {
        setContext(ApplicationProvider.getApplicationContext())
        setAppWidgetSize(DpSize(320.dp, 320.dp))
        provideComposable { WidgetContent(entry) }
        onNode(hasTextEqualTo(entry.pick!!.movie.genre)).assertExists()
        onNode(hasText("YOUR LAST SPIN")).assertExists()
        onNode(hasText("Tremors")).assertExists()
    }

    @Test fun noSnapshotFallsBackLikeIos() = runGlanceAppWidgetUnitTest {
        setContext(ApplicationProvider.getApplicationContext())
        setAppWidgetSize(DpSize(320.dp, 160.dp))
        provideComposable { WidgetContent(WidgetEntry(entry.pick)) }
        onNode(hasText("MOVIE ROULETTE TUESDAY")).assertExists()
        onNode(hasText("Open the app for the latest episode.")).assertExists()
    }
}
