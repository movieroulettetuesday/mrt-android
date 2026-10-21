package com.movieroulettetuesday.app.roulette

import com.movieroulettetuesday.app.catalog.Movie
import com.movieroulettetuesday.app.catalog.MovieCatalog
import com.movieroulettetuesday.app.catalog.RouletteCategory
import kotlin.random.Random

/** Very occasional surprises on top of a normal spin (RouletteCore/RareOutcomes.swift). */
enum class RareOutcome { Normal, ForcedRespin, InfamousJackpot }

class RareOutcomePolicy(
    val isEnabled: Boolean = true,
    forcedRespinChance: Double = 0.01,
    infamousJackpotChance: Double = 0.01,
) {
    val forcedRespinChance: Double = clamp(forcedRespinChance)
    /** Both bands share [0, 1); never overflow it. */
    val infamousJackpotChance: Double = minOf(clamp(infamousJackpotChance), 1 - this.forcedRespinChance)

    private fun clamp(v: Double) = if (v.isFinite()) v.coerceIn(0.0, 1.0) else 0.0
}

object RareOutcomeRoller {
    /** [0, respin) -> forced re-spin; [respin, respin + jackpot) -> jackpot; else normal. */
    fun outcome(unitValue: Double, policy: RareOutcomePolicy, infamousAvailable: Boolean, allowRespin: Boolean = true): RareOutcome {
        if (!policy.isEnabled || !unitValue.isFinite()) return RareOutcome.Normal
        val unit = unitValue.coerceIn(0.0, 1.0)
        if (unit < policy.forcedRespinChance) return if (allowRespin) RareOutcome.ForcedRespin else RareOutcome.Normal
        if (unit < policy.forcedRespinChance + policy.infamousJackpotChance) {
            return if (infamousAvailable) RareOutcome.InfamousJackpot else RareOutcome.Normal
        }
        return RareOutcome.Normal
    }

    /** Always consumes exactly one random value. */
    fun roll(policy: RareOutcomePolicy, infamousAvailable: Boolean, allowRespin: Boolean, random: Random): RareOutcome =
        outcome(random.nextDouble(), policy, infamousAvailable, allowRespin)

    fun infamousMovies(catalog: MovieCatalog): List<Movie> = catalog.movies.filter { it.isInfamous }

    /** Uniform infamous movie, shown under the landed category if it belongs there, else its first known one. */
    fun jackpotPick(catalog: MovieCatalog, landed: RouletteCategory, random: Random): Pair<Movie, RouletteCategory>? {
        val infamous = infamousMovies(catalog)
        if (infamous.isEmpty()) return null
        val movie = infamous[random.nextInt(infamous.size)]
        if (landed.id in movie.categories || movie.id in landed.movieIds) return movie to landed
        val category = movie.categories.firstNotNullOfOrNull { catalog.category(it) } ?: landed
        return movie to category
    }
}

/** SplitMix64, deterministic for tests (same constants as the iOS SeededRandomNumberGenerator). */
class SeededRandom(seed: Long) : Random() {
    private var state = seed

    override fun nextLong(): Long {
        state += -0x61c8864680b583ebL // 0x9E3779B97F4A7C15
        var z = state
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L // 0xBF58476D1CE4E5B9
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L // 0x94D049BB133111EB
        return z xor (z ushr 31)
    }

    override fun nextBits(bitCount: Int): Int =
        if (bitCount == 0) 0 else (nextLong() ushr (64 - bitCount)).toInt()
}
