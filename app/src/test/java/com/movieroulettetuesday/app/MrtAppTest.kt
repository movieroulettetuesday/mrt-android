package com.movieroulettetuesday.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.movieroulettetuesday.app.ui.MrtApp
import com.movieroulettetuesday.app.ui.TopLevelDestination
import com.movieroulettetuesday.app.ui.theme.MrtTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import org.junit.Test
import org.junit.runner.RunWith

/** Compose UI test running on the JVM under Robolectric. */
@RunWith(AndroidJUnit4::class)
class MrtAppTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun startsOnRouletteAndSwitchesTabs() {
        compose.setContent { MrtTheme { MrtApp() } }
        compose.onNodeWithTag("screen-roulette").assertIsDisplayed()
        compose.onNodeWithTag("tab-roulette").assertIsSelected()
        for (dest in TopLevelDestination.entries) {
            compose.onNodeWithTag("tab-${dest.route}").performClick()
            compose.onNodeWithTag("screen-${dest.route}").assertIsDisplayed()
            compose.onNodeWithTag("tab-${dest.route}").assertIsSelected()
        }
    }

    @Test
    fun moviesTabOpensMoviePageAndBack() {
        compose.setContent { MrtTheme { MrtApp() } }
        compose.onNodeWithTag("tab-movies").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("movies-search").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("movies-search").performTextInput("laura")
        compose.onNodeWithTag("movie-row-laura-1944").performClick()
        compose.onNodeWithTag("movie-detail").assertIsDisplayed()
        compose.onNodeWithTag("tab-movies").assertIsSelected()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithTag("movies-search").assertIsDisplayed()
    }

    @Test
    fun gearOpensSettingsAndCredits() {
        compose.setContent { MrtTheme { MrtApp() } }
        compose.onNodeWithTag("open-settings").performClick()
        compose.onNodeWithTag("settings-screen").assertIsDisplayed()
        compose.onNodeWithTag("settings-credits").performScrollTo().performClick()
        compose.onNodeWithTag("credits-screen").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithTag("settings-my-services").performScrollTo().performClick()
        compose.onNodeWithTag("my-services-screen").assertIsDisplayed()
    }

    @Test
    fun notificationTapOpensEpisodePage() {
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<MrtApplication>()
        compose.setContent { MrtTheme { MrtApp() } }
        app.pendingEpisode.value = "Buzzsprout-unknown"
        compose.waitForIdle()
        compose.onNodeWithTag("tab-episodes").assertIsSelected()
        compose.onNodeWithContentDescription("Back").assertIsDisplayed()
        assertEquals(null, app.pendingEpisode.value)
    }

    @Test
    fun widgetTapOpensTodaysMovie() {
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<MrtApplication>()
        compose.setContent { MrtTheme { MrtApp() } }
        compose.waitForIdle()
        compose.waitUntil(5_000) { app.catalogRepository.catalog != null }
        val catalog = app.catalogRepository.catalog!!
        val pick = com.movieroulettetuesday.app.widget.SpinOfTheDay.pick("2026-10-21", catalog)!!
        app.pendingMovie.value = pick.movie.id
        compose.waitForIdle()
        compose.onNodeWithTag("tab-movies").assertIsSelected()
        compose.onNodeWithContentDescription("Back").assertIsDisplayed()
        compose.onAllNodesWithText(pick.movie.title).onFirst().assertIsDisplayed()
        assertEquals(null, app.pendingMovie.value)
    }
}
