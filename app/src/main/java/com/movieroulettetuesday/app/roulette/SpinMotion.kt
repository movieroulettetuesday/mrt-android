package com.movieroulettetuesday.app.roulette

import com.movieroulettetuesday.app.shared.SharedSpinTrack
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.random.Random

/** The recorded spin sound and its measured timing (mrt-shared data/spin-sound-track.json). */
object SpinSoundTrack {
    const val ASSET = SharedSpinTrack.asset
    const val STOP_TIME = SharedSpinTrack.stopTime
    const val BRAKE_DURATION = SharedSpinTrack.brakeDuration
    const val PERIOD_INTERCEPT = SharedSpinTrack.periodIntercept
    const val PERIOD_SLOPE = SharedSpinTrack.periodSlope
    const val WIND_UP = SharedSpinTrack.windUp
    const val SPIN_DURATION = STOP_TIME + BRAKE_DURATION
    val onsets: DoubleArray = SharedSpinTrack.onsets
}

/** CSS/SwiftUI-style cubic Bézier timing curve (iOS RouletteCore/SpinTicks.swift `TimingCurve`). */
data class TimingCurve(val c1x: Double, val c1y: Double, val c2x: Double, val c2y: Double) {
    private fun bezier(s: Double, a: Double, b: Double): Double {
        val u = 1 - s
        return 3 * u * u * s * a + 3 * u * s * s * b + s * s * s
    }

    fun x(s: Double) = bezier(s, c1x, c2x)
    fun y(s: Double) = bezier(s, c1y, c2y)

    fun progress(timeFraction: Double): Double = y(solve(timeFraction.coerceIn(0.0, 1.0), ::x))
    fun timeFraction(progress: Double): Double = x(solve(progress.coerceIn(0.0, 1.0), ::y))

    private fun solve(target: Double, component: (Double) -> Double): Double {
        var lo = 0.0
        var hi = 1.0
        repeat(60) {
            val mid = (lo + hi) / 2
            if (component(mid) < target) lo = mid else hi = mid
        }
        return (lo + hi) / 2
    }

    companion object {
        val spin = TimingCurve(0.12, 0.8, 0.2, 1.0)
        val easeOut = TimingCurve(0.0, 0.0, 0.58, 1.0)
        val linear = TimingCurve(0.0, 0.0, 1.0, 1.0)
    }
}

/** When the pointer passes each slice boundary (iOS `SpinTicks`). */
object SpinTicks {
    fun boundaryRotations(start: Double, end: Double, geometry: WheelGeometry): List<Double> {
        if (!start.isFinite() || !end.isFinite() || end <= start || geometry.count <= 1) return emptyList()
        val out = ArrayList<Double>()
        for (segment in geometry.segments) {
            val base = -segment.startAngle
            var k = floor((start - base) / WheelMath.FULL_TURN) + 1
            var r = base + k * WheelMath.FULL_TURN
            while (r <= start) { k += 1; r = base + k * WheelMath.FULL_TURN }
            while (r <= end) {
                out += r
                k += 1
                r = base + k * WheelMath.FULL_TURN
            }
        }
        return out.sorted()
    }

    fun tickTimes(start: Double, end: Double, geometry: WheelGeometry, curve: TimingCurve, duration: Double, minInterval: Double = 0.04): List<Double> {
        if (duration <= 0 || !duration.isFinite()) return emptyList()
        val span = end - start
        val times = ArrayList<Double>()
        for (rotation in boundaryRotations(start, end, geometry)) {
            val time = curve.timeFraction((rotation - start) / span) * duration
            if (time >= duration - 1e-6) continue
            val last = times.lastOrNull()
            if (last != null && time - last < minInterval) continue
            times += time
        }
        return times
    }

    fun thinned(times: List<Double>, minInterval: Double): List<Double> {
        val kept = ArrayList<Double>()
        for (t in times) {
            val last = kept.lastOrNull()
            if (last != null && t - last < minInterval) continue
            kept += t
        }
        return kept
    }
}

/**
 * The wheel's motion for one spin, locked to the recorded spin sound (port of iOS
 * RouletteCore/SpinMotion.swift). The base curve follows the clip's click rate; each slice-boundary
 * crossing is moved by at most [MAX_SNAP] onto a click onset through a smooth monotone time warp.
 * Only timing changes, never where the wheel lands.
 */
class SpinMotion(
    val realKnots: DoubleArray,
    val baseKnots: DoubleArray,
    val duration: Double,
    val crossingTimes: List<Double>,
    val snapOffsets: List<Double>,
) {
    val durationMs: Int get() = Math.round(duration * 1000).toInt()

    /** Progress 0..1 at real time [t]; exactly the boundary progress at each crossing. */
    fun progress(t: Double): Double {
        if (t <= 0) return 0.0
        if (t >= duration) return 1.0
        var lo = 0
        var hi = realKnots.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (realKnots[mid] <= t) lo = mid else hi = mid
        }
        return baseProgress(warp(t, lo))
    }

    private fun slope(i: Int) = (baseKnots[i + 1] - baseKnots[i]) / (realKnots[i + 1] - realKnots[i])

    private fun tangent(i: Int): Double {
        val n = realKnots.size
        if (i == 0) return slope(0)
        if (i == n - 1) return slope(n - 2)
        val a = slope(i - 1)
        val b = slope(i)
        if (a <= 0 || b <= 0) return 0.0
        return 2 / (1 / a + 1 / b)
    }

    private fun warp(t: Double, i: Int): Double {
        val x0 = realKnots[i]
        val x1 = realKnots[i + 1]
        val y0 = baseKnots[i]
        val y1 = baseKnots[i + 1]
        val h = x1 - x0
        val s = (t - x0) / h
        val m0 = tangent(i) * h
        val m1 = tangent(i + 1) * h
        val s2 = s * s
        val s3 = s2 * s
        val y = (2 * s3 - 3 * s2 + 1) * y0 + (s3 - 2 * s2 + s) * m0 + (-2 * s3 + 3 * s2) * y1 + (s3 - s2) * m1
        return y.coerceIn(y0, y1)
    }

    companion object {
        const val MAX_SNAP = SharedSpinTrack.maxSnap
        private const val STEP = 0.001
        private const val MISS_PENALTY = 0.5

        // Kept for older call sites/tests.
        const val STOP_TIME = SpinSoundTrack.STOP_TIME
        const val SPIN_DURATION = SpinSoundTrack.SPIN_DURATION
        val spinDurationMs: Int = Math.round(SPIN_DURATION * 1000).toInt()

        fun speed(t: Double): Double {
            val period = SpinSoundTrack.PERIOD_INTERCEPT + SpinSoundTrack.PERIOD_SLOPE * maxOf(0.0, t)
            val x = (t / SpinSoundTrack.WIND_UP).coerceIn(0.0, 1.0)
            val ramp = 0.2 + 0.8 * x * x * (3 - 2 * x)
            return ramp / period
        }

        private val table: DoubleArray by lazy {
            val n = ceil(SPIN_DURATION / STEP).toInt() + 1
            val cum = DoubleArray(n)
            val stopSpeed = speed(STOP_TIME)
            var acc = 0.0
            for (i in 1 until n) {
                val t = (i - 0.5) * STEP
                val v = if (t < STOP_TIME) speed(t) else stopSpeed * (1 - minOf(1.0, (t - STOP_TIME) / SpinSoundTrack.BRAKE_DURATION))
                acc += v * STEP
                cum[i] = acc
            }
            for (i in cum.indices) cum[i] /= acc
            cum
        }

        fun baseProgress(t: Double): Double {
            if (t <= 0) return 0.0
            val f = t / STEP
            val i = f.toInt()
            if (i + 1 >= table.size) return 1.0
            return table[i] + (table[i + 1] - table[i]) * (f - i)
        }

        /** Unwarped progress (base curve). */
        fun progress(t: Double): Double = baseProgress(t)

        fun baseTime(p: Double): Double {
            if (p <= 0) return 0.0
            if (p >= 1) return SPIN_DURATION
            var lo = 0
            var hi = table.size - 1
            while (hi - lo > 1) {
                val mid = (lo + hi) / 2
                if (table[mid] < p) lo = mid else hi = mid
            }
            val span = table[hi] - table[lo]
            val frac = if (span > 0) (p - table[lo]) / span else 0.0
            return (lo + frac) * STEP
        }

        /** Compose easing over the base curve (fraction of time -> fraction of rotation). */
        val easing = androidx.compose.animation.core.Easing { f -> baseProgress(f.toDouble() * SPIN_DURATION).toFloat() }

        fun plan(start: Double, end: Double, geometry: WheelGeometry, onsets: DoubleArray = SpinSoundTrack.onsets): SpinMotion {
            val duration = SPIN_DURATION
            val span = end - start
            if (span <= 0 || !span.isFinite()) {
                return SpinMotion(doubleArrayOf(0.0, duration), doubleArrayOf(0.0, duration), duration, emptyList(), emptyList())
            }
            val taus = ArrayList<Double>()
            for (rotation in SpinTicks.boundaryRotations(start, end, geometry)) {
                val tau = baseTime((rotation - start) / span)
                if (tau <= 0.001 || tau >= duration - 0.001 || tau <= (taus.lastOrNull() ?: 0.0) + 0.0005) continue
                taus += tau
            }
            val cands = ArrayList<DoubleArray>()
            val snapped = ArrayList<BooleanArray>()
            cands += doubleArrayOf(0.0); snapped += booleanArrayOf(false)
            for (tau in taus) {
                val c = ArrayList<Double>()
                val f = ArrayList<Boolean>()
                if (tau < STOP_TIME) {
                    for (onset in clickOnsets(tau, onsets)) if (onset < STOP_TIME) { c += onset; f += true }
                }
                c += tau; f += false
                cands += c.toDoubleArray(); snapped += f.toBooleanArray()
            }
            cands += doubleArrayOf(duration); snapped += booleanArrayOf(false)
            val knotBase = DoubleArray(taus.size + 2).also { a -> taus.forEachIndexed { i, v -> a[i + 1] = v }; a[a.size - 1] = duration }
            val choice = smoothestChoice(cands, snapped, knotBase)
            val real = ArrayList<Double>(); val base = ArrayList<Double>()
            val crossings = ArrayList<Double>(); val offsets = ArrayList<Double>()
            real += 0.0; base += 0.0
            for (k in 1 until knotBase.size - 1) {
                val t = cands[k][choice[k]]
                real += t; base += knotBase[k]
                crossings += t; offsets += t - knotBase[k]
            }
            real += duration; base += duration
            return SpinMotion(real.toDoubleArray(), base.toDoubleArray(), duration, crossings, offsets)
        }

        private fun smoothestChoice(cands: List<DoubleArray>, snapped: List<BooleanArray>, base: DoubleArray): IntArray {
            val n = cands.size
            if (n <= 2) return IntArray(n)
            fun gap(k: Int) = if (k == 1 || k == n - 1) 0.002 else 0.004
            fun pen(k: Int, j: Int) = if (k > 0 && k < n - 1 && !snapped[k][j]) MISS_PENALTY else 0.0
            val inf = Double.POSITIVE_INFINITY
            // cost[k][j][i]: best cost with knot k = candidate j, knot k-1 = candidate i.
            val cost = ArrayList<Array<DoubleArray>>()
            val back = ArrayList<Array<IntArray>>()
            cost += arrayOf(doubleArrayOf(0.0)); back += arrayOf(intArrayOf(0))
            cost += Array(cands[1].size) { j -> doubleArrayOf(if (cands[1][j] > cands[0][0] + gap(1)) pen(1, j) else inf) }
            back += Array(cands[1].size) { intArrayOf(0) }
            for (k in 2 until n) {
                val ck = Array(cands[k].size) { DoubleArray(cands[k - 1].size) }
                val bk = Array(cands[k].size) { IntArray(cands[k - 1].size) }
                for (l in cands[k].indices) {
                    for (j in cands[k - 1].indices) {
                        var best = inf
                        var arg = 0
                        val t1 = cands[k - 1][j]
                        val t2 = cands[k][l]
                        if (t2 > t1 + gap(k)) {
                            val s2 = (base[k] - base[k - 1]) / (t2 - t1)
                            for (i in cands[k - 2].indices) {
                                val prev = cost[k - 1][j][i]
                                if (prev >= inf) continue
                                val t0 = cands[k - 2][i]
                                val s1 = (base[k - 1] - base[k - 2]) / (t1 - t0)
                                val r = ln(s2 / s1)
                                val c = prev + r * r + pen(k, l)
                                if (c < best) { best = c; arg = i }
                            }
                        }
                        ck[l][j] = best
                        bk[l][j] = arg
                    }
                }
                cost += ck; back += bk
            }
            val choice = IntArray(n) { cands.size }
            var bestJ = -1
            var bestCost = inf
            for (j in cands[n - 2].indices) if (cost[n - 1][0][j] < bestCost) { bestCost = cost[n - 1][0][j]; bestJ = j }
            if (bestJ < 0) return IntArray(n) { cands[it].size - 1 }
            choice[n - 1] = 0
            choice[n - 2] = bestJ
            for (k in n - 1 downTo 2) choice[k - 2] = back[k][choice[k]][choice[k - 1]]
            return choice
        }

        private fun clickOnsets(t: Double, sorted: DoubleArray): List<Double> {
            if (sorted.isEmpty()) return emptyList()
            var lo = 0
            var hi = sorted.size
            while (lo < hi) {
                val mid = (lo + hi) / 2
                if (sorted[mid] < t - MAX_SNAP) lo = mid + 1 else hi = mid
            }
            val out = ArrayList<Double>()
            var i = lo
            while (i < sorted.size && sorted[i] <= t + MAX_SNAP) { out += sorted[i]; i++ }
            return out
        }
    }
}

/**
 * "Tap to stop" (iOS `SpinStopPlan`): the spin in flight ends early on a NEW random landing at least
 * 120° ahead, decelerating over 0.8 s easeOut. Shown angle = start + span·progress + offset.
 */
data class SpinStopPlan(
    val startTime: Double,
    val start: Double,
    val oldTarget: Double,
    val currentAngle: Double,
    val newTarget: Double,
) {
    val offset: Double get() = newTarget - oldTarget
    val span: Double get() = oldTarget - start
    val fromProgress: Double get() = if (span == 0.0) 1.0 else (currentAngle - offset - start) / span
    val endTime: Double get() = startTime + DURATION

    fun progress(t: Double): Double {
        val u = ((t - startTime) / DURATION).coerceIn(0.0, 1.0)
        val from = fromProgress
        return from + (1 - from) * CURVE.progress(u)
    }

    fun displayedAngle(t: Double): Double = start + span * progress(t) + offset

    fun crossingTimes(geometry: WheelGeometry): List<Double> =
        SpinTicks.tickTimes(currentAngle, newTarget, geometry, CURVE, DURATION, minInterval = 0.0)

    companion object {
        const val DURATION = 0.8
        const val MINIMUM_TRAVEL = 120.0
        val CURVE = TimingCurve.easeOut

        fun make(motion: SpinMotion, start: Double, oldTarget: Double, elapsed: Double, geometry: WheelGeometry, random: Random): SpinStopPlan? {
            if (!elapsed.isFinite() || elapsed < 0 || elapsed >= motion.duration - DURATION) return null
            val current = start + (oldTarget - start) * motion.progress(elapsed)
            val target = geometry.randomTargetRotation(current + MINIMUM_TRAVEL, random, fullTurns = 0..0)
            return SpinStopPlan(elapsed, start, oldTarget, current, target)
        }
    }
}
