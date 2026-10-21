package com.movieroulettetuesday.app.roulette

import kotlin.random.Random

/**
 * iOS `SeededRandomNumberGenerator` (SplitMix64) with Swift's mapping from 64-bit outputs to values
 * (mrt-shared SPEC §4.1, DISCREPANCIES D1), so seeded shared vectors reproduce on the JVM:
 * - unit double = (next() & (2^53 - 1)) * 2^-53 (one draw, LOW 53 bits)
 * - double in a..<b = a + (b - a) * unit
 * - int in lo...hi = lo + Lemire multiply-high with rejection over n = hi - lo + 1
 * Test-only (answers-from-ios.md Q18): production spins use the system RNG.
 */
open class SwiftCompatRandom(seed: Long) : Random() {
    private var state = seed

    /** Raw SplitMix64 output (`next()`), as a signed Long holding the UInt64 bits. */
    fun nextUInt64(): Long {
        state += -0x61c8864680b583ebL // 0x9E3779B97F4A7C15
        var z = state
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L // 0xBF58476D1CE4E5B9
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L // 0x94D049BB133111EB
        return z xor (z ushr 31)
    }

    override fun nextLong(): Long = nextUInt64()

    override fun nextBits(bitCount: Int): Int = if (bitCount == 0) 0 else (nextUInt64() ushr (64 - bitCount)).toInt()

    override fun nextDouble(): Double = (nextUInt64() and MASK_53).toDouble() * TWO_POW_MINUS_53

    override fun nextDouble(until: Double): Double = nextDouble(0.0, until)

    override fun nextDouble(from: Double, until: Double): Double {
        require(until > from) { "empty range" }
        var r: Double
        do { r = from + (until - from) * nextDouble() } while (r >= until) // Swift retries r == upperBound
        return r
    }

    override fun nextInt(): Int = nextUInt64().toInt()

    override fun nextInt(until: Int): Int = nextInt(0, until)

    /** Half-open like Kotlin; maps to Swift `Int.random(in: from...(until-1))`. */
    override fun nextInt(from: Int, until: Int): Int {
        require(until > from) { "empty range" }
        return (from + nextBelow(until.toLong() - from.toLong())).toInt()
    }

    override fun nextLong(from: Long, until: Long): Long {
        require(until > from) { "empty range" }
        return from + nextBelow(until - from)
    }

    /** Swift `next(upperBound:)`: Lemire's nearly-divisionless method. [n] > 0 (fits in 63 bits here). */
    fun nextBelow(n: Long): Long {
        var x = nextUInt64()
        var low = x * n
        if (java.lang.Long.compareUnsigned(low, n) < 0) {
            val t = java.lang.Long.remainderUnsigned(-n, n)
            while (java.lang.Long.compareUnsigned(low, t) < 0) {
                x = nextUInt64()
                low = x * n
            }
        }
        return mulHighUnsigned(x, n)
    }

    companion object {
        private const val MASK_53 = (1L shl 53) - 1
        private const val TWO_POW_MINUS_53 = 1.0 / (1L shl 53)

        /** High 64 bits of the unsigned 128-bit product (no Math.multiplyHigh: minSdk 26). */
        fun mulHighUnsigned(a: Long, b: Long): Long {
            val aLo = a and 0xFFFFFFFFL
            val aHi = a ushr 32
            val bLo = b and 0xFFFFFFFFL
            val bHi = b ushr 32
            val loLo = aLo * bLo
            val hiLo = aHi * bLo
            val loHi = aLo * bHi
            val hiHi = aHi * bHi
            val cross = (loLo ushr 32) + (hiLo and 0xFFFFFFFFL) + loHi
            return hiHi + (hiLo ushr 32) + (cross ushr 32)
        }
    }
}

/** Old name kept for existing tests: now the Swift-compatible generator. */
typealias SeededRandom = SwiftCompatRandom
