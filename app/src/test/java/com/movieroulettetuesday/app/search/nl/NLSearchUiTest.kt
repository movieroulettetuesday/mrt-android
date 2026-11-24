package com.movieroulettetuesday.app.search.nl

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.movieroulettetuesday.app.MrtApplication
import com.movieroulettetuesday.app.data.InMemoryKeyValueStore
import com.movieroulettetuesday.app.episodes.EpisodeFixtures
import com.movieroulettetuesday.app.episodes.EpisodeRepository
import com.movieroulettetuesday.app.episodes.EpisodesScreen
import com.movieroulettetuesday.app.movies.MovieQuery
import com.movieroulettetuesday.app.movies.MoviesInputs
import com.movieroulettetuesday.app.movies.MoviesScreen
import com.movieroulettetuesday.app.movies.MyServices
import com.movieroulettetuesday.app.movies.PersonalRatings
import com.movieroulettetuesday.app.roulette.SpinHistoryStore
import com.movieroulettetuesday.app.roulette.SpinStats
import com.movieroulettetuesday.app.ui.MrtApp
import com.movieroulettetuesday.app.ui.theme.MrtTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 1.1 NL search UI with a fake model (no AICore): chips on AVAILABLE, chips AND the toolbar, the list
 * switch, and the hidden state on unsupported devices (Galaxy Tab SM-X133 reports UNAVAILABLE).
 */
@RunWith(RobolectricTestRunner::class)
class NLSearchUiTest {
    @get:Rule val compose = createComposeRule()
    private val catalog = NLFixtures.catalog
    private val stats = SpinStats(SpinHistoryStore.Log())
    private val total = catalog.movies.size

    private val replies = mapOf(
        "free horror" to """{"genre":"Horror","freeOnly":true}""",
        "episodes about The Thing" to """{"kind":"episode","title":"The Thing"}""",
        "movies I haven't watched" to """{"kind":"movie","watched":"unwatched"}""",
        "show me movies I haven't watched" to """{"kind":"movie","watched":"unwatched"}""",
    )

    private fun model(status: NLModelStatus = NLModelStatus.Available) = FakeNLModel(currentStatus = status) { replies[it] }

    private fun keywordCount(text: String) =
        MovieQuery(text).apply(catalog.movies, PersonalRatings(), stats, MyServices(), catalog).size

    private fun footer(expected: String) {
        compose.onNodeWithTag("movies-list").performScrollToNode(hasTestTag("movies-footer"))
        compose.onNodeWithTag("movies-footer").assertTextContains(expected)
    }

    private fun waitForTag(tag: String) = compose.waitUntil(5_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    private fun hasNo(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()

    private fun setMovies(
        model: NLSearchModel,
        onSwitch: (NLSearchFilter.Kind, String, NLSearchFilter) -> Unit = { _, _, _ -> },
        onDownloadable: () -> Unit = {},
    ) {
        compose.setContent {
            MrtTheme {
                MoviesScreen(MoviesInputs(catalog, PersonalRatings(), stats, MyServices()), {}, {}, {},
                    nlModel = model, onNLSwitch = onSwitch, onNLDownloadable = onDownloadable)
            }
        }
    }

    @Test
    fun chipsAppearOnAParsedQuestionAndAndWithTheToolbar() {
        val model = model()
        setMovies(model)
        compose.onNodeWithTag("movies-search").performTextInput("free horror")
        waitForTag("nl-chips")
        compose.onNodeWithTag("nl-chip-Genre").assertIsDisplayed().assertTextContains("Horror")
        compose.onNodeWithTag("nl-chip-Free").assertIsDisplayed()
        assertEquals(listOf("free horror"), model.questions)
        footer("28 of $total movies")

        // Removing a chip re-runs apply without asking the model again.
        compose.onNodeWithTag("movies-list").performScrollToNode(hasTestTag("nl-chips"))
        compose.onNodeWithTag("nl-chip-Free").performClick()
        compose.waitForIdle()
        assertTrue(hasNo("nl-chip-Free"))
        footer("44 of $total movies")
        assertEquals(1, model.questions.size)

        // A toolbar category ANDs with the chips; the chips stay.
        compose.onNodeWithTag("menu-category").performClick()
        compose.onNodeWithTag("category-cat-cult").performClick()
        val cultHorror = catalog.movies.count { it.genre == "Horror" && "cat-cult" in it.categories }
        footer("$cultHorror of $total movies")
        compose.onNodeWithTag("movies-list").performScrollToNode(hasTestTag("nl-chips"))
        compose.onNodeWithTag("nl-chip-Genre").assertIsDisplayed()
    }

    @Test
    fun unavailableDeviceHidesAllAiAndKeepsKeywordSearch() {
        // SM-X133-like: checkStatus() is UNAVAILABLE.
        val model = model(NLModelStatus.Unavailable)
        setMovies(model)
        compose.onNodeWithTag("movies-search").performTextInput("free horror")
        compose.mainClock.advanceTimeBy(3_000)
        compose.waitForIdle()
        assertTrue(hasNo("nl-chips"))
        assertTrue("never generates", model.questions.isEmpty())
        footer("${keywordCount("free horror")} of $total movies")
        compose.onNodeWithTag("movies-search").performTextClearance()
        compose.onNodeWithTag("movies-search").performTextInput("devils rain")
        compose.onNodeWithTag("movie-row-the-devils-rain-1975").assertIsDisplayed()
        footer("1 of $total movies")
    }

    @Test
    fun downloadableModelStaysHiddenAndAsksForTheDownload() {
        val model = model(NLModelStatus.Downloadable)
        var downloads = 0
        setMovies(model, onDownloadable = { downloads++ })
        compose.onNodeWithTag("movies-search").performTextInput("free horror")
        compose.mainClock.advanceTimeBy(3_000)
        compose.waitForIdle()
        assertTrue(hasNo("nl-chips"))
        assertTrue(model.questions.isEmpty())
        assertTrue("download requested", downloads >= 1)
    }

    @Test
    fun plainKeywordsNeverReachTheModel() {
        val model = model()
        setMovies(model)
        compose.onNodeWithTag("movies-search").performTextInput("the thing")
        compose.mainClock.advanceTimeBy(3_000)
        compose.waitForIdle()
        assertTrue(hasNo("nl-chips"))
        assertTrue(model.questions.isEmpty())
        compose.onNodeWithTag("movie-row-the-thing-1982").assertIsDisplayed()
    }

    @Test
    fun episodeQuestionOnMoviesSwitchesAndClearsTheMoviesField() {
        var switched: Triple<NLSearchFilter.Kind, String, NLSearchFilter>? = null
        setMovies(model(), onSwitch = { k, q, f -> switched = Triple(k, q, f) })
        compose.onNodeWithTag("movies-search").performTextInput("episodes about The Thing")
        compose.waitUntil(5_000) { switched != null }
        assertEquals(
            Triple(NLSearchFilter.Kind.Episode, "episodes about The Thing", NLSearchFilter(title = "The Thing", kind = NLSearchFilter.Kind.Episode)),
            switched,
        )
        compose.waitForIdle()
        assertTrue("source query cleared", hasNo("movies-search-clear"))
        assertTrue(hasNo("nl-chips"))
        footer("$total of $total movies")
    }

    @Test
    fun movieQuestionOnMoviesStaysAndShowsChips() {
        var switched = false
        setMovies(model(), onSwitch = { _, _, _ -> switched = true })
        compose.onNodeWithTag("movies-search").performTextInput("movies I haven't watched")
        waitForTag("nl-chip-Watched")
        compose.onNodeWithTag("nl-chip-Watched").assertTextContains("Unwatched")
        assertTrue(!switched)
        footer("$total of $total movies")
    }

    private fun episodesRepo(): EpisodeRepository {
        val rss = EpisodeFixtures.rss(
            EpisodeFixtures.item("Buzzsprout-20099500", "The Devil&apos;s Rain", "Tue, 04 Nov 2025 04:00:00 -0500", 4, 39),
            EpisodeFixtures.item("Buzzsprout-19314551", "The Thing", "Tue, 15 Jul 2025 04:00:00 -0500", 4, 30),
            EpisodeFixtures.item("Buzzsprout-x40", "Bonus: Listener Mailbag", "Tue, 11 Nov 2025 04:00:00 -0500", 4, 40),
        )
        return EpisodeRepository(null, InMemoryKeyValueStore(), { rss.toByteArray() }).also { runBlocking { it.load() } }
    }

    @Test
    fun episodesShowHandedOverChipsWithoutReparsingAndSwitchBackToMovies() {
        val model = model()
        var consumed = false
        var switched: Triple<NLSearchFilter.Kind, String, NLSearchFilter>? = null
        val handed = NLSearchFilter(title = "The Thing", kind = NLSearchFilter.Kind.Episode)
        val repo = episodesRepo()
        compose.setContent {
            MrtTheme {
                EpisodesScreen(repo, catalog, InMemoryKeyValueStore(), onOpenEpisode = {},
                    requestedQuery = "episodes about The Thing", onRequestedQueryConsumed = { consumed = true },
                    nlModel = model, requestedNLFilter = handed, onNLSwitch = { k, q, f -> switched = Triple(k, q, f) })
            }
        }
        waitForTag("nl-chip-Title")
        assertTrue(consumed)
        compose.onNodeWithTag("nl-chip-Title").assertTextContains("Title: The Thing")
        compose.onNodeWithTag("episodes-search-count").assertTextEquals("1 result")
        compose.onNodeWithTag("episode-row-Buzzsprout-19314551").assertIsDisplayed()
        assertTrue("handed-over chips are not parsed again", model.questions.isEmpty())

        compose.onNodeWithTag("episodes-search").performTextClearance()
        compose.onNodeWithTag("episodes-search").performTextInput("show me movies I haven't watched")
        compose.waitUntil(5_000) { switched != null }
        assertEquals(NLSearchFilter.Kind.Movie, switched!!.first)
        assertEquals(NLSearchFilter(kind = NLSearchFilter.Kind.Movie, watched = NLSearchFilter.Watched.Unwatched), switched!!.third)
        compose.waitForIdle()
        assertTrue("source query cleared", hasNo("episodes-search-clear"))
        compose.onNodeWithTag("episodes-grouping").assertIsDisplayed()
    }

    @Test
    fun episodesHideAiWhenUnavailable() {
        val model = model(NLModelStatus.Unavailable)
        val repo = episodesRepo()
        compose.setContent {
            MrtTheme { EpisodesScreen(repo, catalog, InMemoryKeyValueStore(), onOpenEpisode = {}, nlModel = model) }
        }
        compose.onNodeWithTag("episodes-search").performTextInput("S4E39")
        compose.mainClock.advanceTimeBy(3_000)
        compose.waitForIdle()
        assertTrue(hasNo("nl-chips"))
        assertTrue(model.questions.isEmpty())
        // Keyword search is unchanged.
        compose.onNodeWithTag("episodes-search-count").assertTextEquals("1 result")
    }

    @Test
    fun appSwitchMovesTheQueryToEpisodesAndBackDoesNotBounce() {
        val app = ApplicationProvider.getApplicationContext<MrtApplication>()
        val model = model()
        app.nlSearchModel = model
        compose.setContent { MrtTheme { MrtApp() } }
        compose.waitUntil(8_000) { app.catalogRepository.catalog != null }
        compose.onNodeWithTag("tab-movies").performClick()
        waitForTag("movies-search")
        compose.onNodeWithTag("movies-search").performTextInput("episodes about The Thing")
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithTag("tab-episodes").assertIsSelected() }.isSuccess }
        compose.waitForIdle()
        assertEquals("query handed over and consumed", null, app.pendingEpisodeSearch.value)
        assertEquals(null, app.pendingEpisodeNLFilter.value)
        assertEquals(listOf("episodes about The Thing"), model.questions)

        compose.onNodeWithTag("tab-movies").performClick()
        waitForTag("movies-search")
        compose.waitForIdle()
        compose.onNodeWithTag("tab-movies").assertIsSelected()
        assertTrue("Movies query was cleared", hasNo("movies-search-clear"))
        assertEquals("no second parse, no bounce", 1, model.questions.size)
    }
}
