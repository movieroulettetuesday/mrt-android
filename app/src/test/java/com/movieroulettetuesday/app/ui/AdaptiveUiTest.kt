package com.movieroulettetuesday.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.movieroulettetuesday.app.MrtApplication
import com.movieroulettetuesday.app.data.InMemoryKeyValueStore
import com.movieroulettetuesday.app.episodes.EpisodeFixtures
import com.movieroulettetuesday.app.episodes.EpisodeRepository
import com.movieroulettetuesday.app.roulette.SpinMotion
import com.movieroulettetuesday.app.roulette.SpinResult
import com.movieroulettetuesday.app.ui.theme.MrtTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** MrtApp at compact / medium / expanded widths (bottom bar vs iPad-style top tabs, stacked vs two-pane, side-by-side roulette). */
@RunWith(AndroidJUnit4::class)
class AdaptiveUiTest {
    @get:Rule val compose = createComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<MrtApplication>()

    private fun start(seedEpisodes: Boolean = false) {
        if (seedEpisodes) seedFeed(app)
        compose.setContent { MrtTheme { MrtApp() } }
        compose.waitUntil(5_000) { app.catalogRepository.catalog != null }
        compose.waitForIdle()
    }

    private fun gone(tag: String) = assertTrue("$tag should be absent", compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty())

    @Test @Config(qualifiers = "w411dp-h891dp")
    fun compactUsesBottomBarAndPushedPages() {
        start()
        compose.onNodeWithTag("nav-bar").assertIsDisplayed()
        gone("nav-top-tabs")
        compose.onNodeWithTag("roulette-stacked").assertIsDisplayed()
        compose.onNodeWithTag("tab-movies").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("movies-search").fetchSemanticsNodes().isNotEmpty() }
        gone("list-detail")
        compose.onNodeWithTag("movies-search").performTextInput("laura")
        compose.onNodeWithTag("movie-row-laura-1944").performClick()
        compose.onNodeWithTag("movie-detail").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").assertIsDisplayed()
        gone("movies-search")
    }

    @Test @Config(qualifiers = "w600dp-h960dp")
    fun mediumUsesTopTabsAndTwoPaneEpisodes() {
        start(seedEpisodes = true)
        compose.onNodeWithTag("nav-top-tabs").assertIsDisplayed()
        gone("nav-bar")
        // 600 dp portrait: regular width but not landscape, so the wheel stays stacked (iOS 1024x1366).
        compose.onNodeWithTag("roulette-stacked").assertIsDisplayed()
        compose.onNodeWithTag("tab-episodes").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("tab-episodes").assertIsSelected()
        compose.onNodeWithTag("list-pane").assertIsDisplayed()
        compose.onNodeWithTag("detail-empty").assertIsDisplayed()
        compose.onNodeWithTag("episode-row-Buzzsprout-19980357").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("episode-title").assertIsDisplayed()
        compose.onNodeWithTag("episode-row-Buzzsprout-19980357").assertIsSelected()
        compose.onNodeWithTag("screen-episodes").assertIsDisplayed() // the list stays next to the detail
        gone("detail-empty")
        assertTrue(compose.onAllNodesWithContentDescription("Back").isEmpty()) // nothing was pushed
    }

    @Test @Config(qualifiers = "w1280dp-h800dp")
    fun regularTopTabsSitAboveContentWithEpisodesBadge() {
        seedFeed(app, lastViewedAt = 1L) // everything in the sample feed is newer than this -> badge
        start()
        gone("nav-bar")
        val bar = compose.onNodeWithTag("nav-top-tabs").assertIsDisplayed().getBoundsInRoot()
        val screen = compose.onNodeWithTag("screen-roulette").getBoundsInRoot()
        assertTrue("tabs above the content", bar.bottom <= screen.top)
        assertTrue("tabs at the top of the window", bar.top < 100.dp)
        // Same four destinations as the phone bar, in iOS order, one selected.
        val tabs = TopLevelDestination.entries.map { compose.onNodeWithTag("tab-${it.route}").assertIsDisplayed().getBoundsInRoot() }
        assertEquals(tabs.sortedBy { it.left }, tabs)
        tabs.forEach { assertTrue(it.bottom <= screen.top) }
        compose.onNodeWithTag("tab-roulette").assertIsSelected()
        compose.onNodeWithTag("episodes-badge", useUnmergedTree = true).assertIsDisplayed()
        // Opening Episodes marks them viewed: the badge clears (same as the phone bar).
        compose.onNodeWithTag("tab-episodes").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("tab-episodes").assertIsSelected()
        compose.onNodeWithTag("tab-roulette").assertIsNotSelected()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("episodes-badge", useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("tab-stats").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("screen-stats").assertIsDisplayed()
    }

    @Test @Config(qualifiers = "w1280dp-h800dp")
    fun expandedRouletteRevealSitsBesideTheWheel() {
        start()
        compose.onNodeWithTag("nav-top-tabs").assertIsDisplayed()
        compose.onNodeWithTag("roulette-side-by-side").assertIsDisplayed()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("spin-hub").performClick()
        compose.mainClock.advanceTimeBy(SpinMotion.spinDurationMs.toLong() + 4_000)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onNodeWithTag("reveal-inline").assertIsDisplayed()
        compose.onNodeWithTag("reveal-title").assertIsDisplayed()
        compose.onNodeWithTag("wheel-slot").assertIsDisplayed() // the wheel stays visible
        gone("reveal-sheet")
        compose.onNodeWithTag("reveal-done").performClick()
        compose.waitForIdle()
        gone("reveal-inline")
    }

    @Test @Config(qualifiers = "w1280dp-h800dp")
    fun expandedMoviesTwoPaneAndDeepLinkSelects() {
        start()
        compose.onNodeWithTag("tab-movies").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("movies-search").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("detail-empty").assertIsDisplayed()
        compose.onNodeWithTag("movies-search").performTextInput("laura")
        compose.onNodeWithTag("movie-row-laura-1944").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("movie-detail").assertIsDisplayed()
        compose.onNodeWithTag("movies-search").assertIsDisplayed()
        // Widget deep link on a tablet selects in the pane instead of pushing a page.
        compose.onNodeWithTag("tab-roulette").performClick()
        compose.waitForIdle()
        val other = app.catalogRepository.catalog!!.movies.first { it.id != "laura-1944" }
        app.pendingMovie.value = other.id
        compose.waitForIdle()
        compose.onNodeWithTag("tab-movies").assertIsSelected()
        compose.onNodeWithTag("list-pane").assertIsDisplayed()
        compose.onNodeWithTag("detail-pane").assertIsDisplayed()
        assertTrue(compose.onAllNodes(androidx.compose.ui.test.hasText(other.title) and androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.hasTestTag("detail-pane"))).fetchSemanticsNodes().isNotEmpty())
        assertEquals(null, app.pendingMovie.value)
        assertTrue(compose.onAllNodesWithContentDescription("Back").isEmpty())
    }

    @Test @Config(qualifiers = "w1280dp-h800dp")
    fun expandedStatsUseThreeColumns() {
        seedSpins(app)
        start()
        compose.onNodeWithTag("tab-stats").performClick()
        compose.waitForIdle()
        // 1280 dp minus 40 dp padding = 1240 (the tabs sit on top, no rail) ≥ 1100 -> 3 columns (iOS StatsGridLayout).
        compose.onNodeWithTag("stats-grid-3").assertIsDisplayed()
    }

    @Test @Config(qualifiers = "w600dp-h960dp")
    fun mediumStatsUseOneColumnBelow640() {
        seedSpins(app)
        start()
        compose.onNodeWithTag("tab-stats").performClick()
        compose.waitForIdle()
        // 600 - 40 padding = 560 dp < 640 -> one column, like iPad Slide Over.
        compose.onNodeWithTag("stats-grid-1").assertIsDisplayed()
    }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.onAllNodesWithContentDescription(d: String) =
        onAllNodes(androidx.compose.ui.test.hasContentDescription(d)).fetchSemanticsNodes()

    companion object {
        /** Replaces the app's offline (empty) feed with the sample feed. */
        fun seedFeed(app: MrtApplication, lastViewedAt: Long? = null) {
            val bytes = EpisodeFixtures.resource("feed-sample.rss")
            val settings = InMemoryKeyValueStore().apply { lastViewedAt?.let { putLong(EpisodeRepository.LAST_VIEWED_KEY, it) } }
            val repo = EpisodeRepository(null, settings, { bytes }).also { runBlocking { it.load() } }
            MrtApplication::class.java.getDeclaredField("episodeRepository\$delegate").apply { isAccessible = true }.set(app, lazyOf(repo))
        }

        fun seedSpins(app: MrtApplication) {
            val catalog = com.movieroulettetuesday.app.catalog.CatalogJson.decode(com.movieroulettetuesday.app.catalog.CatalogFixtures.bundledBytes)
            catalog.movies.take(12).forEachIndexed { i, m ->
                val cat = catalog.categories.firstOrNull { m.id in it.movieIds } ?: return@forEachIndexed
                app.spinHistory.record(SpinResult(movieId = m.id, categoryId = cat.id, timestampEpochMs = 1_760_000_000_000L + i * 3_600_000L))
            }
        }
    }
}
