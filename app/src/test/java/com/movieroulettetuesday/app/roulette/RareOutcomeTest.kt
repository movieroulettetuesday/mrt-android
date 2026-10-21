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

    @Test fun jackpotPick() {
        val c = RouletteFixtures.withInfamous()
        assertNull(RareOutcomeRoller.jackpotPick(RouletteFixtures.catalog, c.categories[0], SeededRandom(1)))
        val (movie, cat) = RareOutcomeRoller.jackpotPick(c, c.category("a")!!, SeededRandom(1))!!
        assertEquals("d-2", movie.id)
        assertEquals("d", cat.id) // not in the landed category -> its own first category
        val (_, same) = RareOutcomeRoller.jackpotPick(c, c.category("d")!!, SeededRandom(1))!!
        assertEquals("d", same.id)
        assertNotNull(same)
    }

    @Test fun seededRandomMatchesSplitMix64() {
        // Reference values of SplitMix64 for seed 0 (same algorithm as iOS SeededRandomNumberGenerator).
        val r = SeededRandom(0)
        assertEquals(0xE220A8397B1DCDAFuL.toLong(), r.nextLong())
        assertEquals(0x6E789E6AA1B965F4uL.toLong(), r.nextLong())
    }
}
