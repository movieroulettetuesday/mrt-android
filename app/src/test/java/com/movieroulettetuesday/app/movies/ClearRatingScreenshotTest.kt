package com.movieroulettetuesday.app.movies

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.movieroulettetuesday.app.catalog.CatalogJson
import com.movieroulettetuesday.app.roulette.SpinHistoryStore
import com.movieroulettetuesday.app.roulette.SpinResult
import com.movieroulettetuesday.app.roulette.SpinStats
import com.movieroulettetuesday.app.stats.StatsScreen
import com.movieroulettetuesday.app.ui.theme.MrtTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Renders Your Rating before clear, after clear, and after re-rating, plus the stats exclusion. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ClearRatingScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun clearAndRerateScreens() {
        val catalog = CatalogJson.decode(File("src/main/assets/movies.json").readBytes())
        val store = PersonalRatingsStore(null)
        val movie = catalog.movies.first { it.id == "laura-1944" }
        store.setHalfStars(7, movie.id)
        store.setHalfStars(2, "piranha-ii-the-spawning-1982")
        val stats = SpinStats(SpinHistoryStore.Log(results = listOf(
            SpinResult(movieId = movie.id, categoryId = "cat-criterion", timestampEpochMs = 1),
        )))
        compose.setContent {
            val ratings by store.ratings.collectAsState()
            MrtTheme {
                MovieDetailScreen(movie.id, MoviesInputs(catalog, ratings, stats, MyServices()), store, {}, {})
            }
        }
        compose.onNodeWithTag("clear-rating").performScrollTo().assertIsDisplayed()
        compose.waitForIdle()
        shot("clear-rating-set")

        compose.onNodeWithTag("clear-rating").performClick()
        compose.waitForIdle()
        assertNull(store.ratings.value.halfStars(movie.id))
        assertEquals(WatchStatus.Watched, store.ratings.value.status(movie.id))
        compose.onNodeWithTag("status-watched").performScrollTo().assertIsDisplayed()
        shot("clear-rating-cleared")

        compose.onNodeWithTag("my-rating-stars").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(4f) }
        compose.onNodeWithTag("clear-rating").performScrollTo().assertIsDisplayed()
        assertEquals(4, store.ratings.value.halfStars(movie.id))
        assertEquals(WatchStatus.Watched, store.ratings.value.status(movie.id))
        shot("clear-rating-rerated")
    }

    @Test fun statsLeaveClearedMovieOut() {
        val catalog = CatalogJson.decode(File("src/main/assets/movies.json").readBytes())
        val ratings = PersonalRatings()
            .withHalfStars(10, "laura-1944", 1)
            .withHalfStars(2, "piranha-ii-the-spawning-1982", 1)
            .withHalfStars(null, "laura-1944", 2)
        compose.setContent { MrtTheme { StatsScreen(SpinHistoryStore.Log(), ratings, catalog) } }
        compose.onNodeWithTag("stats-rating-distribution").performScrollTo().assertIsDisplayed()
        compose.waitForIdle()
        shot("clear-rating-stats")
        assertNull(ratings.halfStars("laura-1944"))
        assertEquals(WatchStatus.Watched, ratings.status("laura-1944"))
        assertEquals(2, ratings.halfStars("piranha-ii-the-spawning-1982"))
    }

    private fun shot(name: String) {
        val root = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(android.graphics.Canvas(bmp))
        val dir = File("build/outputs/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("$name has pixels", bmp.width > 100 && bmp.height > 100)
    }
}
