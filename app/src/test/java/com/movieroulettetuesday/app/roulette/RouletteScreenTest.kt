package com.movieroulettetuesday.app.roulette

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.movieroulettetuesday.app.catalog.CatalogRepository
import com.movieroulettetuesday.app.ui.theme.MrtTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/** Compose UI tests for the Roulette tab, on the JVM under Robolectric. */
@RunWith(AndroidJUnit4::class)
class RouletteScreenTest {
    @get:Rule val compose = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val history = SpinHistoryStore(null)
    private val vm = RouletteViewModel(
        catalogState = MutableStateFlow(CatalogRepository.State.Loaded(RouletteFixtures.catalog)),
        history = history,
        scope = scope,
        random = SeededRandom(11),
        rarePolicy = RareOutcomePolicy(isEnabled = false),
    )

    @After fun tearDown() = scope.cancel()

    private fun spinToReveal() {
        compose.setContent { MrtTheme { RouletteScreen(vm) } }
        compose.onNodeWithTag("roulette-status").assertTextEquals("Tap to spin")
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("spin-hub").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("roulette-status").assertTextEquals("Spinning…")
        compose.mainClock.advanceTimeBy(SpinMotion.spinDurationMs.toLong() + 500)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
    }

    @Test fun spinAnimatesThenRevealsCategoryThenMovie() {
        spinToReveal()
        val reveal = vm.state.value.reveal!!
        compose.onNodeWithTag("reveal-category").assertTextEquals(reveal.category.displayName)
        compose.onNodeWithTag("reveal-title").assertTextEquals(reveal.movie.title)
        compose.onNodeWithTag("reveal-sheet-title").assertTextEquals("Tonight’s Movie")
        assertTrue(reveal.movie.id in reveal.category.movieIds)
        assertEquals(1, history.totalSpins)
    }

    /** Only the center control stops a spin (answers-from-ios.md E12); it reads STOP while spinning. */
    @Test fun hubStopsASpinEarly() {
        compose.setContent { MrtTheme { RouletteScreen(vm) } }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("spin-hub").performClick()
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithText("STOP").assertIsDisplayed()
        compose.onNodeWithTag("spin-hub").performClick()
        compose.mainClock.advanceTimeBy(100)
        assertTrue(vm.state.value.spin?.stop != null)
        compose.mainClock.advanceTimeBy(2_000)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(1, history.totalSpins)
    }

    @Test fun shareSendsTextIntent() {
        spinToReveal()
        val movie = vm.state.value.reveal!!.movie
        compose.onNodeWithTag("reveal-share").performClick()
        val started = shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>()).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, started.action)
        @Suppress("DEPRECATION")
        val send = started.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("text/plain", send.type)
        assertEquals(SpinShare.message(movie), send.getStringExtra(Intent.EXTRA_TEXT))
        assertEquals(movie.title, send.getStringExtra(Intent.EXTRA_SUBJECT))
    }

    @Test fun doneClosesRevealAndShowsLandedCategory() {
        spinToReveal()
        val landed = vm.state.value.reveal!!.category
        compose.onNodeWithTag("reveal-done").performClick()
        compose.waitForIdle()
        assertEquals(null, vm.state.value.reveal)
        compose.onNodeWithTag("roulette-status").assertTextEquals("Landed on ${landed.displayName}")
    }
}
