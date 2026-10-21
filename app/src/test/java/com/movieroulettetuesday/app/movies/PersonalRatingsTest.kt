package com.movieroulettetuesday.app.movies

import com.movieroulettetuesday.app.catalog.Movie
import com.movieroulettetuesday.app.catalog.MovieCatalog
import com.movieroulettetuesday.app.roulette.SpinHistoryStore
import com.movieroulettetuesday.app.roulette.SpinResult
import com.movieroulettetuesday.app.roulette.SpinStats
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ports of PersonalRatingsTests.swift / HalfStarMappingTests. */
class PersonalRatingsTest {
    private val catalog = MovieCatalog(categories = emptyList(), movies = listOf(
        Movie("a", "Alpha", 2000, "G"), Movie("b", "Bravo", 2001, "G", hostRating = 4.0), Movie("c", "Charlie", 2002, "G"),
    ))

    @Test
    fun ratingClampsAndImpliesWatched() {
        var r = PersonalRatings().withHalfStars(18, "a", 1)
        assertEquals(10, r.halfStars("a"))
        assertEquals(5, r.rating("a"))
        assertEquals(WatchStatus.Watched, r.status("a"))
        r = r.withStatus(WatchStatus.WantToWatch, "b", 2)
        assertEquals(setOf("b"), r.watchlist)
        r = r.withHalfStars(6, "b", 3)
        assertEquals(WatchStatus.Watched, r.status("b"))
        r = r.withHalfStars(0, "a", 4)
        assertNull(r.halfStars("a"))
        assertEquals("status kept", WatchStatus.Watched, r.status("a"))
        r = r.withStatus(null, "a", 5)
        assertNull("empty entries pruned", r.entry("a"))
    }

    @Test
    fun halfStarMapping() {
        val w = 100.0
        assertEquals(listOf(1, 1, 2, 7, 8, 10, 1, 1),
            listOf(0.0, 5.0, 15.0, 65.0, 75.0, 250.0, -30.0).map { HalfStars.fromLocation(it, w) } + HalfStars.fromLocation(50.0, 0.0))
        assertEquals(listOf(1.0, 1.0, 1.0, 0.5, 0.0), (1..5).map { HalfStars.fill(it, 7) })
        assertEquals("3.5", HalfStars.label(7))
        assertEquals("4", HalfStars.label(8))
        assertEquals("4.5", HalfStars.label(4.26))
        assertEquals("4", HalfStars.label(4.24))
        assertEquals(3.5, HalfStars.roundedToHalf(3.74), 0.0)
        assertEquals(4.0, HalfStars.roundedToHalf(3.75), 0.0)
        assertEquals(listOf(1, 1, 2, 2, 3, 3, 4, 4, 5, 5), (1..10).map(HalfStars::roundedStars))
        assertNull(HalfStars.clamped(0))
        assertEquals(10, HalfStars.clamped(14))
        assertEquals(6, HalfStars.fromLegacyStars(3))
        assertEquals(10, HalfStars.fromLegacyStars(9))
        assertEquals(7, HalfStars.resolved(7, 4))
        assertEquals(8, HalfStars.resolved(null, 4))
        assertEquals(1, HalfStars.adjusted(null, true))
        assertEquals(10, HalfStars.adjusted(10, true))
        assertEquals(6, HalfStars.adjusted(7, false))
        assertNull(HalfStars.adjusted(1, false))
    }

    @Test
    fun legacyAndTolerantDecoding() {
        val legacy = """{"entries":{"a":{"rating":4,"status":"watched","updatedAt":0},"b":{"status":"wantToWatch","updatedAt":0},
            "c":{"status":"mystery","rating":"x"},"d":"junk","e":{"halfStars":7.5}}}"""
        var r = PersonalRatings.decode(legacy)
        assertEquals(8, r.halfStars("a"))
        assertNull(r.halfStars("b"))
        assertEquals(WatchStatus.WantToWatch, r.status("b"))
        assertNull("unknown status / bad rating -> empty -> dropped", r.entry("c"))
        assertNull(r.entry("d"))
        assertNull("non-integer halfStars rejected", r.entry("e"))
        r = r.withHalfStars(7, "a", 5)
        val encoded = PersonalRatings.encode(r)
        assertTrue(encoded.contains("\"a\":{\"halfStars\":7,\"rating\":4,\"status\":\"watched\",\"updatedAt\":5}"))
        assertEquals(r, PersonalRatings.decode(encoded))
    }

    @Test
    fun storePersistsAndQuarantinesCorruptFile() {
        val dir = Files.createTempDirectory("pr").toFile()
        val file = File(dir, PersonalRatingsStore.FILE_NAME)
        val store = PersonalRatingsStore(file) { 42 }
        store.setHalfStars(7, "a")
        store.setStatus(WatchStatus.WantToWatch, "c")
        assertEquals(store.ratings.value, PersonalRatingsStore(file).ratings.value)

        file.writeText("{broken")
        val recovered = PersonalRatingsStore(file) { 99 }
        assertTrue(recovered.ratings.value.entries.isEmpty())
        assertNotNull(recovered.lastError)
        assertEquals("{broken", File(dir, "${PersonalRatingsStore.FILE_NAME}.corrupt-99").readText())
    }

    @Test
    fun summaryAndListFilters() {
        val r = PersonalRatings().withHalfStars(10, "c", 1).withHalfStars(4, "b", 1).withHalfStars(10, "a", 1)
            .withStatus(WatchStatus.WantToWatch, "zzz-gone", 1)
        val s = PersonalRatingsSummary.of(r, catalog, topLimit = 2)
        assertEquals(3, s.ratedCount)
        assertEquals(4.0, s.averageStars!!, 1e-12)
        assertEquals(listOf("Alpha", "Charlie"), s.topRated.map { it.title })
        assertEquals(1, s.watchlistCount)
        assertEquals(3, s.watchedCount)
        assertNull(PersonalRatingsSummary.of(PersonalRatings(), catalog).averageStars)

        val r2 = PersonalRatings().withStatus(WatchStatus.WantToWatch, "a", 1).withHalfStars(8, "b", 1)
        val stats = SpinStats(SpinHistoryStore.Log(results = listOf(SpinResult(movieId = "b", categoryId = "x"), SpinResult(movieId = "c", categoryId = "x"))))
        val m = catalog.movies
        assertEquals(listOf("a", "b", "c"), MovieListFilter.All.apply(m, r2, stats).map { it.id })
        assertEquals(listOf("a"), MovieListFilter.Watchlist.apply(m, r2, stats).map { it.id })
        assertEquals(listOf("b"), MovieListFilter.Watched.apply(m, r2, stats).map { it.id })
        assertEquals(listOf("b"), MovieListFilter.Rated.apply(m, r2, stats).map { it.id })
        assertEquals(listOf("a"), MovieListFilter.NotSpunYet.apply(m, r2, stats).map { it.id })
    }
}
