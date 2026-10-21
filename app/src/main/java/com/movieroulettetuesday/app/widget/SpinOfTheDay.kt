package com.movieroulettetuesday.app.widget

import com.movieroulettetuesday.app.catalog.Movie
import com.movieroulettetuesday.app.catalog.MovieCatalog
import com.movieroulettetuesday.app.catalog.RouletteCategory
import com.movieroulettetuesday.app.catalog.StableHash
import com.movieroulettetuesday.app.roulette.MovieSelector
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Deterministic "Spin of the Day" (iOS `SpinOfTheDay`): the same local date always gives the same
 * category and movie, in the app and the widget, on iOS and Android, with no shared state.
 */
object SpinOfTheDay {
    data class Pick(val movie: Movie, val category: RouletteCategory?, val dayKey: String)

    /** "2026-10-08" for the local calendar day. */
    fun dayKey(date: LocalDate): String = "%04d-%02d-%02d".format(date.year, date.monthValue, date.dayOfMonth)

    fun dayKey(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String = dayKey(Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate())

    /** A wheel category for the day, then a movie from it; the whole catalog if that category is empty. */
    fun pick(dayKey: String, catalog: MovieCatalog): Pick? {
        val selector = MovieSelector()
        if (catalog.categories.isNotEmpty()) {
            val category = catalog.categories[StableHash.index("category:$dayKey", catalog.categories.size)]
            val candidates = selector.candidates(category, catalog)
            if (candidates.isNotEmpty()) {
                return Pick(candidates[StableHash.index("movie:$dayKey:${category.id}", candidates.size)], category, dayKey)
            }
        }
        if (catalog.movies.isEmpty()) return null
        val movie = catalog.movies[StableHash.index("movie:$dayKey", catalog.movies.size)]
        return Pick(movie, movie.categories.firstOrNull()?.let(catalog::category), dayKey)
    }

    fun pick(epochMs: Long, catalog: MovieCatalog, zone: ZoneId = ZoneId.systemDefault()): Pick? = pick(dayKey(epochMs, zone), catalog)

    /** Start of the next local day (the widget's refresh time). */
    fun nextRefresh(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
}
