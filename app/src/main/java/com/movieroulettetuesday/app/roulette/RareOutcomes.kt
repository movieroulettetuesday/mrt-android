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

    fun infamousMovies(catalog: MovieCatalog): List<Movie> = catalog.movies.filter { it.isInfamous }

    /** Uniform infamous movie, shown under the landed category if it belongs there, else its first known one. */
    fun jackpotPick(catalog: MovieCatalog, landed: RouletteCategory, random: Random): Pair<Movie, RouletteCategory>? {
        val infamous = infamousMovies(catalog)
        if (infamous.isEmpty()) return null
        val movie = infamous[random.nextInt(infamous.size)]
        return movie to JackpotSelector.creditedCategory(movie, landed, catalog)
    }
}

/**
 * How an Infamous Jackpot chooses its movie. Behind an interface so the pending iOS 1.0.1 formula
 * ([RatingWeightedSelector], mrt-shared infamous-selection.md) can replace today's iOS main behavior
 * ([FlaggedInfamousSelector]) with one line once it lands on iOS main.
 */
interface JackpotSelector {
    /** Whether the jackpot band can fire for this catalog (otherwise the roll counts as normal). */
    fun isAvailable(catalog: MovieCatalog): Boolean

    /** The jackpot movie and the category it is shown under; draws its own randomness. */
    fun pick(catalog: MovieCatalog, landed: RouletteCategory, random: Random): Pair<Movie, RouletteCategory>?

    companion object {
        /** Landed category if the movie belongs to it (either direction), else its first known one, else landed. */
        fun creditedCategory(movie: Movie, landed: RouletteCategory, catalog: MovieCatalog): RouletteCategory {
            if (landed.id in movie.categories || movie.id in landed.movieIds) return landed
            return movie.categories.firstNotNullOfOrNull { catalog.category(it) } ?: landed
        }
    }
}

/** iOS main (b673df1): uniform int over the `isInfamous` movies, in catalog order. */
object FlaggedInfamousSelector : JackpotSelector {
    override fun isAvailable(catalog: MovieCatalog) = RareOutcomeRoller.infamousMovies(catalog).isNotEmpty()
    override fun pick(catalog: MovieCatalog, landed: RouletteCategory, random: Random) = RareOutcomeRoller.jackpotPick(catalog, landed, random)
}

/**
 * Pending iOS 1.0.1 (infamous-selection.md): every movie is a candidate, weighted by its cached
 * average listener rating (lower-rated more likely). Not the default until it is on iOS main.
 * [averages] = movieId -> average stars (0.5...5), from the community ratings cache; missing = unrated.
 */
class RatingWeightedSelector(private val averages: () -> Map<String, Double>) : JackpotSelector {
    override fun isAvailable(catalog: MovieCatalog) = catalog.movies.isNotEmpty()

    override fun pick(catalog: MovieCatalog, landed: RouletteCategory, random: Random): Pair<Movie, RouletteCategory>? {
        val movies = catalog.movies
        val index = pickIndex(weights(movies, averages()), random.nextDouble()) ?: return null
        val movie = movies[index]
        return movie to JackpotSelector.creditedCategory(movie, landed, catalog)
    }

    companion object {
        const val INFAMOUS_WEIGHT = 5.0
        const val UNRATED_WEIGHT = 2.5

        fun weight(isInfamous: Boolean, average: Double?): Double = when {
            isInfamous -> INFAMOUS_WEIGHT
            average == null || !average.isFinite() -> UNRATED_WEIGHT
            else -> 5.5 - average.coerceIn(0.5, 5.0)
        }

        fun weights(movies: List<Movie>, averages: Map<String, Double>): List<Double> =
            movies.map { weight(it.isInfamous, averages[it.id]) }

        /** One unit draw; half-open cumulative intervals; slack -> last index; empty -> null. */
        fun pickIndex(weights: List<Double>, unit: Double): Int? {
            if (weights.isEmpty()) return null
            val total = weights.sum()
            val target = unit * total
            var cum = 0.0
            for ((i, w) in weights.withIndex()) {
                cum += w
                if (target < cum) return i
            }
            return weights.size - 1
        }
    }
}
