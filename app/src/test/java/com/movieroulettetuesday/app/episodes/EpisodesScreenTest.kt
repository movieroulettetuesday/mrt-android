package com.movieroulettetuesday.app.episodes

import android.app.Application
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.movieroulettetuesday.app.data.InMemoryKeyValueStore
import com.movieroulettetuesday.app.ui.theme.MrtTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class EpisodesScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun repo(): EpisodeRepository {
        val bytes = EpisodeFixtures.resource("feed-sample.rss")
        return EpisodeRepository(null, InMemoryKeyValueStore(), { bytes }).also { runBlocking { it.load() } }
    }

    @Test
    fun listGroupsBySeasonAndOpensEpisode() {
        val repo = repo()
        val settings = InMemoryKeyValueStore()
        var opened: String? = null
        compose.setContent { MrtTheme { EpisodesScreen(repo, null, settings, onOpenEpisode = { opened = it }) } }
        compose.onNodeWithTag("episode-group-season-4").assertIsDisplayed()
        compose.onNodeWithTag("episode-group-bonus").assertIsDisplayed()
        compose.onNodeWithTag("episode-row-Buzzsprout-19980357").performClick()
        assertEquals("Buzzsprout-19980357", opened)
        // Bonus starts collapsed (not newest); tapping the header opens it and persists the state.
        compose.onNodeWithTag("episode-group-bonus").performClick()
        compose.onNodeWithTag("episodes-list").performScrollToNode(hasTestTag("episode-row-Buzzsprout-11111111"))
        compose.onNodeWithTag("episode-row-Buzzsprout-11111111").assertIsDisplayed()
        assertEquals("bonus:1", settings.getString(EpisodeRepository.EXPANDED_KEY))
        compose.onNodeWithTag("episodes-list").performScrollToNode(hasTestTag("episodes-grouping"))
        compose.onNodeWithTag("grouping-year").performClick()
        compose.onNodeWithTag("episode-group-year-2026").assertIsDisplayed()
        assertEquals("year", settings.getString(EpisodeRepository.GROUPING_KEY))
    }

    @Test
    fun detailShowsNotesListenLinksAndOpensFanMail() {
        val repo = repo()
        val links = AppleEpisodeLinks(null, fetch = null)
        compose.setContent {
            MrtTheme { EpisodeDetailScreen("Buzzsprout-19980357", repo, links, null, onBack = {}, onOpenMovie = null) }
        }
        compose.onNodeWithTag("episode-title").assertIsDisplayed()
        compose.onAllNodesWithText("S4E36").onFirst().assertIsDisplayed()
        compose.onNodeWithTag("episode-fan-mail").performScrollTo().performClick()
        val app = ApplicationProvider.getApplicationContext<Application>()
        val intent = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("https://www.buzzsprout.com/2117292/fan_mail/new", intent.dataString)
        compose.onNodeWithTag("listen-buzzsprout").performScrollTo().performClick()
        assertEquals("https://www.buzzsprout.com/2117292/episodes/19980357", shadowOf(app).nextStartedActivity.dataString)
        compose.onNodeWithTag("listen-apple").assertIsDisplayed()
        compose.onNodeWithText("Show page (episode not listed yet)").assertIsDisplayed()
    }
}
