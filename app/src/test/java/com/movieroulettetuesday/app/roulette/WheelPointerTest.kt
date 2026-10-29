package com.movieroulettetuesday.app.roulette

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.movieroulettetuesday.app.catalog.CatalogRepository
import com.movieroulettetuesday.app.ui.LocalWindowLayout
import com.movieroulettetuesday.app.ui.RouletteLayout
import com.movieroulettetuesday.app.ui.WindowLayout
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
import org.robolectric.annotation.Config

/** The drawn pointer is iOS `RouletteLayout.pointerSize(diameter)` for the wheel it sits on (SPEC §11). */
@RunWith(AndroidJUnit4::class)
class WheelPointerTest {
    @get:Rule val compose = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After fun tearDown() = scope.cancel()

    @Test fun helperMatchesLayoutMath() {
        for (d in listOf(120f, 329.4f, 391f, 560f, 700f, 860f)) {
            val (w, h) = RouletteLayout.pointerSize(d.toDouble())
            assertEquals(w.toFloat() to h.toFloat(), WheelPointer.size(d))
        }
        assertEquals(28f, WheelPointer.size(120f).first, 0f) // floor
        assertEquals(56f, WheelPointer.size(860f).first, 0f) // cap
    }

    private fun assertPointerMatchesWheel(window: WindowLayout): Float {
        val vm = RouletteViewModel(MutableStateFlow(CatalogRepository.State.Loaded(RouletteFixtures.catalog)), SpinHistoryStore(null), scope)
        compose.setContent {
            CompositionLocalProvider(LocalWindowLayout provides window) { MrtTheme { RouletteScreen(vm) } }
        }
        compose.waitForIdle()
        val wheel = compose.onNodeWithTag("wheel", useUnmergedTree = true).getBoundsInRoot()
        val pointer = compose.onNodeWithTag("wheel-pointer", useUnmergedTree = true).getBoundsInRoot()
        val diameter = wheel.right - wheel.left
        val (w, h) = RouletteLayout.pointerSize(diameter.value.toDouble())
        assertEquals("pointer width for d=$diameter", w.toFloat(), (pointer.right - pointer.left).value, 0.6f)
        assertEquals("pointer height for d=$diameter", h.toFloat(), (pointer.bottom - pointer.top).value, 0.6f)
        // Straddles the rim: half above the wheel, centred on it.
        assertEquals((wheel.top - (h / 2).toFloat().let { androidx.compose.ui.unit.Dp(it) }).value, pointer.top.value, 0.6f)
        assertEquals(((wheel.left + wheel.right) / 2).value, ((pointer.left + pointer.right) / 2).value, 0.6f)
        return diameter.value
    }

    @Test @Config(qualifiers = "w411dp-h891dp")
    fun phonePointerScalesWithWheel() {
        val d = assertPointerMatchesWheel(WindowLayout(411f, 891f))
        assertTrue("phone wheel d=$d", d in 300f..400f)
    }

    @Test @Config(qualifiers = "w1280dp-h800dp")
    fun tabletPointerIsLargerOnTheLargerWheel() {
        val d = assertPointerMatchesWheel(WindowLayout(1280f, 800f))
        assertTrue("tablet wheel d=$d", d > 420f)
        assertTrue(WheelPointer.size(d).first > WheelPointer.size(391f).first)
    }
}
