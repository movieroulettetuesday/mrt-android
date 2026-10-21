package com.movieroulettetuesday.app.roulette

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WheelMathTest {
    @Test fun zeroRotationLandsOnSegmentZero() {
        assertEquals(0, WheelMath.segmentIndex(0.0, 10))
    }

    @Test fun clockwiseRotationBringsEarlierSegmentsUnderPointer() {
        // Rotating clockwise by 36° (one slice of 10) puts the last slice under the pointer.
        assertEquals(9, WheelMath.segmentIndex(1.0, 10))
        assertEquals(9, WheelMath.segmentIndex(36.0, 10))
        assertEquals(8, WheelMath.segmentIndex(36.5, 10))
    }

    @Test fun boundaryBelongsToSegmentStartingThere() {
        assertEquals(1, WheelMath.segmentIndex(-36.0, 10))
        assertEquals(WheelMath.segmentIndex(-36.0, 10), WheelMath.segmentIndex(324.0, 10))
    }

    @Test fun fullTurnsAndHugeOrBadAngles() {
        for (r in listOf(12.3, 100.0, 359.9)) {
            assertEquals(WheelMath.segmentIndex(r, 7), WheelMath.segmentIndex(r + 360 * 1000, 7))
            assertEquals(WheelMath.segmentIndex(r, 7), WheelMath.segmentIndex(r - 360 * 3, 7))
        }
        assertEquals(0.0, WheelMath.pointerAngle(Double.NaN), 0.0)
        assertEquals(0, WheelMath.segmentIndex(Double.POSITIVE_INFINITY, 5))
        assertEquals(0, WheelMath.segmentIndex(10.0, 0))
        for (r in listOf(-1e9, -0.0, 1e-12, 1e12)) {
            val a = WheelMath.pointerAngle(r)
            assertTrue("$r -> $a", a >= 0 && a < 360)
        }
    }

    @Test fun everySegmentIsReachable() {
        for (n in 1..12) {
            val g = WheelGeometry.uniform(n)
            val seen = (0 until 3600).map { g.segmentIndex(it / 10.0) }.toSet()
            assertEquals((0 until n).toSet(), seen)
        }
    }

    @Test fun weightedSegmentsAreProportionalAndInvalidWeightsCountAsOne() {
        val g = WheelGeometry.fromWeights(listOf(1.0, 2.0, 1.0))
        assertEquals(90.0, g.segments[0].sweep, 1e-9)
        assertEquals(180.0, g.segments[1].sweep, 1e-9)
        assertEquals(360.0, g.segments.last().endAngle, 0.0)
        val bad = WheelGeometry.fromWeights(listOf(null, -1.0, Double.NaN, 0.0))
        assertTrue(bad.isUniform)
        assertEquals(90.0, bad.segments[2].sweep, 1e-9)
        assertEquals(1, WheelGeometry.fromWeights(emptyList()).count)
        // Non-uniform lookup agrees with drawn layout: pointer angle 100 is in segment 1 (90..270).
        assertEquals(1, g.segmentIndex(-100.0))
    }

    @Test fun randomTargetSpinsForwardSeveralTurnsAndAvoidsEdges() {
        val g = WheelGeometry.uniform(10)
        val rnd = SeededRandom(7)
        var current = 0.0
        repeat(500) {
            val t = g.randomTargetRotation(current, rnd)
            assertTrue(t - current >= 5 * 360 - 3)
            assertTrue(t - current <= 9 * 360)
            val a = WheelMath.pointerAngle(t)
            val seg = g.segments[g.segmentIndex(t)]
            assertTrue(a >= seg.startAngle + 3 - 1e-9 && a <= seg.endAngle - 3 + 1e-9)
            current = t
        }
    }

    @Test fun randomTargetIsDeterministicAndFair() {
        val g = WheelGeometry.uniform(10)
        val a = SeededRandom(42)
        val b = SeededRandom(42)
        assertEquals(g.randomTargetRotation(0.0, a), g.randomTargetRotation(0.0, b), 0.0)
        val counts = IntArray(10)
        val rnd = SeededRandom(1)
        repeat(20_000) { counts[g.segmentIndex(g.randomTargetRotation(0.0, rnd))]++ }
        counts.forEach { assertTrue("$it", it in 1700..2300) }
    }
}
