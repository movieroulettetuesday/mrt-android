package com.movieroulettetuesday.app.suggest

import android.app.Application
import android.content.ClipboardManager
import android.content.Intent
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.movieroulettetuesday.app.catalog.CatalogJson
import com.movieroulettetuesday.app.ui.theme.MrtTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class SuggestMovieSheetTest {
    @get:Rule val compose = createComposeRule()
    private val catalog = CatalogJson.decode(File("src/main/assets/movies.json").readBytes())
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    private fun fill() {
        compose.onNodeWithTag("suggest-email").assertIsNotEnabled()
        compose.onNodeWithTag("suggest-title").performTextInput("Tremors (1990)")
        compose.onNodeWithTag("suggest-category").performClick()
        compose.onNodeWithTag("suggest-category-cat-horror").performClick()
        compose.onNodeWithTag("suggest-why").performTextInput("Graboids")
        compose.onNodeWithTag("suggest-credit").performTextInput("Laura from Ohio")
    }

    @Test
    fun emailIncludesCreditName() {
        var dismissed = false
        compose.setContent { MrtTheme { SuggestMovieSheet(catalog.categories, { dismissed = true }) } }
        fill()
        compose.onNodeWithTag("suggest-send").assertDoesNotExist() // no relay in this build
        compose.onNodeWithTag("suggest-email").performScrollTo().assertIsEnabled().performClick()
        val intent = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_SENDTO, intent.action)
        assertTrue(intent.dataString!!.startsWith("mailto:theMrtPodcast@gmail.com?subject=Movie%20suggestion:%20Tremors"))
        assertEquals("Movie suggestion: Tremors (1990)", intent.getStringExtra(Intent.EXTRA_SUBJECT))
        val body = intent.getStringExtra(Intent.EXTRA_TEXT)!!
        assertTrue(body, body.contains("Why: Graboids\nFrom: Laura from Ohio"))
        assertTrue(body, body.contains("Category: Horror"))
        compose.waitForIdle()
        assertTrue(dismissed)
    }

    @Test
    fun noMailAppCopiesAddress() {
        shadowOf(app).checkActivities(true)
        compose.setContent { MrtTheme { SuggestMovieSheet(catalog.categories, {}) } }
        fill()
        compose.onNodeWithTag("suggest-email").performScrollTo().performClick()
        compose.onNodeWithText("Email address copied").assertExists()
        val clip = app.getSystemService(ClipboardManager::class.java).primaryClip!!
        assertEquals("theMrtPodcast@gmail.com", clip.getItemAt(0).text.toString())
    }

    @Test
    fun inAppSendHonorsRateLimit() {
        var sent: InAppSuggestion? = null
        compose.setContent {
            MrtTheme {
                SuggestMovieSheet(catalog.categories, {}, sendInApp = { sent = it; null },
                    rateLimit = { SuggestionRateLimit(lastSent = 0) }, now = { 60_000 })
            }
        }
        fill()
        compose.onNodeWithTag("suggest-send").performScrollTo().performClick()
        compose.onNodeWithTag("suggest-error").assertExists()
        compose.onNodeWithText("You just sent one. Try again in 9 min.").assertExists()
        assertEquals(null, sent)
    }
}
