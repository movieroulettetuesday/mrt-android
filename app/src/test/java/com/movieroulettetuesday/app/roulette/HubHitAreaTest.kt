package com.movieroulettetuesday.app.roulette

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.movieroulettetuesday.app.ui.RouletteLayout
import com.movieroulettetuesday.app.ui.theme.MrtTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The hub Spin/Stop hit area stays at least 48 dp when the wheel is at its minimum size. */
@RunWith(AndroidJUnit4::class)
class HubHitAreaTest {
    @get:Rule val compose = createComposeRule()

    @Test
    @Config(qualifiers = "w120dp-h200dp")
    fun hubHitAreaIsAtLeast48DpAtMinimumWheelSize() {
        val catalog = RouletteFixtures.catalog
        val wheel = RouletteLayout.MINIMUM_DIAMETER.dp
        compose.setContent {
            MrtTheme {
                Box(Modifier.size(wheel), contentAlignment = Alignment.Center) {
                    RouletteWheel(
                        categories = catalog.categories,
                        geometry = WheelGeometry.forCatalog(catalog),
                        rotation = 0.0,
                        spin = null,
                        canSpin = true,
                        onSpin = {},
                        onSpinFinished = {},
                    )
                }
            }
        }
        compose.waitForIdle()
        val hub = compose.onNodeWithTag("spin-hub").getBoundsInRoot()
        val width = hub.right - hub.left
        val height = hub.bottom - hub.top
        assertTrue("hub width $width at ${wheel.value} dp wheel", width >= 48.dp)
        assertTrue("hub height $height at ${wheel.value} dp wheel", height >= 48.dp)
        // Drawn disc is unchanged at 88 dp, so the hit frame matches it and is already ≥ 48 dp.
        assertEquals(88f, width.value, 0.6f)
        assertEquals(88f, height.value, 0.6f)
    }
}
