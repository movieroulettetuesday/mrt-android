package com.movieroulettetuesday.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** iOS `AdaptiveLayoutTests.testHubHitAreaIsAtLeast44AtMinimumDiameter`, with Material 48 dp. */
class RouletteLayoutTest {
    @Test fun hubHitAreaIsAtLeast48AtMinimumDiameter() {
        val minD = RouletteLayout.MINIMUM_DIAMETER
        assertEquals(120.0, minD, 0.0)
        val visual = RouletteLayout.hubVisualDiameter(minD)
        assertEquals(minD * RouletteLayout.HUB_DIAMETER_FRACTION, visual, 0.001)
        assertEquals(27.6, visual, 0.001)
        assertTrue("the visual disc is allowed to shrink; the hit area is not", visual < 48.0)
        val hit = RouletteLayout.hubHitSize(minD)
        assertEquals(48.0, hit, 0.001)
        assertTrue(hit >= RouletteLayout.MINIMUM_HUB_HIT_SIZE)

        // Just under the 48 dp visual threshold (~208.7 dp wheel): still clamped.
        val under = 48.0 / RouletteLayout.HUB_DIAMETER_FRACTION - 1
        assertTrue(RouletteLayout.hubVisualDiameter(under) < 48.0)
        assertEquals(48.0, RouletteLayout.hubHitSize(under), 0.001)

        // At and above the threshold the hit area matches the visual disc.
        val at = 48.0 / RouletteLayout.HUB_DIAMETER_FRACTION
        assertEquals(48.0, RouletteLayout.hubVisualDiameter(at), 0.001)
        assertEquals(48.0, RouletteLayout.hubHitSize(at), 0.001)
        val comfortable = 382.0 // typical phone portrait wheel neighborhood
        assertEquals(
            RouletteLayout.hubVisualDiameter(comfortable),
            RouletteLayout.hubHitSize(comfortable),
            0.001,
        )
        assertTrue(RouletteLayout.hubHitSize(comfortable) > 48.0)

        // Degenerate diameters don't go below the minimum hit size.
        for (bad in listOf(Double.NaN, -40.0, 0.0, Double.POSITIVE_INFINITY)) {
            assertEquals(48.0, RouletteLayout.hubHitSize(bad), 0.001)
            assertFalse(bad.isFinite() && bad > 0 && RouletteLayout.hubVisualDiameter(bad) >= 48.0)
        }
    }
}
