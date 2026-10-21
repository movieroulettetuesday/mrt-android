package com.movieroulettetuesday.app.roulette

import com.movieroulettetuesday.app.catalog.Movie
import com.movieroulettetuesday.app.catalog.MovieCatalog
import com.movieroulettetuesday.app.catalog.RouletteCategory

object RouletteFixtures {
    /** Four categories, two movies each; m-infamous is infamous and in "d". */
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

    fun withInfamous(): MovieCatalog = catalog.copy(
        movies = catalog.movies.map { if (it.id == "d-2") it.copy(isInfamous = true) else it },
    )
}
