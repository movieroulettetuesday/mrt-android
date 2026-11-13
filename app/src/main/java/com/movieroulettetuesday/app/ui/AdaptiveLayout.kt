package com.movieroulettetuesday.app.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.window.core.layout.WindowSizeClass
import androidx.window.core.layout.computeWindowSizeClass

/** Window width class (androidx.window `WindowSizeClass`, breakpoints 600 / 840 dp). */
enum class WidthClass { Compact, Medium, Expanded }

/**
 * The window the app is laid out in. `isRegularWidth` is iOS's horizontal size class `.regular`
 * (SPEC §11: Android maps regular width to width class ≥ Medium). Like iOS, layouts use this and
 * the available size, never the device type, so a narrow multi-window tablet gets the phone UI.
 */
data class WindowLayout(val widthDp: Float, val heightDp: Float) {
    val sizeClass: WindowSizeClass = WindowSizeClass.BREAKPOINTS_V1.computeWindowSizeClass(widthDp, heightDp)
    val widthClass: WidthClass = when {
        sizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND) -> WidthClass.Expanded
        sizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND) -> WidthClass.Medium
        else -> WidthClass.Compact
    }
    val isRegularWidth: Boolean get() = widthClass != WidthClass.Compact

    companion object {
        val Phone = WindowLayout(411f, 891f)
    }
}

/** Set by `MrtApp` from the window size; screens used on their own (tests, previews) get a phone. */
val LocalWindowLayout = staticCompositionLocalOf { WindowLayout.Phone }

/** Port of iOS `RouletteLayout` (RouletteCore/AdaptiveLayout.swift); vectors `wheel.json#layout.roulette`. */
object RouletteLayout {
    enum class Arrangement { Stacked, SideBySide }

    const val COMPACT_SIDE_MARGIN = 10.0
    const val HINT_SPACING = 14.0
    const val COMPACT_CAP = 560.0
    const val REGULAR_STACKED_CAP = 820.0
    const val SIDE_BY_SIDE_CAP = 860.0
    const val MINIMUM_DIAMETER = 120.0
    /** Fraction of the wheel diameter used for the drawn hub disc (iOS `hubDiameterFraction`). */
    const val HUB_DIAMETER_FRACTION = 0.23
    /**
     * Minimum hit area for the hub Spin / Stop control (Material 48 dp; iOS uses 44 pt).
     * The drawn disc may shrink with the wheel; only the tappable frame is expanded when
     * `diameter × HUB_DIAMETER_FRACTION` would drop below 48 dp (below about 209 dp,
     * including [MINIMUM_DIAMETER] 120).
     */
    const val MINIMUM_HUB_HIT_SIZE = 48.0
    const val SHADOW_ALLOWANCE = 24.0
    const val TOP_GAP = 14.0
    /** Height of the "Tap to spin" line under the wheel (iOS reserves 40 pt). */
    const val HINT_HEIGHT = 40.0

    fun arrangement(width: Double, height: Double, isRegularWidth: Boolean): Arrangement {
        val w = sane(width); val h = sane(height)
        val landscapeEnough = w >= 700 && w > h * 1.15
        return if (isRegularWidth && landscapeEnough) Arrangement.SideBySide else Arrangement.Stacked
    }

    fun sideBySideControlsWidth(width: Double): Double = minOf(360.0, maxOf(240.0, sane(width) * 0.3))

    fun pointerSize(diameter: Double): Pair<Double, Double> {
        val w = minOf(maxOf(sane(diameter) * 0.085, 28.0), 56.0)
        return w to w * 34 / 28
    }

    /** Drawn hub disc diameter in dp (visuals only). */
    fun hubVisualDiameter(diameter: Double): Double = sane(diameter) * HUB_DIAMETER_FRACTION

    /**
     * Tappable width and height of the hub Spin / Stop control, at least
     * [MINIMUM_HUB_HIT_SIZE]. At [MINIMUM_DIAMETER] this is 48, not 27.6.
     */
    fun hubHitSize(diameter: Double): Double = maxOf(MINIMUM_HUB_HIT_SIZE, hubVisualDiameter(diameter))

    fun wheelDiameter(slotWidth: Double, slotHeight: Double, arrangement: Arrangement, isRegularWidth: Boolean, hintHeight: Double = 0.0): Double {
        val hint = sane(hintHeight)
        val w = sane(slotWidth)
        val h = sane(slotHeight) - (if (hint > 0) hint + HINT_SPACING else 0.0)
        var d = when {
            arrangement == Arrangement.SideBySide -> minOf(w, SIDE_BY_SIDE_CAP)
            isRegularWidth -> minOf(w * 0.84, REGULAR_STACKED_CAP)
            else -> minOf(w - 2 * COMPACT_SIDE_MARGIN, COMPACT_CAP)
        }
        repeat(3) { d = minOf(d, h - pointerSize(d).second / 2 - SHADOW_ALLOWANCE - TOP_GAP) }
        if (!d.isFinite()) return MINIMUM_DIAMETER
        return maxOf(MINIMUM_DIAMETER, d)
    }

    private fun sane(v: Double) = if (v.isFinite()) maxOf(0.0, v) else 0.0
}

/** iOS `StatsGridLayout`: card columns on the Stats tab (regular width only). */
object StatsGridLayout {
    fun columnCount(width: Double, isRegularWidth: Boolean): Int = when {
        !isRegularWidth || !width.isFinite() -> 1
        width >= 1_100 -> 3
        width >= 640 -> 2
        else -> 1
    }
}

/** iOS `navigationSplitViewColumnWidth(min: 320, ideal: 380, max: 460)` for the list pane. */
object ListDetailLayout {
    fun listWidth(totalWidth: Double): Double = (totalWidth * 0.38).coerceIn(320.0, 460.0)
}
