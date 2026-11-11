package com.movieroulettetuesday.app.suggestions

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
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
        var open = 0
        var wtw = 0
        var spin = 0
        compose.setContent {
            MrtTheme {
                WatchSuggestionCard(
                    movie, "It’s on your watchlist.",
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
        compose.onNodeWithText("It’s on your watchlist.").assertIsDisplayed()
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
                WhatShouldIWatchButton(visible = false, notReady = false, onClick = {})
            }
        }
        compose.onNodeWithTag("what-should-i-watch").assertDoesNotExist()
    }

    @Test
    fun buttonVisibleWhenAvailable() {
        compose.setContent {
            MrtTheme {
                WhatShouldIWatchButton(visible = true, notReady = false, onClick = {})
            }
        }
        compose.onNodeWithTag("what-should-i-watch").assertIsDisplayed()
        compose.onNodeWithText("What should I watch?").assertIsDisplayed()
    }

    @Test
    fun buttonSaysNotReadyWhenModelIsDownloadable() {
        compose.setContent {
            MrtTheme {
                WhatShouldIWatchButton(visible = true, notReady = true, onClick = {})
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
