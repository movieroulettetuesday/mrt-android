package com.movieroulettetuesday.app.movies

import com.movieroulettetuesday.app.catalog.Movie
import com.movieroulettetuesday.app.catalog.StreamingInfo
import com.movieroulettetuesday.app.catalog.StreamingInfo.Provider
import com.movieroulettetuesday.app.roulette.SpinHistoryStore
import com.movieroulettetuesday.app.roulette.SpinResult
import com.movieroulettetuesday.app.roulette.SpinStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingTest {
    private val netflix = Provider(8, "Netflix", "/n.png")
    private val hulu = Provider(15, "Hulu")
    private val roku = Provider(207, "The Roku Channel", "/r.png")
    private val apple = Provider(2, "Apple TV")
    private val m1 = Movie("m1", "Zed", 1990, "Horror", streaming = StreamingInfo(stream = listOf(netflix), rent = listOf(apple)))
    private val m2 = Movie("m2", "Alpha", 2001, "Comedy", streaming = StreamingInfo(free = listOf(roku), stream = listOf(hulu)))
    private val m3 = Movie("m3", "Mid", 1990, "Drama", streaming = StreamingInfo(buy = listOf(apple), rent = listOf(apple)))
    private val m4 = Movie("m4", "Nothing", 1980, "Drama", episodeTitle = "Episode about zed")

    @Test
    fun myServicesSerialization() {
        val s = MyServices.parse(" 9, 8,x,,337")
        assertEquals(setOf(8, 9, 337), s.ids)
        assertEquals("8,9,337", s.serialized)
        assertEquals("9,337", s.toggled(8).serialized)
        assertTrue(MyServices.parse(null).isEmpty)
    }

    @Test
    fun servicesMatchingAndAvailableFirst() {
        val movies = listOf(m1, m2, m3, m4)
        assertEquals(listOf("Hulu", "Netflix", "The Roku Channel"), StreamingCatalog.services(movies).map { it.name })
        val mine = MyServices(setOf(207))
        assertEquals(listOf(roku), StreamingCatalog.matchingServices(m2, mine))
        assertEquals(listOf(m2), StreamingCatalog.filter(movies, mine))
        assertTrue(StreamingCatalog.filter(movies, MyServices()).isEmpty())
        assertEquals(listOf("m2", "m1", "m3", "m4"), StreamingCatalog.availableFirst(movies, mine).map { it.id })
        assertEquals(listOf("m1", "m2", "m3", "m4"), StreamingCatalog.availableFirst(movies, MyServices()).map { it.id })
        assertEquals(listOf(apple), StreamingCatalog.rentOrBuy(m3.streaming!!))
    }

    @Test
    fun whereToWatchDisplay() {
        val withLink = Movie("x", "X", 2000, "G", tmdbId = 5, streaming = StreamingInfo(updated = "2026-10-13", link = "https://www.themoviedb.org/movie/5/watch?locale=US", stream = listOf(netflix)))
        val d = WhereToWatchDisplay.of(withLink)
        assertTrue(d.showsProviders)
        assertEquals("https://www.themoviedb.org/movie/5/watch?locale=US", d.link)
        assertEquals("Streaming data by JustWatch via TMDB · updated Oct 13", d.footer)
        val tmdbOnly = WhereToWatchDisplay.of(Movie("y", "Y", 2000, "G", tmdbId = 77))
        assertFalse(tmdbOnly.showsProviders)
        assertEquals("https://www.themoviedb.org/movie/77/watch?locale=US", tmdbOnly.link)
        assertEquals(StreamingCatalog.ATTRIBUTION, tmdbOnly.footer)
        val bare = WhereToWatchDisplay.of(Movie("z", "Z", 2000, "G"))
        assertNull(bare.link)
        assertNull(bare.footer)
        assertTrue(bare.showsEmptyMessage)
        assertNull(StreamingCatalog.updatedLabel("13/10/2026"))
        assertEquals("https://image.tmdb.org/t/p/w92/n.png", netflix.logoURL)
    }

    @Test
    fun movieQuerySearchFilterSort() {
        val movies = listOf(m1, m2, m3, m4)
        val stats = SpinStats(SpinHistoryStore.Log(results = listOf(SpinResult(movieId = "m3", categoryId = "c"), SpinResult(movieId = "m3", categoryId = "c"), SpinResult(movieId = "m1", categoryId = "c"))))
        val r = PersonalRatings()
        fun ids(q: MovieQuery, s: MyServices = MyServices()) = q.apply(movies, r, stats, s).map { it.id }
        assertEquals(listOf("m2", "m3", "m4", "m1"), ids(MovieQuery()))
        assertEquals("title, genre and episode title searched", listOf("m4", "m1"), ids(MovieQuery(text = "ZED")))
        assertEquals(listOf("m2", "m3", "m1", "m4"), ids(MovieQuery(sort = MovieSort.YearNewest)))
        assertEquals(listOf("m4", "m3", "m1", "m2"), ids(MovieQuery(sort = MovieSort.YearOldest)))
        assertEquals(listOf("m3", "m1", "m2", "m4"), ids(MovieQuery(sort = MovieSort.Spins)))
        assertEquals(listOf("m2"), ids(MovieQuery(onMyServices = true), MyServices(setOf(15))))
        assertEquals(listOf("m4", "m2"), ids(MovieQuery(listFilter = MovieListFilter.NotSpunYet, sort = MovieSort.YearOldest)))
        assertTrue(MovieQuery().isPlain)
        assertFalse(MovieQuery(text = "a").isPlain)
    }
}
