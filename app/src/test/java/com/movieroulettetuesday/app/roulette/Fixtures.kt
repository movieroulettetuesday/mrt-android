package com.movieroulettetuesday.app.roulette

import com.movieroulettetuesday.app.catalog.Movie
import com.movieroulettetuesday.app.catalog.MovieCatalog
import com.movieroulettetuesday.app.catalog.RouletteCategory

object RouletteFixtures {
    /** Four categories (a-d), two movies each ("<c>-1", "<c>-2"), in category order. */
    /** Listener averages that make d-2 the clear jackpot favourite (weight 5.0 vs 0.5 for the rest). */
    val jackpotAverages: Map<String, Double> = catalogIds().associateWith { if (it == "d-2") 0.5 else 5.0 }

    private fun catalogIds() = listOf("a", "b", "c", "d").flatMap { listOf("$it-1", "$it-2") }

    val catalog: MovieCatalog = run {
        val cats = listOf("a", "b", "c", "d")
        val movies = cats.flatMap { c ->
            listOf(
                Movie(id = "$c-1", title = "Movie $c 1", year = 2001, genre = "Drama", categories = listOf(c), episodeIds = listOf("Buzzsprout-1"), episodeTitle = "Ep $c"),
                Movie(id = "$c-2", title = "Movie $c 2", year = 2002, genre = "Drama", categories = listOf(c)),
            )
        }
        MovieCatalog(
            schemaVersion = 2,
            categories = cats.mapIndexed { i, c ->
                RouletteCategory(id = c, name = c.uppercase(), fullName = "Category ${c.uppercase()}", colorHex = listOf("#C1121F", "#7B2CBF", "#E9C46A", "#2A9D8F")[i], movieIds = listOf("$c-1", "$c-2"))
            },
            movies = movies,
        )
    }

}
