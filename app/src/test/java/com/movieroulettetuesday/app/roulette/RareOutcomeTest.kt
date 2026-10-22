package com.movieroulettetuesday.app.roulette

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RareOutcomeTest {
    private val policy = RareOutcomePolicy()

    @Test fun bandsAtOnePercentEach() {
        assertEquals(RareOutcome.ForcedRespin, RareOutcomeRoller.outcome(0.0, policy, true))
        assertEquals(RareOutcome.ForcedRespin, RareOutcomeRoller.outcome(0.0099, policy, true))
        assertEquals(RareOutcome.InfamousJackpot, RareOutcomeRoller.outcome(0.01, policy, true))
        assertEquals(RareOutcome.InfamousJackpot, RareOutcomeRoller.outcome(0.0199, policy, true))
        assertEquals(RareOutcome.Normal, RareOutcomeRoller.outcome(0.02, policy, true))
    }

    @Test fun jackpotFallsBackAndRespinsNeverChain() {
        assertEquals(RareOutcome.Normal, RareOutcomeRoller.outcome(0.015, policy, infamousAvailable = false))
        assertEquals(RareOutcome.Normal, RareOutcomeRoller.outcome(0.005, policy, true, allowRespin = false))
    }

    @Test fun disabledAndBadInput() {
        assertEquals(RareOutcome.Normal, RareOutcomeRoller.outcome(0.0, RareOutcomePolicy(isEnabled = false), true))
        assertEquals(RareOutcome.Normal, RareOutcomeRoller.outcome(Double.NaN, policy, true))
        val greedy = RareOutcomePolicy(forcedRespinChance = 0.8, infamousJackpotChance = 0.8)
        assertEquals(0.2, greedy.infamousJackpotChance, 1e-9)
    }

    @Test fun seededFrequenciesAreRoughlyOnePercent() {
        val rnd = SeededRandom(99)
        val outcomes = (0 until 100_000).map { RareOutcomeRoller.roll(policy, true, true, rnd) }
        val respins = outcomes.count { it == RareOutcome.ForcedRespin }
        val jackpots = outcomes.count { it == RareOutcome.InfamousJackpot }
        assertTrue("$respins", respins in 800..1200)
        assertTrue("$jackpots", jackpots in 800..1200)
    }

    /** A [Random] whose doubles come from [units], counting draws. */
    private class Units(vararg val units: Double) : kotlin.random.Random() {
        var draws = 0
        override fun nextBits(bitCount: Int): Int = error("only nextDouble is expected")
        override fun nextDouble(): Double = units[draws++]
    }

    @Test fun jackpotPick() {
        val c = RouletteFixtures.catalog
        val avgs = RouletteFixtures.jackpotAverages // weights: seven movies 0.5 (5-star avg), d-2 5.0 (half-star avg): total 8.5
        // iOS 1.0.1: every movie is a candidate; no flags needed for the jackpot to be available.
        assertEquals(c.movies, RareOutcomeRoller.jackpotCandidates(c))
        assertNull(RareOutcomeRoller.jackpotPick(c.copy(movies = emptyList()), c.categories[0], avgs, Units()))
        val r = Units(0.99, 0.0, 0.41)
        val (movie, cat) = RareOutcomeRoller.jackpotPick(c, c.category("a")!!, avgs, r)!!
        assertEquals("d-2", movie.id)
        assertEquals("d", cat.id) // not in the landed category -> its own first category
        assertEquals(1, r.draws) // exactly one unit double per jackpot
        val (first, credited) = RareOutcomeRoller.jackpotPick(c, c.category("a")!!, avgs, r)!!
        assertEquals("a-1", first.id)
        assertEquals("a", credited.id)
        // 0.41 * 8.5 = 3.485 < 3.5: still the seventh movie (d-1), not d-2.
        assertEquals("d-1", RareOutcomeRoller.jackpotPick(c, c.category("d")!!, avgs, r)!!.first.id)
        assertEquals(3, r.draws)
        // Unrated movies all weigh 2.5 -> uniform.
        assertEquals(List(8) { 2.5 }, InfamousSelection.weights(c.movies, emptyMap()))
        assertNotNull(RatingWeightedSelector().pick(c, c.categories[0], SeededRandom(1)))
    }

    @Test fun seededRandomMatchesSplitMix64() {
        // Reference values of SplitMix64 for seed 0 (same algorithm as iOS SeededRandomNumberGenerator).
        val r = SeededRandom(0)
        assertEquals(0xE220A8397B1DCDAFuL.toLong(), r.nextLong())
        assertEquals(0x6E789E6AA1B965F4uL.toLong(), r.nextLong())
    }
}
