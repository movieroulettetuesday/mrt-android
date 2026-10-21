package com.movieroulettetuesday.app.roulette

import kotlin.math.floor
import kotlin.random.Random

/**
 * Pure wheel geometry, ported from RouletteCore/WheelMath.swift.
 * - Angles are degrees clockwise from 12 o'clock; segment 0 starts at 12 o'clock.
 * - The wheel rotates clockwise by `rotation` degrees; the pointer is fixed at 12 o'clock.
 * - A pointer exactly on a boundary belongs to the segment that starts there.
 */
object WheelMath {
    const val FULL_TURN = 360.0

    /** Wheel-local angle in [0, 360) under the pointer after rotating clockwise by [degrees]. */
    fun pointerAngle(degrees: Double): Double {
        if (!degrees.isFinite()) return 0.0
        var angle = (-degrees) % FULL_TURN // Kotlin % == fmod (exact)
        if (angle < 0) angle += FULL_TURN
        if (angle >= FULL_TURN) angle -= FULL_TURN
        return if (angle == 0.0) 0.0 else angle // normalizes -0.0
    }

    fun segmentIndex(degrees: Double, segmentCount: Int): Int {
        if (segmentCount <= 0 || !degrees.isFinite()) return 0
        val sweep = FULL_TURN / segmentCount
        val raw = floor(pointerAngle(degrees) / sweep).toInt()
        return raw.coerceIn(0, segmentCount - 1)
    }

    fun targetRotation(current: Double, fullTurns: Int, extraDegrees: Double): Double =
        current + fullTurns * FULL_TURN + extraDegrees
}

/** Segment layout for slices that may have different sizes (`RouletteCategory.weight`). */
class WheelGeometry private constructor(val segments: List<Segment>, val isUniform: Boolean) {
    data class Segment(val index: Int, val startAngle: Double, val endAngle: Double) {
        val sweep: Double get() = endAngle - startAngle
        val midAngle: Double get() = startAngle + sweep / 2
    }

    val count: Int get() = segments.size

    fun segmentIndex(rotation: Double): Int {
        if (isUniform) return WheelMath.segmentIndex(rotation, segments.size)
        if (!rotation.isFinite()) return 0
        val angle = WheelMath.pointerAngle(rotation)
        return segments.lastOrNull { angle >= it.startAngle }?.index ?: 0
    }

    /**
     * Random clockwise target: [current] + whole turns + a uniform extra angle, then nudged (inside
     * the same segment, so odds are unchanged) to stay [edgePadding] degrees off a boundary.
     */
    fun randomTargetRotation(
        current: Double,
        random: Random,
        fullTurns: IntRange = 5..8,
        edgePadding: Double = 3.0,
    ): Double {
        val turns = random.nextInt(fullTurns.first, fullTurns.last + 1)
        val extra = random.nextDouble(0.0, WheelMath.FULL_TURN)
        var target = WheelMath.targetRotation(current, turns, extra)
        val angle = WheelMath.pointerAngle(target)
        val segment = segments.getOrNull(segmentIndex(target)) ?: return target
        val padding = edgePadding.coerceAtLeast(0.0).coerceAtMost(segment.sweep / 4)
        val clamped = angle.coerceIn(segment.startAngle + padding, segment.endAngle - padding)
        // The pointer angle grows as rotation shrinks, so subtract the correction.
        target -= clamped - angle
        return target
    }

    companion object {
        /** null, zero, negative or non-finite weights count as 1. Empty -> one segment. */
        fun fromWeights(weights: List<Double?>): WheelGeometry {
            val input = weights.ifEmpty { listOf(1.0) }
            val sanitized = input.map { w -> if (w != null && w.isFinite() && w > 0) w else 1.0 }
            val uniform = sanitized.toSet().size == 1
            val total = sanitized.sum()
            var cumulative = 0.0
            val segments = sanitized.mapIndexed { index, weight ->
                val last = index == sanitized.size - 1
                if (uniform) {
                    val sweep = WheelMath.FULL_TURN / sanitized.size
                    Segment(index, index * sweep, if (last) WheelMath.FULL_TURN else (index + 1) * sweep)
                } else {
                    val start = WheelMath.FULL_TURN * cumulative / total
                    cumulative += weight
                    Segment(index, start, if (last) WheelMath.FULL_TURN else WheelMath.FULL_TURN * cumulative / total)
                }
            }
            return WheelGeometry(segments, uniform)
        }

        fun uniform(segmentCount: Int): WheelGeometry = fromWeights(List(segmentCount.coerceAtLeast(0)) { 1.0 })
    }
}
