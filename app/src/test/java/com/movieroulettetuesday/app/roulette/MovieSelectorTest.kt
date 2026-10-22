package com.movieroulettetuesday.app.roulette

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MovieSelectorTest {
    private val selector = MovieSelector()
    private val catalog = RouletteFixtures.catalog

    @Test fun candidatesUnionCategoryListAndMovieTagsWithoutDuplicates() {
        val extra = catalog.movies.first().copy(id = "tagged", categories = listOf("a"))
        val c = catalog.copy(movies = catalog.movies + extra)
        val ids = selector.candidates(c.category("a")!!.copy(movieIds = listOf("a-1", "a-1", "ghost")), c).map { it.id }
        assertEquals(listOf("a-1", "a-2", "tagged"), ids)
    }

    @Test fun recencyMultiplierShape() {
        assertEquals(0.1, selector.recencyMultiplier(1), 1e-9)
        assertEquals(0.1 + 0.9 * 4 / 5, selector.recencyMultiplier(5), 1e-9)
        assertEquals(1.0, selector.recencyMultiplier(6), 0.0)
        assertEquals(1.0, selector.recencyMultiplier(null), 0.0)
    }

    @Test fun weights() {
        val m = catalog.movie("a-1")!!
        assertEquals(1.0, selector.weight(m, emptyList()), 0.0)
        val history = listOf(SpinResult(movieId = "a-1", categoryId = "a"), SpinResult(movieId = "b-1", categoryId = "b"))
        assertEquals(selector.recencyMultiplier(2), selector.weight(m, history), 1e-9)
    }

    @Test fun pickIndexExactIntervalsAndEdgeCases() {
        val w = listOf(1.0, 2.0, 1.0)
        assertEquals(0, MovieSelector.pickIndex(w, 0.0))
        assertEquals(0, MovieSelector.pickIndex(w, 0.2499))
        assertEquals(1, MovieSelector.pickIndex(w, 0.25))
        assertEquals(2, MovieSelector.pickIndex(w, 0.75))
        assertEquals(2, MovieSelector.pickIndex(w, 1.0))
        assertNull(MovieSelector.pickIndex(emptyList(), 0.5))
        assertEquals(1, MovieSelector.pickIndex(listOf(0.0, 1.0, -1.0, Double.NaN), 0.99))
        assertEquals(2, MovieSelector.pickIndex(listOf(0.0, 0.0, 0.0), 0.9))
        assertEquals(0, MovieSelector.pickIndex(listOf(1.0, 1.0), Double.NaN))
    }

    @Test fun recentlySpunMovieIsPickedLessOften() {
        val cat = catalog.category("a")!!
        val history = listOf(SpinResult(movieId = "a-1", categoryId = "a"))
        val rnd = SeededRandom(3)
        val picks = (0 until 5000).map { selector.selectMovie(cat, catalog, history, rnd)!!.id }
        val a1 = picks.count { it == "a-1" }
        // Expected share 0.1 / 1.1 ≈ 9 %.
        assertTrue("$a1", a1 in 300..650)
    }

    @Test fun emptyCategoryReturnsNil() {
        val empty = catalog.category("a")!!.copy(id = "zzz", movieIds = emptyList())
        assertNull(selector.selectMovie(empty, catalog, emptyList(), SeededRandom(1)))
    }
}
