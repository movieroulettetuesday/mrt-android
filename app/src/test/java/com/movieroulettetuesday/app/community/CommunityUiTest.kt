package com.movieroulettetuesday.app.community

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.movieroulettetuesday.app.ui.MrtApp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.movieroulettetuesday.app.MrtApplication
import com.movieroulettetuesday.app.catalog.ShowInfo
import com.movieroulettetuesday.app.data.InMemoryKeyValueStore
import com.movieroulettetuesday.app.ui.theme.MrtTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CommunityUiTest {
    @get:Rule val compose = createComposeRule()
    private val poll = PollFactory.defaultPoll("Buzzsprout-19877460", ShowInfo.MRT.hosts) // good/bad

    @After fun reset() { ApplicationProvider.getApplicationContext<MrtApplication>().community = null }

    @Test
    fun voteRevealsResultsWithPercentAndCount() {
        val store = FakeCommunityStore().apply { votes[poll.id] = mutableMapOf("v1" to "bad", "v2" to "bad", "v3" to "good") }
        val service = CommunityService(store, InMemoryKeyValueStore())
        compose.setContent { MrtTheme { Column { EpisodePollSection(poll, service) } } }
        compose.onNodeWithText("Was this movie good or bad?").assertExists()
        compose.onNodeWithText("Vote to see results").assertExists()
        compose.onNodeWithTag("poll-option-good").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("poll-total").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("4 votes").assertExists()
        assertEquals(2, compose.onAllNodesWithText("50%").fetchSemanticsNodes().size) // 2 good (incl. mine), 2 bad
        compose.onNodeWithContentDescription("Your vote").assertExists()
        compose.onNodeWithText(CommunityCopy.POLL_FOOTER_CHANGE).assertExists()
        // Change the vote.
        compose.onNodeWithTag("poll-result-bad").performClick()
        compose.waitUntil(5_000) { service.myVote(poll) == "bad" }
        compose.onNodeWithText("75%").assertExists()
    }

    @Test
    fun readOnlyBuildShowsResultsButNoVoting() {
        val store = FakeCommunityStore(canWrite = false).apply { votes[poll.id] = mutableMapOf("v1" to "good") }
        val service = CommunityService(store, InMemoryKeyValueStore())
        compose.setContent { MrtTheme { Column { EpisodePollSection(poll, service) } } }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("poll-total").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("1 vote").assertExists()
        compose.onNodeWithText(CommunityCopy.READ_ONLY).assertExists()
        compose.onNodeWithTag("poll-result-bad").assertIsNotEnabled()
    }

    @Test
    fun listenerRatingShowsAverageAndOffersToShareMine() {
        val store = FakeCommunityStore().apply { ratings["the-room-2003"] = mutableMapOf("r1" to 2, "r2" to 4) }
        val service = CommunityService(store, InMemoryKeyValueStore())
        compose.setContent { MrtTheme { Column { ListenerRatingSection("the-room-2003", 7, service) } } }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("listener-average").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("1.5").assertExists()
        compose.onNodeWithText("2 ratings").assertExists()
        compose.onNodeWithText("Add my 3.5 ★ rating to the listener average").performClick()
        compose.waitUntil(5_000) { service.myListenerRating("the-room-2003") == 7 }
        compose.onNodeWithText("3 ratings").assertExists()
        compose.onNodeWithText("Your listener rating").assertExists()
        assertEquals(0, compose.onAllNodesWithTag("share-listener-rating").fetchSemanticsNodes().size)
    }

    @Test
    fun noRatingsYet() {
        val service = CommunityService(FakeCommunityStore(), InMemoryKeyValueStore())
        compose.setContent { MrtTheme { Column { ListenerRatingSection("m", null, service) } } }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("listener-none").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Rate it under Your Rating to add your stars.").assertExists()
    }

    private fun openLaura() {
        compose.onNodeWithTag("tab-movies").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("movies-search").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("movies-search").performTextInput("laura")
        compose.onNodeWithTag("movie-row-laura-1944").performClick()
        compose.onNodeWithTag("movie-detail").assertExists()
    }

    @Test
    fun hiddenUnlessConfigured() {
        val app = ApplicationProvider.getApplicationContext<MrtApplication>()
        assertNull(app.community) // no API token under tests / in builds without MRT_CLOUDKIT_API_TOKEN
        compose.setContent { MrtTheme { MrtApp() } }
        openLaura()
        assertEquals(0, compose.onAllNodesWithTag("listener-rating").fetchSemanticsNodes().size)
    }

    @Test
    fun moviePageShowsListenerRatingWhenConfigured() {
        val app = ApplicationProvider.getApplicationContext<MrtApplication>()
        app.community = app.createCommunity(FakeCommunityStore().apply { ratings["laura-1944"] = mutableMapOf("r" to 10) })
        compose.setContent { MrtTheme { MrtApp() } }
        openLaura()
        compose.onNodeWithTag("listener-rating").performScrollTo()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("listener-average").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("1 rating").assertExists()
    }
}
