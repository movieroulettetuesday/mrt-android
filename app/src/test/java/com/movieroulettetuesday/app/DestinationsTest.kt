package com.movieroulettetuesday.app

import com.movieroulettetuesday.app.ui.TopLevelDestination
import org.junit.Assert.assertEquals
import org.junit.Test

class DestinationsTest {
    @Test
    fun tabsMatchIosOrder() {
        assertEquals(listOf("roulette", "episodes", "movies", "stats"), TopLevelDestination.entries.map { it.route })
    }
}
