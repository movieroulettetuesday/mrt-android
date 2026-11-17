package com.movieroulettetuesday.app.suggestions

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.movieroulettetuesday.app.catalog.Movie
import com.movieroulettetuesday.app.ui.theme.MrtTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WatchSuggestionCardTest {
    @get:Rule val compose = createComposeRule()

    private val movie = Movie("laura-1944", "Laura", 1944, "Thriller")

    @Test
    fun cardShowsTitleYearGenreWhyAndActions() {
        val reason = PickReason(Vibe.Thrills, listOf(Fact.OnWatchlist, Fact.NotWatched, Fact.Streaming("Netflix")), fromModel = true)
        var open = 0
        var wtw = 0
        var spin = 0
        compose.setContent {
            MrtTheme {
                WatchSuggestionCard(
                    movie, reason,
                    onOpenMovie = { open++ },
                    onWhereToWatch = { wtw++ },
                    onSpinInstead = { spin++ },
                    onDismiss = {},
                )
            }
        }
        compose.onNodeWithTag("watch-suggestion-card").assertIsDisplayed()
        compose.onNodeWithTag("watch-suggestion-title").assertIsDisplayed()
        compose.onNodeWithText("Laura").assertIsDisplayed()
        compose.onNodeWithText("1944  ·  Thriller").assertIsDisplayed()
        compose.onNodeWithText("Picked for You").assertIsDisplayed()
        compose.onNodeWithText("PICKED FOR YOU").assertDoesNotExist()
        compose.onNodeWithTag("watch-suggestion-header").assertDoesNotExist()
        compose.onNodeWithText("WHY").assertIsDisplayed()
        compose.onNodeWithTag("watch-suggestion-why").assertTextEquals("Edge-of-your-seat.")
        compose.onNodeWithTag("watch-suggestion-facts").assertTextEquals("On your watchlist  ·  Not watched yet  ·  Streaming on Netflix")
        compose.onNodeWithText("What should I watch?").assertDoesNotExist()
        compose.onNodeWithTag("watch-suggestion-open").performClick()
        compose.onNodeWithTag("watch-suggestion-wtw").performClick()
        compose.onNodeWithTag("watch-suggestion-spin").performClick()
        assertEquals(1, open)
        assertEquals(1, wtw)
        assertEquals(1, spin)
    }

    @Test
    fun buttonHiddenWhenUnavailable() {
        compose.setContent {
            MrtTheme {
                PickForMeButton(visible = false, notReady = false, onClick = {})
            }
        }
        compose.onNodeWithTag("what-should-i-watch").assertDoesNotExist()
    }

    @Test
    fun buttonVisibleWhenAvailable() {
        compose.setContent {
            MrtTheme {
                PickForMeButton(visible = true, notReady = false, onClick = {})
            }
        }
        compose.onNodeWithTag("what-should-i-watch").assertIsDisplayed()
        compose.onNodeWithText("Pick for Me").assertIsDisplayed()
        compose.onNodeWithTag("pick-for-me-subtitle").assertTextEquals("Not random: one movie chosen for you, and why it fits.")
        compose.onNodeWithTag("what-should-i-watch")
            .assertContentDescriptionEquals("Pick for Me. Picks one movie for you on purpose and explains why. Not random.")
        compose.onNodeWithText("What should I watch?").assertDoesNotExist()
    }

    @Test
    fun noModelPickShowsTheFallbackLineAndFactsOnly() {
        val reason = PickReason(null, listOf(Fact.OnWatchlist, Fact.NotWatched, Fact.Free("Tubi")), fromModel = false)
        compose.setContent {
            MrtTheme { WatchSuggestionCard(movie, reason, {}, {}, {}, {}) }
        }
        compose.onNodeWithTag("watch-suggestion-why").assertTextEquals("Picked from your watchlist and services.")
        compose.onNodeWithTag("watch-suggestion-facts").assertTextEquals("On your watchlist  ·  Not watched yet  ·  Free on Tubi")
    }

    @Test
    fun loadingSaysWhatItIsDoingAndCanBeCancelled() {
        var cancel = 0
        var spin = 0
        compose.setContent {
            MrtTheme { SuggestionLoadingCard(onCancel = { cancel++ }, onSpinInstead = { spin++ }) }
        }
        compose.onNodeWithText("Picking from your watchlist and services…").assertIsDisplayed()
        compose.onNodeWithTag("watch-suggestion-cancel").performClick()
        compose.onNodeWithTag("watch-suggestion-loading-spin").performClick()
        assertEquals(1, cancel)
        assertEquals(1, spin)
    }

    @Test
    fun buttonSaysNotReadyWhenModelIsDownloadable() {
        compose.setContent {
            MrtTheme {
                PickForMeButton(visible = true, notReady = true, onClick = {})
            }
        }
        compose.onNodeWithTag("what-should-i-watch").assertIsDisplayed()
        compose.onNodeWithTag("what-should-i-watch").assertIsNotEnabled()
        compose.onNodeWithText("Suggestions aren’t ready yet").assertIsDisplayed()
    }

    @Test
    fun unavailableCardOffersSpinAndDoesNotSpinOnItsOwn() {
        var spin = 0
        compose.setContent {
            MrtTheme {
                SuggestionUnavailableCard(
                    message = ActionSpeechMsg,
                    onSpin = { spin++ },
                    onDismiss = {},
                )
            }
        }
        compose.onNodeWithTag("watch-suggestion-unavailable").assertIsDisplayed()
        assertEquals(0, spin)
        compose.onNodeWithTag("watch-suggestion-offer-spin").performClick()
        assertEquals(1, spin)
    }
}

private const val ActionSpeechMsg = "Suggestions aren’t available on this device. You can spin the wheel instead."
