package com.movieroulettetuesday.app.roulette

import com.movieroulettetuesday.app.catalog.Movie
import com.movieroulettetuesday.app.catalog.MovieCatalog
import com.movieroulettetuesday.app.catalog.RouletteCategory
import kotlin.random.Random

/** Very occasional surprises on top of a normal spin (RouletteCore/RareOutcomes.swift). */
enum class RareOutcome(
    /** iOS raw value (mrt-shared vectors). */
    val wire: String,
) { Normal("normal"), ForcedRespin("forcedRespin"), InfamousJackpot("infamousJackpot") }

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

    /** iOS 1.0.1 `jackpotCandidates`: every catalog movie (the jackpot was never limited by category). */
    fun jackpotCandidates(catalog: MovieCatalog): List<Movie> = catalog.movies

    /**
     * iOS 1.0.1 `jackpotPick(in:landed:listenerAverages:using:)`: a movie chosen by [InfamousSelection]
     * (weighted by [listenerAverages], movie id -> average stars) shown under the landed category if it
     * belongs there, else its first known one. Draws exactly one unit double when there are movies.
     */
    fun jackpotPick(
        catalog: MovieCatalog,
        landed: RouletteCategory,
        listenerAverages: Map<String, Double> = emptyMap(),
        random: Random,
    ): Pair<Movie, RouletteCategory>? {
        val movie = InfamousSelection.pick(jackpotCandidates(catalog), listenerAverages, random) ?: return null
        return movie to creditedCategory(movie, landed, catalog)
    }

    /** Landed category if the movie belongs to it (either direction), else its first known one, else landed. */
    fun creditedCategory(movie: Movie, landed: RouletteCategory, catalog: MovieCatalog): RouletteCategory {
        if (landed.id in movie.categories || movie.id in landed.movieIds) return landed
        return movie.categories.firstNotNullOfOrNull { catalog.category(it) } ?: landed
    }
}

/**
 * How an Infamous Jackpot chooses its movie (iOS 1.0.1, mrt-shared infamous-selection.md). The
 * interface stays so tests and previews can inject a fixed pick; [RatingWeightedSelector] is the
 * only production implementation.
 */
interface JackpotSelector {
    /** Whether the jackpot band can fire for this catalog (otherwise the roll counts as normal). */
    fun isAvailable(catalog: MovieCatalog): Boolean

    /** The jackpot movie and the category it is shown under; draws its own randomness. */
    fun pick(catalog: MovieCatalog, landed: RouletteCategory, random: Random): Pair<Movie, RouletteCategory>?
}

/**
 * iOS 1.0.1 (5d459a0): every movie is a candidate, weighted by its cached average listener rating
 * (lower-rated more likely). [averages] = movieId -> average stars, from the community ratings
 * cache; missing = unrated. The legacy `isInfamous` catalog key is not decoded and carries no weight.
 */
class RatingWeightedSelector(private val averages: () -> Map<String, Double> = { emptyMap() }) : JackpotSelector {
    override fun isAvailable(catalog: MovieCatalog) = RareOutcomeRoller.jackpotCandidates(catalog).isNotEmpty()

    override fun pick(catalog: MovieCatalog, landed: RouletteCategory, random: Random) =
        RareOutcomeRoller.jackpotPick(catalog, landed, averages(), random)
}

/**
 * iOS 1.0.1 `InfamousSelection`:
 *
 *     rated (avg clamped to 0.5...5):  weight = 5.5 - avg   (0.5 ... 5.0)
 *     unrated (no / non-finite avg):   weight = 2.5         (a 3-star average)
 *     P(movie) = weight / sum(weights)
 */
object InfamousSelection {
    const val MAX_STARS = 5.0
    const val MIN_STARS = 0.5
    const val UNRATED_STARS = 3.0
    /** 2.5: the weight of a 3-star average. */
    const val UNRATED_WEIGHT = MAX_STARS + 0.5 - UNRATED_STARS

    /** Always > 0 and finite. */
    fun weight(average: Double?): Double {
        val stars = if (average == null || !average.isFinite()) UNRATED_STARS else average.coerceIn(MIN_STARS, MAX_STARS)
        return MAX_STARS + 0.5 - stars
    }

    fun weights(movies: List<Movie>, averages: Map<String, Double>): List<Double> = movies.map { weight(averages[it.id]) }

    fun probabilities(movies: List<Movie>, averages: Map<String, Double>): List<Double> {
        val w = weights(movies, averages)
        val total = w.sum()
        return if (total > 0) w.map { it / total } else emptyList()
    }

    /** Same half-open cumulative intervals as a normal spin ([MovieSelector.pickIndex]); empty -> null. */
    fun pickIndex(weights: List<Double>, unit: Double): Int? = MovieSelector.pickIndex(weights, unit)

    /** Weighted random movie; null only when [movies] is empty. Draws exactly one unit double. */
    fun pick(movies: List<Movie>, averages: Map<String, Double>, random: Random): Movie? {
        if (movies.isEmpty()) return null
        val unit = random.nextDouble()
        return pickIndex(weights(movies, averages), unit)?.let { movies[it] }
    }
}
