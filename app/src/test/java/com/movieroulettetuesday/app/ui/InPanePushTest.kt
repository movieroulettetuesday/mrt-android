package com.movieroulettetuesday.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.movieroulettetuesday.app.MrtApplication
import com.movieroulettetuesday.app.ui.theme.MrtTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Regular width: links from a detail pane push onto a stack inside that pane (iOS `NavigationStack`
 * in the split view's detail column) and pop back to the prior detail; the list stays beside it.
 * Shrinking to compact turns the selection and its in-pane pages into pushed pages, in order.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w1280dp-h800dp")
class InPanePushTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<MrtApplication>()
    private var widthDp by mutableFloatStateOf(1280f)

    private val feedGuids = listOf("Buzzsprout-19980357", "Buzzsprout-19950553", "Buzzsprout-19913482")

    private fun start() {
        AdaptiveUiTest.seedFeed(app)
        compose.setContent {
            MrtTheme { Box(Modifier.requiredWidth(widthDp.dp).fillMaxHeight()) { MrtApp() } }
        }
        compose.waitUntil(5_000) { app.catalogRepository.catalog != null }
        compose.waitForIdle()
    }

    private fun inDetail(tag: String): SemanticsNodeInteraction =
        compose.onNode(hasTestTag(tag) and hasAnyAncestor(hasTestTag("detail-pane")))

    private fun count(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size
    private fun backButtons() = compose.onAllNodes(hasContentDescription("Back")).fetchSemanticsNodes().size
    private fun systemBack() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    /** An episode in the sample feed and the catalog movie it discusses. */
    private fun linkedPair(): Pair<String, String> {
        val catalog = app.catalogRepository.catalog!!
        return feedGuids.firstNotNullOf { g -> catalog.movieForEpisodeGuid(g)?.let { g to it.id } }
    }

    @Test fun episodePaneLinkPushesMovieInsideThePaneAndPopsBack() {
        start()
        val (guid, movieId) = linkedPair()
        compose.onNodeWithTag("tab-episodes").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("episode-row-$guid").performClick()
        compose.waitForIdle()
        inDetail("episode-title").assertIsDisplayed()
        assertEquals(0, backButtons())

        inDetail("episode-movie-link").performScrollTo().performClick()
        compose.waitForIdle()
        // Pushed inside the detail pane: the movie fills the pane, the list stays, nav route unchanged.
        inDetail("pane-page-1").assertIsDisplayed()
        inDetail("movie-detail").assertIsDisplayed()
        compose.onNodeWithTag("screen-episodes").assertIsDisplayed()
        compose.onNodeWithTag("list-pane").assertIsDisplayed()
        assertEquals(0, count("episode-title"))
        assertEquals(1, backButtons())
        // The pushed movie page doesn't link back to episodes (no endless push loop), like on phones.
        assertTrue(app.catalogRepository.catalog!!.movies.first { it.id == movieId }.episodeIds.isNotEmpty())

        compose.onNode(hasContentDescription("Back") and hasAnyAncestor(hasTestTag("detail-pane"))).performClick()
        compose.waitForIdle()
        inDetail("pane-page-0").assertIsDisplayed()
        inDetail("episode-title").assertIsDisplayed()
        assertEquals(0, count("movie-detail"))
        assertEquals(0, backButtons())
        compose.onNodeWithTag("screen-episodes").assertIsDisplayed()
    }

    @Test fun moviePaneLinkPushesEpisodeAndSystemBackPopsIt() {
        start()
        val (guid, movieId) = linkedPair()
        app.pendingMovie.value = movieId // tablet deep link selects in the pane
        compose.waitForIdle()
        inDetail("movie-detail").assertIsDisplayed()
        inDetail("movie-episode-$guid").performScrollTo().performClick()
        compose.waitForIdle()
        inDetail("pane-page-1").assertIsDisplayed()
        inDetail("episode-title").assertIsDisplayed()
        compose.onNodeWithTag("screen-movies").assertIsDisplayed()
        assertEquals(0, count("movie-detail"))

        systemBack() // pops the pane stack, not the tab
        inDetail("movie-detail").assertIsDisplayed()
        assertEquals(0, count("episode-title"))
        compose.onNodeWithTag("screen-movies").assertIsDisplayed()
        assertEquals(0, backButtons())
    }

    @Test fun pickingAnotherRowOrDeepLinkStartsAFreshStack() {
        start()
        val (guid, movieId) = linkedPair()
        app.pendingMovie.value = movieId
        compose.waitForIdle()
        inDetail("movie-episode-$guid").performScrollTo().performClick()
        compose.waitForIdle()
        inDetail("pane-page-1").assertIsDisplayed()
        // Same movie again via deep link: shows that movie, nothing pushed over it.
        app.pendingMovie.value = movieId
        compose.waitForIdle()
        inDetail("pane-page-0").assertIsDisplayed()
        inDetail("movie-detail").assertIsDisplayed()
        // Push again, then pick a different movie: fresh stack.
        inDetail("movie-episode-$guid").performScrollTo().performClick()
        compose.waitForIdle()
        val other = app.catalogRepository.catalog!!.movies.first { it.id != movieId }
        app.pendingMovie.value = other.id
        compose.waitForIdle()
        inDetail("pane-page-0").assertIsDisplayed()
        inDetail("movie-detail").assertIsDisplayed()
        assertEquals(0, backButtons())
    }

    @Test fun shrinkingToCompactKeepsSelectionAndPushedPagesAsAStack() {
        start()
        val (guid, _) = linkedPair()
        compose.onNodeWithTag("tab-episodes").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("episode-row-$guid").performClick()
        compose.waitForIdle()
        inDetail("episode-movie-link").performScrollTo().performClick()
        compose.waitForIdle()
        inDetail("movie-detail").assertIsDisplayed()

        widthDp = 400f // e.g. split screen: compact width
        compose.waitForIdle()
        assertEquals(0, count("list-detail"))
        compose.onNodeWithTag("nav-bar").assertIsDisplayed()
        compose.onNodeWithTag("movie-detail").assertIsDisplayed()
        assertEquals(0, count("screen-episodes"))
        systemBack()
        compose.onNodeWithTag("episode-title").assertIsDisplayed()
        assertEquals(0, count("movie-detail"))
        systemBack()
        compose.onNodeWithTag("screen-episodes").assertIsDisplayed()
        assertEquals(0, count("episode-title"))
    }

    @Test fun shrinkingWithOnlyASelectionStillPushesIt() {
        start()
        val (_, movieId) = linkedPair()
        app.pendingMovie.value = movieId
        compose.waitForIdle()
        inDetail("movie-detail").assertIsDisplayed()
        widthDp = 400f
        compose.waitForIdle()
        compose.onNodeWithTag("movie-detail").assertIsDisplayed()
        assertEquals(1, backButtons())
        systemBack()
        compose.onNodeWithTag("screen-movies").assertIsDisplayed()
        assertEquals(0, count("movie-detail"))
    }

    @Test fun paneStacksSurviveSaveAndRestore() {
        val p = PaneSelection("g1", "m1", listOf(PanePage.Movie("m:odd/id")), listOf(PanePage.Episode("Buzzsprout-1")))
        val saved = with(PaneSelection.Saver) { androidx.compose.runtime.saveable.SaverScope { true }.save(p) }!!
        val r = PaneSelection.Saver.restore(saved)!!
        assertEquals("g1", r.episode); assertEquals("m1", r.movie)
        assertEquals(listOf(PanePage.Movie("m:odd/id")), r.episodeStack.toList())
        assertEquals(listOf(PanePage.Episode("Buzzsprout-1")), r.movieStack.toList())
        r.episode = "g1"; assertEquals(1, r.episodeStack.size) // same pick keeps the stack (iOS .id)
        r.episode = "g2"; assertEquals(0, r.episodeStack.size)
        r.showMovie("m1"); assertEquals(0, r.movieStack.size)
    }
}
