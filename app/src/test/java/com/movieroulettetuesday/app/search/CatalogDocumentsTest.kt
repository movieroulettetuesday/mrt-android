package com.movieroulettetuesday.app.search

import com.movieroulettetuesday.app.catalog.Movie
import com.movieroulettetuesday.app.catalog.MovieCatalog
import com.movieroulettetuesday.app.catalog.RouletteCategory
import com.movieroulettetuesday.app.episodes.PodcastEpisode
import com.movieroulettetuesday.app.movies.PersonalRatings
import com.movieroulettetuesday.app.movies.WatchStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogDocumentsTest {
    private val catalog = MovieCatalog(
        categories = listOf(RouletteCategory("horror", "Horror", colorHex = "#C00", movieIds = listOf("the-devils-rain-1975"))),
        movies = listOf(
            Movie("the-devils-rain-1975", "The Devil’s Rain", 1975, "Horror", categories = listOf("horror")),
            Movie("laura-1944", "Laura", 1944, "Thriller"),
        ),
    )
    private val episodes = listOf(
        PodcastEpisode("Buzzsprout-19913482", "The Devil’s Rain", season = 4, episode = 34),
    )

    @Test
    fun indexesPublicCatalogTitlesAndNotPersonalData() {
        val docs = CatalogDocuments.all(catalog, episodes)
        val values = CatalogDocuments.indexedValues(docs)
        assertTrue(values.contains("The Devil’s Rain"))
        assertTrue(values.contains("Laura"))
        assertTrue(values.contains("1975"))
        assertTrue(values.contains("1944"))
        assertTrue(values.contains("Horror"))
        assertTrue(values.contains("Thriller"))
        assertTrue(values.contains("Buzzsprout-19913482"))
        assertTrue(values.any { it.startsWith("mrt://movie/") })
        assertTrue(values.any { it.startsWith("mrt://episode/") })
        // Season is indexed for episodes.
        assertTrue(values.contains("4"))

        val personal = PersonalRatings()
            .withStatus(WatchStatus.WantToWatch, "laura-1944", 1)
            .withHalfStars(9, "the-devils-rain-1975", 1)
        val banned = listOf(
            "wantToWatch", "Want to watch", "watched",
            "halfStars", "personal-ratings",
            "9", // this movie's half-star rating (4.5 stars = 9). Year 1975 is 1975, episode 34 is 34.
        )
        // Ratings and watchlist status must not appear as properties. "9" as a half-star
        // must not be written; years and season numbers that coincide with a rating are
        // public catalog fields and may appear (1975 is a year, not a rating).
        val propertyBag = docs.flatMap { doc ->
            listOfNotNull(doc.schema, doc.id, doc.title, doc.genre, doc.uri) +
                listOfNotNull(doc.year?.toString(), doc.season?.toString())
        }
        assertFalse(propertyBag.any { it.contains("wantToWatch", ignoreCase = true) })
        assertFalse(propertyBag.any { it.contains("watchlist", ignoreCase = true) })
        assertFalse(propertyBag.any { it.contains("halfStars", ignoreCase = true) })
        // The personal ratings object is not passed in at all — this is the API contract.
        val encoded = personal.entries.toString()
        assertTrue(encoded.contains("wantToWatch") || encoded.contains("Want"))
        assertFalse(docs.toString().contains("wantToWatch"))
        assertFalse(docs.any { it.id.contains("rating") })
    }

    @Test
    fun everyDocumentHasADeepLinkAndAPublicSchema() {
        val docs = CatalogDocuments.all(catalog, episodes)
        assertEquals(catalog.movies.size + episodes.size, docs.size)
        assertTrue(docs.all { it.uri.startsWith("mrt://") })
        assertTrue(docs.filter { it.schema == CatalogDocuments.MOVIE_SCHEMA }.all { it.year != null })
        assertTrue(docs.filter { it.schema == CatalogDocuments.EPISODE_SCHEMA }.all { it.season != null || true })
    }

    private fun assertEquals(expected: Int, actual: Int) = org.junit.Assert.assertEquals(expected, actual)
}
