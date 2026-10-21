package com.movieroulettetuesday.app.roulette

import androidx.compose.animation.core.Easing
import kotlin.math.ceil

/**
 * The wheel's motion for one spin, timed like the iOS recorded spin sound (RouletteCore/SpinMotion.swift
 * + SpinSoundTrack.swift): speed follows the recording's ratchet click rate (wind-up over 0.45 s,
 * ~53 easing to ~44 clicks/s), then brakes to rest within 0.09 s after the final thunk at 6.204 s.
 * Only timing is shaped; the landing angle is the uniformly random target.
 * Not ported yet: per-click time warp and the sound/haptics themselves (see PARITY.md).
 */
object SpinMotion {
    const val STOP_TIME = 6.2040
    const val BRAKE_DURATION = 0.09
    const val PERIOD_INTERCEPT = 0.018712
    const val PERIOD_SLOPE = 0.000681
    const val WIND_UP = 0.45
    const val SPIN_DURATION = STOP_TIME + BRAKE_DURATION
    val spinDurationMs: Int = (SPIN_DURATION * 1000).toInt()
    private const val STEP = 0.001

    /** Clicks per second (∝ wheel speed) with the wind-up ramp (smoothstep from 0.2). */
    fun speed(t: Double): Double {
        val period = PERIOD_INTERCEPT + PERIOD_SLOPE * maxOf(0.0, t)
        val x = (t / WIND_UP).coerceIn(0.0, 1.0)
        val ramp = 0.2 + 0.8 * x * x * (3 - 2 * x)
        return ramp / period
    }

    /** Normalised cumulative progress sampled every STEP seconds over 0..SPIN_DURATION. */
    private val table: DoubleArray by lazy {
        val n = ceil(SPIN_DURATION / STEP).toInt() + 1
        val cum = DoubleArray(n)
        val stopSpeed = speed(STOP_TIME)
        var acc = 0.0
        for (i in 1 until n) {
            val t = (i - 0.5) * STEP
            val v = if (t < STOP_TIME) speed(t) else stopSpeed * (1 - minOf(1.0, (t - STOP_TIME) / BRAKE_DURATION))
            acc += v * STEP
            cum[i] = acc
        }
        for (i in cum.indices) cum[i] /= acc
        cum
    }

    /** Progress 0..1 at time [t] seconds. */
    fun progress(t: Double): Double {
        if (t <= 0) return 0.0
        val f = t / STEP
        val i = f.toInt()
        if (i + 1 >= table.size) return 1.0
        return table[i] + (table[i + 1] - table[i]) * (f - i)
    }

    /** Compose easing: fraction of the spin's time -> fraction of its rotation. */
    val easing: Easing = Easing { fraction -> progress(fraction.toDouble() * SPIN_DURATION).toFloat() }
}
