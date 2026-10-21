package com.movieroulettetuesday.app.stats

import com.movieroulettetuesday.app.catalog.CatalogJson
import com.movieroulettetuesday.app.roulette.SpinHistoryStore
import com.movieroulettetuesday.app.roulette.SpinResult
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StatsCalculatorTest {
    private val catalog = CatalogJson.decode(File("src/main/assets/movies.json").readBytes())
    private fun spin(movie: String, cat: String, t: Long) = SpinResult(id = "s$t", movieId = movie, categoryId = cat, timestampEpochMs = t)

    @Test
    fun emptyLogHasNoFavorites() {
        val s = StatsSnapshot.of(SpinHistoryStore.Log(), catalog)
        assertEquals(0, s.totalSpins)
        assertNull(s.mostSpunMovie)
        assertNull(s.mostSpunCategory)
        assertEquals(emptyList<CountedId>(), s.categoryBreakdown)
    }

    @Test
    fun countsArchiveAndDetailWithDeterministicTies() {
        val log = SpinHistoryStore.Log(
            results = listOf(
                spin("laura-1944", "cat-criterion", 1),
                spin("piranha-ii-the-spawning-1982", "cat-horror", 2),
                spin("laura-1944", "cat-criterion", 3),
            ),
            archivedTotal = 2,
            archivedMovieCounts = mapOf("piranha-ii-the-spawning-1982" to 2),
            archivedCategoryCounts = mapOf("cat-cult" to 2),
        )
        val s = StatsSnapshot.of(log, catalog)
        assertEquals(5, s.totalSpins)
        assertEquals(2, s.distinctMovies)
        assertEquals(CountedId("piranha-ii-the-spawning-1982", 3), s.mostSpunMovie)
        // cat-criterion and cat-cult both have 2: the id breaks the tie.
        assertEquals(listOf(CountedId("cat-criterion", 2), CountedId("cat-cult", 2), CountedId("cat-horror", 1)), s.categoryBreakdown)
        assertEquals(listOf(CountedId("Horror", 3), CountedId("Thriller", 2)), s.genreBreakdown)
        assertEquals(listOf("s3", "s2", "s1"), s.recent.map { it.id })
    }

    @Test
    fun recentIsCappedNewestFirst() {
        val log = SpinHistoryStore.Log(results = (1..15L).map { spin("laura-1944", "cat-criterion", it) })
        val s = StatsSnapshot.of(log, catalog, recentLimit = 10)
        assertEquals(10, s.recent.size)
        assertEquals("s15", s.recent.first().id)
        assertEquals("s6", s.recent.last().id)
    }
}
