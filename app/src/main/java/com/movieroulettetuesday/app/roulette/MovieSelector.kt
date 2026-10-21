package com.movieroulettetuesday.app.roulette

import com.movieroulettetuesday.app.catalog.Movie
import com.movieroulettetuesday.app.catalog.MovieCatalog
import com.movieroulettetuesday.app.catalog.RouletteCategory
import kotlin.random.Random

/** Tunables for weighted movie selection (RouletteCore/MovieSelector.swift). */
data class SelectionPolicy(
    /** How many of the most recent spins count as "recent". */
    val recentWindow: Int = 5,
    /** Weight multiplier for the very last spin's movie; recovers linearly toward 1 across the window. */
    val mostRecentMultiplier: Double = 0.1,
    val infamousBoost: Double = 1.25,
) {
    val window: Int get() = recentWindow.coerceAtLeast(0)
    val recentMultiplier: Double get() = mostRecentMultiplier.coerceIn(0.0, 1.0)
    val boost: Double get() = infamousBoost.coerceAtLeast(0.0)
}

/** Resolves a category to a movie with weighted randomness. All randomness comes from the injected [Random]. */
class MovieSelector(val policy: SelectionPolicy = SelectionPolicy()) {
    /** The category's `movieIds` (in order) plus any movie that lists the category, de-duplicated. */
    fun candidates(category: RouletteCategory, catalog: MovieCatalog): List<Movie> {
        val seen = HashSet<String>()
        val result = mutableListOf<Movie>()
        for (id in category.movieIds) {
            val movie = catalog.movie(id)
            if (movie != null && seen.add(id)) result += movie
        }
        for (movie in catalog.movies) {
            if (category.id in movie.categories && seen.add(movie.id)) result += movie
        }
        return result
    }

    /** [spinsAgo] 1 = the most recent spin; null or outside the window -> 1. */
    fun recencyMultiplier(spinsAgo: Int?): Double {
        if (spinsAgo == null || spinsAgo < 1 || spinsAgo > policy.window) return 1.0
        val recovered = (spinsAgo - 1).toDouble() / policy.window
        return policy.recentMultiplier + (1 - policy.recentMultiplier) * recovered
    }

    /** History is chronological (oldest first). */
    fun weight(movie: Movie, history: List<SpinResult>): Double {
        var weight = 1.0
        if (movie.isInfamous) weight *= policy.boost
        val recent = history.takeLast(policy.window).asReversed()
        val idx = recent.indexOfFirst { it.movieId == movie.id }
        return weight * recencyMultiplier(if (idx >= 0) idx + 1 else null)
    }

    fun selectMovie(category: RouletteCategory, catalog: MovieCatalog, history: List<SpinResult>, random: Random): Movie? {
        val candidates = candidates(category, catalog)
        if (candidates.isEmpty()) return null
        val weights = candidates.map { weight(it, history) }
        val index = pickIndex(weights, random.nextDouble()) ?: return null
        return candidates[index]
    }

    companion object {
        /**
         * Weighted pick from a uniform value in [0, 1). Zero/negative/non-finite weights never win
         * unless all are unusable (then uniform). Empty -> null.
         */
        fun pickIndex(weights: List<Double>, unitValue: Double): Int? {
            if (weights.isEmpty()) return null
            val unit = if (unitValue.isFinite()) unitValue.coerceIn(0.0, 1.0) else 0.0
            val usable = weights.map { if (it.isFinite() && it > 0) it else 0.0 }
            val total = usable.sum()
            if (total <= 0) return minOf((unit * weights.size).toInt(), weights.size - 1)
            val target = unit * total
            var cumulative = 0.0
            usable.forEachIndexed { i, w ->
                if (w > 0) {
                    cumulative += w
                    if (target < cumulative) return i
                }
            }
            return usable.indexOfLast { it > 0 }
        }
    }
}
