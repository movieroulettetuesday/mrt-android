package com.movieroulettetuesday.app.shared

import com.movieroulettetuesday.app.roulette.SwiftCompatRandom
import org.junit.Assert.assertEquals
import org.junit.Test

/** selection.json#rng: SplitMix64 + Swift's value mapping (D1). */
class RandomVectorsTest {
    private val cases = SharedVectors.load("selection.json").arr("rng").objs()

    @Test
    fun rawOutputsUnitsDoublesAndInts() {
        assertEquals(5, cases.size)
        for (c in cases) {
            val seed = c.str("seed")!!.toULong().toLong()
            val raw = SwiftCompatRandom(seed)
            assertEquals("seed $seed raw", c["nextUInt64Hex"]!!.strings(), List(4) { "%016x".format(raw.nextUInt64()) })
            SwiftCompatRandom(seed).let { r -> c["unitDoubles"]!!.doubles().forEach { assertClose("seed $seed unit", it, r.nextDouble()) } }
            SwiftCompatRandom(seed).let { r -> c["doubleIn0until360"]!!.doubles().forEach { assertClose("seed $seed 0..<360", it, r.nextDouble(0.0, 360.0)) } }
            SwiftCompatRandom(seed).let { r -> assertEquals("seed $seed 0..<3", c["intIn0until3"]!!.doubles().map { it.toInt() }, List(4) { r.nextInt(0, 3) }) }
            SwiftCompatRandom(seed).let { r -> assertEquals("seed $seed 5...8", c["intIn5through8"]!!.doubles().map { it.toInt() }, List(4) { r.nextInt(5, 9) }) }
        }
    }

    @Test
    fun infamousDocSeed42() {
        val r = SwiftCompatRandom(42)
        listOf(0.7248717246943267, 0.49648461193240256, 0.575114762524189).forEach { assertClose("seed 42", it, r.nextDouble()) }
    }

    @Test
    fun mulHighUnsigned() {
        assertEquals(0L, SwiftCompatRandom.mulHighUnsigned(Long.MAX_VALUE, 1))
        assertEquals(1L, SwiftCompatRandom.mulHighUnsigned(-1L, 2)) // (2^64-1)*2 >> 64 = 1
        assertEquals(2L, SwiftCompatRandom.mulHighUnsigned(-1L, 3))
    }
}
