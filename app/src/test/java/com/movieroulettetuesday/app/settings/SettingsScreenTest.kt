package com.movieroulettetuesday.app.settings

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertTextContains
import androidx.test.core.app.ApplicationProvider
import com.movieroulettetuesday.app.catalog.StreamingInfo
import com.movieroulettetuesday.app.movies.MyServices
import com.movieroulettetuesday.app.ui.theme.MrtTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class SettingsScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun togglesAndLinks() {
        var suggested = 0
        var services = 0
        compose.setContent {
            var state by remember { mutableStateOf(SettingsState(AppearancePreference.System, haptics = true, newEpisodeAlerts = false)) }
            MrtTheme {
                SettingsScreen(state, SettingsActions(
                    setAppearance = { state = state.copy(appearance = it) },
                    setHaptics = { state = state.copy(haptics = it) },
                    setNewEpisodeAlerts = { state = state.copy(newEpisodeAlerts = it) },
                    setAutoShareRatings = {},
                    openMyServices = { services++ },
                    openCredits = {},
                    suggest = { suggested++ },
                    close = {},
                ))
            }
        }
        compose.onNodeWithTag("appearance-system").assertIsSelected()
        compose.onNodeWithTag("appearance-dark").performClick().assertIsSelected()
        compose.onNodeWithTag("appearance-system").assertIsNotSelected()
        compose.onNodeWithTag("settings-haptics").assertIsOn().performClick().assertIsOff()
        compose.onNodeWithTag("settings-auto-share").assertDoesNotExist() // community not configured
        compose.onNodeWithTag("settings-my-services").performScrollTo().performClick()
        assertEquals(1, services)
        compose.onNodeWithTag("follow-youtube").performScrollTo().performClick()
        val app = ApplicationProvider.getApplicationContext<Application>()
        assertEquals("https://www.youtube.com/@theMrtPodcast", shadowOf(app).nextStartedActivity.dataString)
        compose.onNodeWithTag("settings-suggest").performScrollTo().performClick()
        assertEquals(1, suggested)
        compose.onNodeWithTag("privacy-note").performScrollTo().assertTextContains("collects no personal data", substring = true)
    }

    @Test
    fun myServicesPickerToggles() {
        val providers = listOf(StreamingInfo.Provider(8, "Netflix"), StreamingInfo.Provider(15, "Hulu"))
        compose.setContent {
            var mine by remember { mutableStateOf(MyServices()) }
            MrtTheme { MyServicesScreen(providers, mine, { mine = mine.toggled(it) }, {}) }
        }
        compose.onNodeWithTag("service-8").assertIsNotSelected().performClick().assertIsSelected()
        compose.onNodeWithTag("service-15").assertIsNotSelected()
    }

    @Test
    fun creditsShowTmdbNoticeAndVersion() {
        compose.setContent { MrtTheme { CreditsScreen("1.0 (1)") {} } }
        compose.onNodeWithTag("tmdb-notice").assertTextContains("TMDB", substring = true)
        compose.onNodeWithTag("about-version").assertTextContains("Version 1.0 (1)")
    }
}
