package com.movieroulettetuesday.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.movieroulettetuesday.app.ui.MrtApp
import com.movieroulettetuesday.app.ui.TopLevelDestination
import com.movieroulettetuesday.app.ui.theme.MrtTheme
import org.junit.Rule
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
}
