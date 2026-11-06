package com.movieroulettetuesday.app.movies

import android.app.Application
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.movieroulettetuesday.app.catalog.CatalogJson
import com.movieroulettetuesday.app.roulette.SpinHistoryStore
import com.movieroulettetuesday.app.roulette.SpinResult
import com.movieroulettetuesday.app.roulette.SpinStats
import com.movieroulettetuesday.app.ui.theme.MrtTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class MoviesScreenTest {
    @get:Rule val compose = createComposeRule()
    private val catalog = CatalogJson.decode(File("src/main/assets/movies.json").readBytes())
    private val stats = SpinStats(SpinHistoryStore.Log())

    @Test
    fun listSearchesAndOpensMovie() {
        var opened: String? = null
        var suggested = false
        compose.setContent {
            MrtTheme {
                MoviesScreen(MoviesInputs(catalog, PersonalRatings(), stats, MyServices()), { opened = it }, { suggested = true }, {})
            }
        }
        compose.onNodeWithTag("suggest-row").performClick()
        assertEquals(true, suggested)
        compose.onNodeWithTag("movies-search").performTextInput("piranha")
        compose.onNodeWithTag("movie-row-piranha-ii-the-spawning-1982").assertIsDisplayed().performClick()
        assertEquals("piranha-ii-the-spawning-1982", opened)
        compose.onNodeWithTag("movies-list").performScrollToNode(hasTestTag("movies-footer"))
        compose.onNodeWithTag("movies-footer").assertTextContains("1 of ${catalog.movies.size} movies")
    }

    @Test
    fun detailRatesSetsStatusAndOpensTmdb() {
        val store = PersonalRatingsStore(null)
        val movie = catalog.movies.first { it.streaming?.stream?.isNotEmpty() == true }
        compose.setContent {
            val ratings by store.ratings.collectAsState()
            MrtTheme {
                MovieDetailScreen(movie.id, MoviesInputs(catalog, ratings, stats, MyServices(setOf(movie.streaming!!.stream.first().id))),
                    store, onBack = {}, onOpenEpisode = {})
            }
        }
        compose.onNodeWithTag("movie-title").assertTextContains(movie.title)
        compose.onNodeWithTag("streaming-stream").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("my-rating-stars").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(7f) }
        compose.onNodeWithTag("my-rating-label").assertTextContains("3.5 of 5")
        assertEquals(7, store.ratings.value.halfStars(movie.id))
        assertEquals(WatchStatus.Watched, store.ratings.value.status(movie.id))
        compose.onNodeWithTag("status-wantToWatch").performScrollTo().performClick()
        assertEquals(WatchStatus.WantToWatch, store.ratings.value.status(movie.id))
        compose.onNodeWithTag("streaming-tmdb-link").performScrollTo().performClick()
        val app = ApplicationProvider.getApplicationContext<Application>()
        assertEquals(movie.streaming!!.link, shadowOf(app).nextStartedActivity.dataString)
    }

    @Test
    fun detailClearsRatingKeepsWatchedAndCanReRate() {
        val dir = java.nio.file.Files.createTempDirectory("rate-ui").toFile()
        val store = PersonalRatingsStore(File(dir, PersonalRatingsStore.FILE_NAME)) { 7 }
        val movie = catalog.movies.first { it.id == "laura-1944" }
        val stats = SpinStats(SpinHistoryStore.Log(results = listOf(
            SpinResult(movieId = movie.id, categoryId = "cat-criterion", timestampEpochMs = 1),
        )))
        compose.setContent {
            val ratings by store.ratings.collectAsState()
            MrtTheme {
                MovieDetailScreen(movie.id, MoviesInputs(catalog, ratings, stats, MyServices()), store, onBack = {}, onOpenEpisode = {})
            }
        }
        compose.onNodeWithTag("clear-rating").assertDoesNotExist()
        compose.onNodeWithTag("movie-spin-count").performScrollTo().assertTextContains("Spun 1 time")
        val stars = compose.onNodeWithTag("my-rating-stars")
        assertEquals(listOf("Increase rating", "Decrease rating"), customActionLabels())

        stars.performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(7f) }
        compose.onNodeWithTag("my-rating-label").assertTextContains("3.5 of 5")
        assertEquals(7, store.ratings.value.halfStars(movie.id))
        assertEquals(WatchStatus.Watched, store.ratings.value.status(movie.id))
        assertEquals(listOf("Clear rating", "Increase rating", "Decrease rating"), customActionLabels())
        compose.onNodeWithContentDescription("Clear rating").performScrollTo().assertIsDisplayed()

        assertTrue(customAction("Clear rating"))
        compose.waitForIdle()
        assertNull(store.ratings.value.halfStars(movie.id))
        assertEquals(WatchStatus.Watched, store.ratings.value.status(movie.id))
        compose.onNodeWithTag("my-rating-label").assertDoesNotExist()
        compose.onNodeWithTag("clear-rating").assertDoesNotExist()
        compose.onNodeWithTag("movie-spin-count").assertTextContains("Spun 1 time")
        assertEquals(listOf("Increase rating", "Decrease rating"), customActionLabels())

        stars.performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(4f) }
        compose.onNodeWithTag("my-rating-label").assertTextContains("2 of 5")
        compose.onNodeWithTag("clear-rating").performScrollTo().performClick()
        compose.waitForIdle()
        assertNull(store.ratings.value.halfStars(movie.id))
        assertEquals(WatchStatus.Watched, store.ratings.value.status(movie.id))
        compose.onNodeWithTag("clear-rating").assertDoesNotExist()

        stars.performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(10f) }
        compose.onNodeWithTag("my-rating-label").assertTextContains("5 of 5")
        assertEquals(10, store.ratings.value.halfStars(movie.id))
        val reloaded = PersonalRatingsStore(File(dir, PersonalRatingsStore.FILE_NAME)).ratings.value
        assertEquals(10, reloaded.halfStars(movie.id))
        assertEquals(5, reloaded.rating(movie.id))
        assertEquals(WatchStatus.Watched, reloaded.status(movie.id))
        assertEquals(1, stats.spinCount(movie.id))
    }

    private fun customActionLabels(): List<String> =
        compose.onNodeWithTag("my-rating-stars").fetchSemanticsNode().config[SemanticsActions.CustomActions].map { it.label }

    private fun customAction(label: String): Boolean =
        compose.onNodeWithTag("my-rating-stars").fetchSemanticsNode().config[SemanticsActions.CustomActions]
            .first { it.label == label }.action()
}
