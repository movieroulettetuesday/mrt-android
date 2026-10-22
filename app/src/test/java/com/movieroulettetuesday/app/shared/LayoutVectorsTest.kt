package com.movieroulettetuesday.app.shared

import com.movieroulettetuesday.app.ui.ListDetailLayout
import com.movieroulettetuesday.app.ui.RouletteLayout
import com.movieroulettetuesday.app.ui.StatsGridLayout
import com.movieroulettetuesday.app.ui.WidthClass
import com.movieroulettetuesday.app.ui.WindowLayout
import org.junit.Assert.assertEquals
import org.junit.Test

/** wheel.json#layout (iOS RouletteCore/AdaptiveLayout.swift) and the Android width classes. */
class LayoutVectorsTest {
    private val layout = SharedVectors.load("wheel.json").obj("layout")

    @Test fun rouletteLayoutMatchesIos() {
        val cases = layout.arr("roulette").objs()
        assertEquals(6, cases.size)
        for (c in cases) {
            val w = c.dbl("width")!!; val h = c.dbl("height")!!; val regular = c.bool("isRegularWidth")!!
            val tag = "${w}x$h regular=$regular"
            val arrangement = RouletteLayout.arrangement(w, h, regular)
            assertEquals(tag, c.str("arrangement"), if (arrangement == RouletteLayout.Arrangement.SideBySide) "sideBySide" else "stacked")
            assertEquals(tag, c.dbl("controlsWidth")!!, RouletteLayout.sideBySideControlsWidth(w), 1e-9)
            val d = RouletteLayout.wheelDiameter(c.dbl("slotWidthUsed")!!, h, arrangement, regular, c.dbl("hintHeight")!!)
            assertEquals(tag, c.dbl("wheelDiameter")!!, d, 1e-6)
            val pointer = c["pointerSize"]!!.doubles()
            val (pw, ph) = RouletteLayout.pointerSize(d)
            assertEquals(tag, pointer[0], pw, 1e-6)
            assertEquals(tag, pointer[1], ph, 1e-6)
        }
    }

    @Test fun statsColumnsMatchIos() {
        for (c in layout.arr("statsColumns").objs()) {
            assertEquals(c.toString(), c.int("columns"), StatsGridLayout.columnCount(c.dbl("width")!!, c.bool("isRegularWidth")!!))
        }
    }

    @Test fun widthClassesUseWindowSizeClassBreakpoints() {
        assertEquals(WidthClass.Compact, WindowLayout(360f, 800f).widthClass)
        assertEquals(WidthClass.Compact, WindowLayout(599f, 900f).widthClass)
        assertEquals(WidthClass.Medium, WindowLayout(600f, 960f).widthClass) // 7" portrait
        assertEquals(WidthClass.Medium, WindowLayout(800f, 1280f).widthClass) // 10" portrait
        assertEquals(WidthClass.Expanded, WindowLayout(840f, 600f).widthClass)
        assertEquals(WidthClass.Expanded, WindowLayout(1280f, 800f).widthClass) // 10" landscape
        assertEquals(false, WindowLayout(411f, 891f).isRegularWidth)
        assertEquals(true, WindowLayout(600f, 960f).isRegularWidth)
    }

    @Test fun listPaneWidthFollowsSplitViewColumn() {
        assertEquals(320.0, ListDetailLayout.listWidth(600.0), 0.0)
        assertEquals(380.0, ListDetailLayout.listWidth(1000.0), 1e-9)
        assertEquals(460.0, ListDetailLayout.listWidth(2000.0), 0.0)
    }
}
