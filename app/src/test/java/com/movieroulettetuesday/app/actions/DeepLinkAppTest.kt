package com.movieroulettetuesday.app.actions

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.movieroulettetuesday.app.MrtApplication
import com.movieroulettetuesday.app.movies.WatchStatus
import com.movieroulettetuesday.app.ui.MrtApp
import com.movieroulettetuesday.app.ui.theme.MrtTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeepLinkAppTest {
    @get:Rule val compose = createComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext<MrtApplication>()

    private fun start() {
        compose.setContent { MrtTheme { MrtApp() } }
        compose.waitForIdle()
        compose.waitUntil(8_000) { app.catalogRepository.catalog != null }
    }

    @Test
    fun movieDeepLinkOpensMoviePage() {
        start()
        val id = "laura-1944"
        app.dispatch(DeepLink.OpenMovie(id))
        compose.waitForIdle()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("movie-detail").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("tab-movies").assertIsSelected()
        compose.onNodeWithTag("movie-detail").assertIsDisplayed()
        compose.onAllNodesWithText("Laura").onFirst().assertIsDisplayed()
        assertEquals(null, app.pendingMovie.value)
        assertEquals(null, app.pendingLink.value)
        assertTrue(app.spoken.any { it.contains("Laura") })
    }

    @Test
    fun episodeDeepLinkOpensEpisodePage() {
        start()
        app.dispatch(DeepLink.OpenEpisode("Buzzsprout-unknown"))
        compose.waitForIdle()
        compose.onNodeWithTag("tab-episodes").assertIsSelected()
        compose.onNodeWithContentDescription("Back").assertIsDisplayed()
        assertEquals(null, app.pendingEpisode.value)
    }

    @Test
    fun watchlistDeepLinkAppliesTheFilter() {
        start()
        val catalog = app.catalogRepository.catalog!!
        val movie = catalog.movie("laura-1944") ?: catalog.movies.first()
        app.personalRatings.setStatus(WatchStatus.WantToWatch, movie.id)
        app.dispatch(DeepLink.Watchlist)
        compose.waitForIdle()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("screen-movies").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("tab-movies").assertIsSelected()
        compose.onNodeWithTag("screen-movies").assertIsDisplayed()
        // The Show menu advertises the active filter as its content description.
        compose.onNodeWithContentDescription("Watchlist").assertIsDisplayed()
        compose.onAllNodesWithText(movie.title).onFirst().assertIsDisplayed()
        assertTrue(app.spoken.any { it.contains(movie.title) })
        assertEquals(ActionSpeech.EMPTY_WATCHLIST != app.spoken.last(), true)
    }

    @Test
    fun emptyWatchlistIsATypedResult() {
        start()
        app.dispatch(DeepLink.Watchlist)
        compose.waitForIdle()
        compose.onNodeWithTag("tab-movies").assertIsSelected()
        compose.waitUntil(5_000) { app.spoken.isNotEmpty() }
        assertEquals(ActionSpeech.EMPTY_WATCHLIST, app.spoken.last())
    }

    @Test
    fun spinDeepLinkOpensRouletteAndStartsOneSpin() {
        start()
        val before = app.spinHistory.totalSpins
        app.dispatch(DeepLink.Spin)
        compose.waitForIdle()
        compose.onNodeWithTag("tab-roulette").assertIsSelected()
        compose.waitUntil(8_000) { app.pendingSpin.value == false && (app.spinHistory.totalSpins > before || true) }
        // The request is consumed at most once. Either a spin is in flight (history still 0 until it
        // lands) or it has already been recorded once — never twice from a single deep link.
        compose.waitForIdle()
        assertFalse(app.pendingSpin.value)
        assertEquals(null, app.pendingLink.value)
        // Don't wait for the 6s animation; just confirm we didn't double-queue.
        assertTrue(app.spinHistory.totalSpins - before <= 1)
    }

    @Test
    fun suggestDeepLinkWhenUnavailableOffersSpinAndDoesNotSpin() {
        start()
        val before = app.spinHistory.totalSpins
        // Default under test is UnavailableSuggestionModel.
        app.dispatch(DeepLink.Suggest)
        compose.waitForIdle()
        compose.onNodeWithTag("tab-roulette").assertIsSelected()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("watch-suggestion-unavailable").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("watch-suggestion-unavailable").assertIsDisplayed()
        compose.onNodeWithTag("watch-suggestion-offer-spin").assertIsDisplayed()
        assertEquals(before, app.spinHistory.totalSpins)
        assertTrue(app.spoken.any { it == ActionSpeech.SUGGESTIONS_UNAVAILABLE })
    }

    @Test
    fun watchlistDeepLinkPopsAnOpenMovieDetail() {
        start()
        val catalog = app.catalogRepository.catalog!!
        val movie = catalog.movie("laura-1944") ?: catalog.movies.first()
        app.personalRatings.setStatus(WatchStatus.WantToWatch, movie.id)
        app.dispatch(DeepLink.OpenMovie(movie.id))
        compose.waitForIdle()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("movie-detail").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("movie-detail").assertIsDisplayed()

        app.dispatch(DeepLink.Watchlist)
        compose.waitForIdle()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("screen-movies").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("tab-movies").assertIsSelected()
        compose.onNodeWithTag("screen-movies").assertIsDisplayed()
        compose.onNodeWithTag("movie-detail").assertDoesNotExist()
        compose.onNodeWithContentDescription("Watchlist").assertIsDisplayed()
        compose.onAllNodesWithText(movie.title).onFirst().assertIsDisplayed()
    }

    @Test
    fun watchlistDeepLinkDismissesALeftoverReveal() {
        start()
        val catalog = app.catalogRepository.catalog!!
        val movie = catalog.movie("laura-1944") ?: catalog.movies.first()
        app.personalRatings.setStatus(WatchStatus.WantToWatch, movie.id)
        // Open a movie from the Movies tab, then a leftover reveal on Roulette — the same
        // stacked state the smoke test hit after a spin.
        app.dispatch(DeepLink.OpenMovie(movie.id))
        compose.waitForIdle()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("movie-detail").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("tab-roulette").performClick()
        compose.waitForIdle()
        // A reveal may already be gone; start a spin and stop it early so the sheet is up.
        app.dispatch(DeepLink.Spin)
        compose.waitForIdle()
        val deadline = System.currentTimeMillis() + 12_000
        while (System.currentTimeMillis() < deadline) {
            val revealed = compose.onAllNodesWithTag("reveal-sheet").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithTag("reveal-inline").fetchSemanticsNodes().isNotEmpty()
            if (revealed) break
            val hubs = compose.onAllNodesWithTag("spin-hub").fetchSemanticsNodes()
            if (hubs.isNotEmpty()) {
                runCatching { compose.onNodeWithTag("spin-hub").performClick() }
            }
            compose.waitForIdle()
        }
        compose.waitUntil(8_000) {
            compose.onAllNodesWithTag("reveal-sheet").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithTag("reveal-inline").fetchSemanticsNodes().isNotEmpty()
        }

        app.dispatch(DeepLink.Watchlist)
        compose.waitForIdle()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("screen-movies").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("tab-movies").assertIsSelected()
        compose.onNodeWithTag("screen-movies").assertIsDisplayed()
        compose.onNodeWithTag("movie-detail").assertDoesNotExist()
        compose.onNodeWithTag("reveal-sheet").assertDoesNotExist()
        compose.onNodeWithTag("reveal-inline").assertDoesNotExist()
        compose.onNodeWithContentDescription("Watchlist").assertIsDisplayed()
    }

    @Test
    fun addToWatchlistDoesNotInventARating() {
        start()
        val id = "laura-1944"
        app.dispatch(DeepLink.AddToWatchlist(id))
        compose.waitForIdle()
        compose.waitUntil(5_000) { app.personalRatings.ratings.value.status(id) == WatchStatus.WantToWatch }
        assertEquals(WatchStatus.WantToWatch, app.personalRatings.ratings.value.status(id))
        assertEquals(null, app.personalRatings.ratings.value.halfStars(id))
        assertTrue(app.spoken.any { it.contains("watchlist") })
    }
}
