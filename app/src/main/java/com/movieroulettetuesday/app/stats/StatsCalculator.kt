package com.movieroulettetuesday.app.stats

import com.movieroulettetuesday.app.catalog.MovieCatalog
import com.movieroulettetuesday.app.roulette.SpinHistoryStore
import com.movieroulettetuesday.app.roulette.SpinResult
import com.movieroulettetuesday.app.roulette.SpinStats

data class CountedId(val id: String, val count: Int)

/** Stats tab aggregation (iOS `StatsCalculator.snapshot`), archived counts included. */
data class StatsSnapshot(
    val totalSpins: Int,
    val distinctMovies: Int,
    val mostSpunMovie: CountedId?,
    val mostSpunCategory: CountedId?,
    val categoryBreakdown: List<CountedId>,
    val genreBreakdown: List<CountedId>,
    val recent: List<SpinResult>,
) {
    companion object {
        fun of(log: SpinHistoryStore.Log, catalog: MovieCatalog?, recentLimit: Int = 10): StatsSnapshot {
            val stats = SpinStats(log)
            val genreById = catalog?.movies?.associate { it.id to it.genre }.orEmpty()
            val genres = LinkedHashMap<String, Int>()
            for ((movieId, count) in stats.movieCounts) genreById[movieId]?.let { genres[it] = (genres[it] ?: 0) + count }
            // Ties: higher count first, then id, so the output is deterministic (iOS dictionary order is not).
            fun sorted(m: Map<String, Int>) = m.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .map { CountedId(it.key, it.value) }
            val movies = sorted(stats.movieCounts)
            val cats = sorted(stats.categoryCounts)
            return StatsSnapshot(
                totalSpins = stats.totalSpins,
                distinctMovies = stats.distinctMoviesSpun,
                mostSpunMovie = movies.firstOrNull(),
                mostSpunCategory = cats.firstOrNull(),
                categoryBreakdown = cats,
                genreBreakdown = sorted(genres),
                recent = log.results.takeLast(maxOf(0, recentLimit)).reversed(),
            )
        }
    }
}
