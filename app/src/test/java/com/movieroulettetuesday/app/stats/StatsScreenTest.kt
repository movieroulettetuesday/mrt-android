package com.movieroulettetuesday.app.stats

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import com.movieroulettetuesday.app.catalog.CatalogJson
import com.movieroulettetuesday.app.movies.PersonalRatings
import com.movieroulettetuesday.app.roulette.SpinHistoryStore
import com.movieroulettetuesday.app.roulette.SpinResult
import com.movieroulettetuesday.app.ui.theme.MrtTheme
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StatsScreenTest {
    @get:Rule val compose = createComposeRule()
    private val catalog = CatalogJson.decode(File("src/main/assets/movies.json").readBytes())

    @Test
    fun emptyState() {
        compose.setContent { MrtTheme { StatsScreen(SpinHistoryStore.Log(), PersonalRatings(), catalog) } }
        compose.onNodeWithTag("stats-empty").assertIsDisplayed()
        compose.onNodeWithText("No Stats Yet").assertIsDisplayed()
    }

    @Test
    fun showsSpinsAndRatings() {
        val log = SpinHistoryStore.Log(results = listOf(
            SpinResult(movieId = "laura-1944", categoryId = "cat-criterion", timestampEpochMs = 1_000),
            SpinResult(movieId = "laura-1944", categoryId = "cat-criterion", timestampEpochMs = 2_000),
        ))
        val ratings = PersonalRatings().withHalfStars(7, "piranha-ii-the-spawning-1982", 0)
        compose.setContent { MrtTheme { StatsScreen(log, ratings, catalog, now = 3_000) } }
        compose.onNodeWithTag("stats-overview").assertIsDisplayed()
        compose.onNodeWithText("Total spins").assertIsDisplayed()
        compose.onAllNodesWithText("×2").assertCountEquals(4) // movie, category, By Category, By Genre
        compose.onNodeWithTag("stats-ratings").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("3.5 ★").assertExists()
        compose.onNodeWithText("Piranha II: The Spawning").assertExists()
        compose.onNodeWithTag("stats-recent").performScrollTo().assertIsDisplayed()
    }
}
