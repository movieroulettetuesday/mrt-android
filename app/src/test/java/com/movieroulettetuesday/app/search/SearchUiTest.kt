package com.movieroulettetuesday.app.search

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.movieroulettetuesday.app.catalog.CatalogJson
import com.movieroulettetuesday.app.data.InMemoryKeyValueStore
import com.movieroulettetuesday.app.episodes.EpisodeFixtures
import com.movieroulettetuesday.app.episodes.EpisodeRepository
import com.movieroulettetuesday.app.episodes.EpisodesScreen
import com.movieroulettetuesday.app.movies.MoviesInputs
import com.movieroulettetuesday.app.movies.MoviesScreen
import com.movieroulettetuesday.app.movies.MyServices
import com.movieroulettetuesday.app.movies.PersonalRatings
import com.movieroulettetuesday.app.roulette.SpinHistoryStore
import com.movieroulettetuesday.app.roulette.SpinStats
import com.movieroulettetuesday.app.ui.theme.MrtTheme
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 1.1 search fields on the Movies and Episodes lists (iOS `SearchUITests`). The matcher's rules are
 * unit-tested in [CatalogSearchTest]; this checks the fields, results, empty states, and opening a hit.
 */
@RunWith(RobolectricTestRunner::class)
class SearchUiTest {
    @get:Rule val compose = createComposeRule()
    private val catalog = CatalogJson.decode(File("src/main/assets/movies.json").readBytes())
    private val stats = SpinStats(SpinHistoryStore.Log())

    private fun episodesRepo(): EpisodeRepository {
        val rss = EpisodeFixtures.rss(
            EpisodeFixtures.item("Buzzsprout-20099500", "The Devil&apos;s Rain", "Tue, 04 Nov 2025 04:00:00 -0500", 4, 39),
            EpisodeFixtures.item("Buzzsprout-x38", "Pinocchio and the Puppet Masters", "Tue, 28 Oct 2025 04:00:00 -0500", 4, 38),
            EpisodeFixtures.item("Buzzsprout-x40", "Bonus: Listener Mailbag", "Tue, 11 Nov 2025 04:00:00 -0500", 4, 40),
        )
        return EpisodeRepository(null, InMemoryKeyValueStore(), { rss.toByteArray() }).also { runBlocking { it.load() } }
    }

    private fun setMovies(onOpen: (String) -> Unit = {}, requestedQuery: String? = null) {
        compose.setContent {
            MrtTheme {
                MoviesScreen(MoviesInputs(catalog, PersonalRatings(), stats, MyServices()), onOpen, {}, {},
                    requestedQuery = requestedQuery)
            }
        }
    }

    @Test
    fun moviesSearchFindsApostropheTitleShowsEmptyStateAndOpensResult() {
        var opened: String? = null
        setMovies(onOpen = { opened = it })
        compose.onNodeWithText("Title, year, S4E39, service").assertIsDisplayed()
        compose.onNodeWithTag("movies-search").performTextInput("zzqx")
        compose.onNodeWithTag("movies-search-empty").assertIsDisplayed()
        compose.onNodeWithText("No Results for “zzqx”").assertIsDisplayed()
        compose.onNodeWithText("Check the spelling or try a new search.").assertIsDisplayed()
        assertTrue("Suggest row hides while searching", compose.onAllNodesWithTag("suggest-row").fetchSemanticsNodes().isEmpty())

        compose.onNodeWithTag("movies-search").performTextClearance()
        compose.onNodeWithTag("movies-search").performTextInput("devils rain")
        compose.onNodeWithTag("movie-row-the-devils-rain-1975").assertIsDisplayed()
        compose.onNodeWithTag("movies-list").performScrollToNode(hasTestTag("movies-footer"))
        compose.onNodeWithTag("movies-footer").assertTextContains("1 of ${catalog.movies.size} movies")
        compose.onNodeWithTag("movies-list").performScrollToNode(hasTestTag("movie-row-the-devils-rain-1975"))
        compose.onNodeWithTag("movie-row-the-devils-rain-1975").performClick()
        assertEquals("the-devils-rain-1975", opened)
    }

    @Test
    fun moviesProviderMatchAndClearRestoresList() {
        setMovies()
        // Shudder is only a streaming provider in the bundled catalog (rank 6, stream list).
        compose.onNodeWithTag("movies-search").performTextInput("shudder")
        compose.onNodeWithTag("movies-list").performScrollToNode(hasTestTag("movie-row-possession-1981"))
        compose.onNodeWithTag("movie-row-possession-1981").assertIsDisplayed()
        compose.onNodeWithTag("movies-list").performScrollToNode(hasTestTag("movies-search"))
        compose.onNodeWithTag("movies-search-clear").performClick()
        compose.onNodeWithTag("suggest-row").assertIsDisplayed()
        compose.onNodeWithTag("movies-list").performScrollToNode(hasTestTag("movies-footer"))
        compose.onNodeWithTag("movies-footer").assertTextContains("${catalog.movies.size} of ${catalog.movies.size} movies")
    }

    @Test
    fun moviesYearDisambiguates() {
        setMovies()
        compose.onNodeWithTag("movies-search").performTextInput("the thing 1982")
        compose.onNodeWithTag("movie-row-the-thing-1982").assertIsDisplayed()
        compose.onNodeWithTag("movies-list").performScrollToNode(hasTestTag("movies-footer"))
        compose.onNodeWithTag("movies-footer").assertTextContains("1 of ${catalog.movies.size} movies")
    }

    @Test
    fun moviesRequestedQueryFillsTheField() {
        var consumed = false
        compose.setContent {
            MrtTheme {
                MoviesScreen(MoviesInputs(catalog, PersonalRatings(), stats, MyServices()), {}, {}, {},
                    requestedQuery = "devils rain", onRequestedQueryConsumed = { consumed = true })
            }
        }
        compose.onNodeWithTag("movie-row-the-devils-rain-1975").assertIsDisplayed()
        assertTrue(consumed)
    }

    @Test
    fun episodesSearchFindsSeasonEpisodeShowsEmptyStateAndOpensIt() {
        val repo = episodesRepo()
        var opened: String? = null
        compose.setContent { MrtTheme { EpisodesScreen(repo, catalog, InMemoryKeyValueStore(), onOpenEpisode = { opened = it }) } }
        compose.onNodeWithTag("episodes-grouping").assertIsDisplayed()
        compose.onNodeWithText("Title, S4E39, or movie").assertIsDisplayed()

        compose.onNodeWithTag("episodes-search").performTextInput("S4E39")
        compose.onNodeWithTag("episodes-search-count").assertTextEquals("1 result")
        assertTrue("grouping hides while searching", compose.onAllNodesWithTag("episodes-grouping").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithTag("episode-row-Buzzsprout-20099500").assertIsDisplayed().performClick()
        assertEquals("Buzzsprout-20099500", opened)

        compose.onNodeWithTag("episodes-search").performTextClearance()
        compose.onNodeWithTag("episodes-search").performTextInput("zzqx")
        compose.onNodeWithTag("episodes-search-empty").assertIsDisplayed()
        compose.onNodeWithText("No Results for “zzqx”").assertIsDisplayed()

        compose.onNodeWithTag("episodes-search").performTextClearance()
        compose.onNodeWithTag("episodes-search").performTextInput("2025")
        compose.onNodeWithTag("episodes-search-count").assertTextEquals("3 results")

        compose.onNodeWithTag("episodes-search-clear").performClick()
        compose.onNodeWithTag("episodes-grouping").assertIsDisplayed()
    }

    @Test
    fun episodesSearchMatchesLinkedMovieGenreAndRequestedQuery() {
        val repo = episodesRepo()
        var consumed = false
        compose.setContent {
            MrtTheme {
                EpisodesScreen(repo, catalog, InMemoryKeyValueStore(), onOpenEpisode = {},
                    requestedQuery = "devils rain horror", onRequestedQueryConsumed = { consumed = true })
            }
        }
        assertTrue(consumed)
        // Words split across title and genre don't match; the linked movie's genre alone does.
        compose.onNodeWithTag("episodes-search-empty").assertIsDisplayed()
        compose.onNodeWithTag("episodes-search").performTextClearance()
        compose.onNodeWithTag("episodes-search").performTextInput("horror")
        compose.onNodeWithTag("episodes-search-count").assertTextEquals("1 result")
        compose.onNodeWithTag("episode-row-Buzzsprout-20099500").assertIsDisplayed()
    }
}
