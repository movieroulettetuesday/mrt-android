package com.movieroulettetuesday.app.search

import com.movieroulettetuesday.app.catalog.Movie
import com.movieroulettetuesday.app.catalog.MovieCatalog
import com.movieroulettetuesday.app.catalog.RouletteCategory
import com.movieroulettetuesday.app.catalog.StreamingInfo
import com.movieroulettetuesday.app.episodes.PodcastEpisode
import com.movieroulettetuesday.app.search.CatalogSearch.Rank
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shared 1.1 matcher. Mirrors iOS `CatalogSearchTests` (movie-roulette-tuesday PR #32) case for
 * case on the same fixture, plus Android-only checks for normalization, tokens and tie-breaking.
 */
class CatalogSearchTest {
    private val catalog = makeCatalog()
    private val episodes = makeEpisodes()
    private val byGUID = PodcastEpisode.byGUID(episodes)
    private val utc = ZoneOffset.UTC

    private fun movieHits(q: String) = CatalogSearch.movies(catalog.movies, q, catalog, byGUID)
    private fun movieIDs(q: String) = movieHits(q).map { it.movie.id }
    private fun movieRanks(q: String) = movieHits(q).associate { it.movie.id to it.rank }
    private fun episodeIDs(q: String) = CatalogSearch.episodes(episodes, q, catalog, utc).map { it.episode.guid }

    // iOS CatalogSearchTests, mirrored

    @Test fun foldHandlesCaseAccentsApostrophesAndPunctuation() {
        assertEquals("the devils rain", CatalogSearch.fold("The Devil’s Rain"))
        assertEquals("the devils rain", CatalogSearch.fold("THE DEVIL'S RAIN"))
        assertEquals("amelie", CatalogSearch.fold("Amélie"))
        assertEquals("spider man far", CatalogSearch.fold("  Spider-Man:  Far…  "))
        assertEquals("π", CatalogSearch.fold("π"))
    }

    @Test fun caseInsensitive() {
        assertEquals(listOf("the-devils-rain-1975"), movieIDs("DEVILS RAIN"))
        assertEquals(listOf("the-devils-rain-1975"), movieIDs("devils rain"))
        assertEquals(Rank.ExactTitle, movieRanks("devils rain")["the-devils-rain-1975"])
    }

    @Test fun accentsFoldBothWays() {
        assertEquals(listOf("amelie-2001"), movieIDs("amelie"))
        assertEquals(listOf("amelie-2001"), movieIDs("AMÉLIE"))
    }

    @Test fun straightAndCurlyApostrophes() {
        assertEquals(listOf("the-devils-rain-1975"), movieIDs("devil's rain"))
        assertEquals(listOf("the-devils-rain-1975"), movieIDs("devil’s rain"))
        assertEquals(listOf("pinocchio-2022"), movieIDs("del toros pinocchio"))
        assertEquals(listOf("pinocchio-2022"), movieIDs("del toro's pinocchio"))
    }

    @Test fun leadingTheIsIgnored() {
        assertEquals(listOf("the-thing-1982", "the-thing-2011"), movieIDs("thing"))
        assertEquals(listOf("the-thing-1982", "the-thing-2011"), movieIDs("The Thing"))
        assertEquals(Rank.ExactTitle, movieRanks("thing")["the-thing-1982"])
        assertEquals(Rank.TitleWords, movieRanks("a space odyssey")["2001-a-space-odyssey-1968"])
    }

    @Test fun partialMatches() {
        assertEquals(Rank.TitlePrefix, movieRanks("devil")["the-devils-rain-1975"])
        assertEquals(Rank.TitleWords, movieRanks("rain devils")["the-devils-rain-1975"])
        assertEquals(listOf("the-thing-1982", "the-thing-2011"), movieIDs("thi"))
    }

    @Test fun yearDisambiguatesAndYearOnlyQueries() {
        assertEquals(listOf("the-thing-1982"), movieIDs("The Thing 1982"))
        assertEquals(listOf("the-thing-1982"), movieIDs("the thing 1982"))
        assertEquals(listOf("the-thing-2011"), movieIDs("thing 2011"))
        assertEquals(listOf("the-thing-1982"), movieIDs("1982"))
        assertEquals(Rank.Year, movieRanks("1982")["the-thing-1982"])
        // A year that is also in a title: the title match ranks first.
        assertEquals(listOf("2001-a-space-odyssey-1968", "amelie-2001"), movieIDs("2001"))
        assertEquals(Rank.TitlePrefix, movieRanks("2001")["2001-a-space-odyssey-1968"])
        assertEquals(Rank.Year, movieRanks("2001")["amelie-2001"])
    }

    @Test fun seasonEpisodeForms() {
        for (form in listOf("S4E39", "s4e39", "s4 e39", "4x39", "season 4 episode 39")) {
            assertEquals(form, listOf("the-devils-rain-1975"), movieIDs(form))
            assertEquals(form, listOf("Buzzsprout-20099500"), episodeIDs(form))
        }
        assertEquals(Rank.Episode, movieRanks("S4E39")["the-devils-rain-1975"])
        assertEquals(listOf("Buzzsprout-3"), episodeIDs("episode 40"))
        assertEquals(listOf("Buzzsprout-2"), episodeIDs("ep 38"))
        assertEquals(listOf("Buzzsprout-2"), episodeIDs("e38"))
        assertEquals(listOf("Buzzsprout-20099500"), episodeIDs("S4E39 rain"))
        assertTrue(episodeIDs("S9E99").isEmpty())
        assertTrue(movieIDs("S9E99").isEmpty())
    }

    @Test fun providerIsASecondaryMatch() {
        val hits = movieHits("shudder")
        assertEquals(listOf("the-shudder-1990", "the-devils-rain-1975"), hits.map { it.movie.id })
        assertEquals(listOf(Rank.ExactTitle, Rank.Provider), hits.map { it.rank })
        assertTrue(Rank.TitleWords < Rank.Episode)
        assertTrue(Rank.Episode < Rank.Year)
        assertTrue(Rank.Year < Rank.GenreOrCategory)
        assertTrue(Rank.GenreOrCategory < Rank.Provider)
        // Rent / buy lists are not searched (too noisy).
        assertTrue(movieIDs("vudu").isEmpty())
    }

    @Test fun genreCategoryAndEpisodeTitleMatches() {
        assertEquals(Rank.GenreOrCategory, movieRanks("horror")["the-thing-1982"])
        assertEquals(Rank.GenreOrCategory, movieRanks("Cult Classics")["the-devils-rain-1975"])
        assertEquals(Rank.Episode, movieRanks("puppet masters")["pinocchio-2022"])
    }

    @Test fun episodesMatchTitleLinkedMovieGenreAndProvider() {
        val hits = CatalogSearch.episodes(episodes, "devils rain", catalog, utc)
        assertEquals(listOf("Buzzsprout-20099500"), hits.map { it.episode.guid })
        assertEquals(Rank.ExactTitle, hits.first().rank)
        val linked = CatalogSearch.episodes(episodes, "guillermo", catalog, utc)
        assertEquals(listOf("Buzzsprout-2"), linked.map { it.episode.guid })
        assertEquals(Rank.Episode, linked.first().rank)
        assertEquals(listOf("Buzzsprout-20099500"), episodeIDs("horror"))
        assertEquals(listOf("Buzzsprout-20099500"), episodeIDs("shudder"))
        assertEquals("newest first within a rank", listOf("Buzzsprout-3", "Buzzsprout-20099500", "Buzzsprout-2"), episodeIDs("2025"))
    }

    @Test fun noMatchAndUnicodeTitles() {
        assertTrue(movieIDs("zzzz-not-a-movie").isEmpty())
        assertTrue(episodeIDs("zzzz-not-an-episode").isEmpty())
        assertTrue(movieIDs("zzqx").isEmpty())
        assertTrue(episodeIDs("zzqx").isEmpty())
        assertEquals(listOf("pi-1998"), movieIDs("π"))
        assertFalse("Unicode folding does not turn pi into π", "pi-1998" in movieIDs("pi"))
    }

    @Test fun hostsAreNotSearched() {
        assertEquals(listOf("Rachael", "Karl", "Matt"), catalog.showInfo.hosts)
        assertTrue(movieIDs("karl").isEmpty())
        assertTrue(episodeIDs("rachael").isEmpty())
        // Show notes ("Karl and Rachael talk Satanists.") are not searched either.
        assertTrue(episodeIDs("satanists").isEmpty())
    }

    @Test fun emptyQueryReturnsNothing() {
        for (q in listOf("", "   ", "!!!", "’")) {
            assertTrue("movies for '$q'", movieIDs(q).isEmpty())
            assertTrue("episodes for '$q'", episodeIDs(q).isEmpty())
            assertTrue(q, CatalogSearch.Query.parse(q).isEmpty)
        }
    }

    // Android additions

    @Test fun foldEdgeCases() {
        assertEquals("", CatalogSearch.fold(""))
        assertEquals("", CatalogSearch.fold(" ’‘ʼ`´' "))
        assertEquals("rock n roll", CatalogSearch.fold("Rock ’n’ Roll"))
        assertEquals("wall e", CatalogSearch.fold("WALL·E"))
        assertEquals("creme brulee", CatalogSearch.fold("Crème Brûlée"))
        assertEquals("se7en", CatalogSearch.fold("Se7en"))
        assertEquals("a b", CatalogSearch.fold("a\t\n—b"))
        assertEquals("naive", CatalogSearch.fold("Nai\u0308ve"))
    }

    @Test fun articleDropKeepsALoneArticle() {
        assertEquals("thing", CatalogSearch.droppingArticle("the thing"))
        assertEquals("space odyssey", CatalogSearch.droppingArticle("a space odyssey"))
        assertEquals("american werewolf", CatalogSearch.droppingArticle("an american werewolf"))
        assertEquals("the", CatalogSearch.droppingArticle("the"))
        assertEquals("theater", CatalogSearch.droppingArticle("theater"))
        assertEquals("the thing", CatalogSearch.droppingArticle("the the thing"))
    }

    @Test fun queryTokens() {
        fun p(s: String) = CatalogSearch.Query.parse(s)
        p("S4E39").let { assertEquals(4, it.season); assertEquals(39, it.episode); assertEquals("", it.text) }
        p("4x39").let { assertEquals(4, it.season); assertEquals(39, it.episode) }
        p("s4 e39").let { assertEquals(4, it.season); assertEquals(39, it.episode) }
        p("Season 4, Episode 39").let { assertEquals(4, it.season); assertEquals(39, it.episode); assertEquals("", it.text) }
        p("season 4").let { assertEquals(4, it.season); assertNull(it.episode) }
        p("ep39").let { assertNull(it.season); assertEquals(39, it.episode) }
        p("ep 39").let { assertNull(it.season); assertEquals(39, it.episode) }
        p("episode 39").let { assertNull(it.season); assertEquals(39, it.episode) }
        p("e39").let { assertNull(it.season); assertEquals(39, it.episode) }
        // Not tokens: words that only look like them stay text.
        p("episode").let { assertNull(it.episode); assertEquals("episode", it.text) }
        p("season finale").let { assertNull(it.season); assertEquals("season finale", it.text) }
        p("e").let { assertNull(it.episode); assertEquals("e", it.text) }
        p("s4e").let { assertNull(it.season); assertEquals("s4e", it.text) }
        p("s1234e1").let { assertNull(it.season); assertEquals("s1234e1", it.text) }
        p("e12345").let { assertNull(it.episode); assertEquals("e12345", it.text) }
        // Year: first 4-digit number in 1880..2100; stays in textWithYear.
        p("The Thing 1982").let { assertEquals(1982, it.year); assertEquals("thing", it.text); assertEquals("thing 1982", it.textWithYear) }
        p("1879").let { assertNull(it.year); assertEquals("1879", it.text) }
        p("2101").let { assertNull(it.year) }
        p("1880 2100").let { assertEquals(1880, it.year); assertEquals("2100", it.text); assertEquals("1880 2100", it.textWithYear) }
        p("blade runner 2049").let { assertEquals(2049, it.year); assertEquals("blade runner", it.text); assertEquals("blade runner 2049", it.textWithYear) }
        p("ocean's 11").let { assertEquals("oceans 11", it.text) }
    }

    @Test fun movieTiesBreakByFoldedTitleThenYearThenId() {
        val twins = MovieCatalog(
            categories = emptyList(),
            movies = listOf(
                Movie("z-dune-2021", "Dune", 2021, "Sci-Fi"),
                Movie("dune-1984-b", "Dune", 1984, "Sci-Fi"),
                Movie("dune-1984-a", "Dune", 1984, "Sci-Fi"),
                Movie("dune-part-two-2024", "Dune: Part Two", 2024, "Sci-Fi"),
                Movie("dunes-1990", "Dunes", 1990, "Sci-Fi"),
            ),
        )
        val hits = CatalogSearch.movies(twins.movies, "dune", twins)
        assertEquals(listOf("dune-1984-a", "dune-1984-b", "z-dune-2021", "dune-part-two-2024", "dunes-1990"), hits.map { it.movie.id })
        assertEquals(listOf(Rank.ExactTitle, Rank.ExactTitle, Rank.ExactTitle, Rank.TitlePrefix, Rank.TitlePrefix), hits.map { it.rank })
    }

    @Test fun episodeTiesBreakNewestFirstThenTitleAndUndatedLast() {
        val eps = listOf(
            PodcastEpisode("b", "Bonus B", pubDate = day("2025-01-01")),
            PodcastEpisode("a", "Bonus A", pubDate = day("2025-01-01")),
            PodcastEpisode("n", "Bonus New", pubDate = day("2025-06-01")),
            PodcastEpisode("u", "Bonus Undated"),
        )
        assertEquals(listOf("n", "a", "b", "u"), CatalogSearch.episodes(eps, "bonus", null, utc).map { it.episode.guid })
    }

    @Test fun episodeYearUsesPublishYearOrTheMovieYear() {
        // Buzzsprout-20099500 is published in 2025 and covers a 1975 movie.
        assertEquals(listOf("Buzzsprout-20099500"), episodeIDs("1975"))
        assertEquals(listOf("Buzzsprout-20099500"), episodeIDs("devils rain 2025"))
        assertTrue(episodeIDs("devils rain 1990").isEmpty())
    }

    @Test fun freeListCountsAsProvider() {
        val tubi = StreamingInfo.Provider(73, "Tubi")
        val m = Movie("x-1999", "X", 1999, "Drama", streaming = StreamingInfo(free = listOf(tubi), buy = listOf(StreamingInfo.Provider(2, "Apple TV"))))
        val cat = MovieCatalog(categories = emptyList(), movies = listOf(m))
        assertEquals(listOf(Rank.Provider), CatalogSearch.movies(cat.movies, "tubi", cat).map { it.rank })
        assertTrue(CatalogSearch.movies(cat.movies, "apple tv", cat).isEmpty())
    }

    companion object {
        fun day(text: String): Long = Instant.parse(text + "T12:00:00Z").toEpochMilli()

        fun makeCatalog(): MovieCatalog {
            val horror = RouletteCategory(
                id = "cat-horror", name = "Horror", colorHex = "#C25B5E",
                movieIds = listOf("the-devils-rain-1975", "the-thing-1982", "the-thing-2011", "the-shudder-1990"),
            )
            val cult = RouletteCategory(id = "cat-cult", name = "Cult", fullName = "Cult Classics", colorHex = "#8670AE",
                movieIds = listOf("the-devils-rain-1975"))
            val other = RouletteCategory(id = "cat-other", name = "Other", colorHex = "#5E8C6A",
                movieIds = listOf("pinocchio-2022", "amelie-2001", "2001-a-space-odyssey-1968", "pi-1998"))
            val shudder = StreamingInfo.Provider(99, "Shudder")
            val netflix = StreamingInfo.Provider(8, "Netflix")
            val vudu = StreamingInfo.Provider(7, "Vudu")
            val movies = listOf(
                Movie("the-devils-rain-1975", "The Devil's Rain", 1975, "Horror", listOf("cat-horror", "cat-cult"),
                    episodeIds = listOf("Buzzsprout-20099500"), episodeTitle = "The Devil's Rain",
                    streaming = StreamingInfo(stream = listOf(shudder, netflix), rent = listOf(vudu))),
                Movie("the-thing-1982", "The Thing", 1982, "Horror", listOf("cat-horror")),
                Movie("the-thing-2011", "The Thing", 2011, "Horror", listOf("cat-horror")),
                Movie("the-shudder-1990", "The Shudder", 1990, "Horror", listOf("cat-horror")),
                Movie("pinocchio-2022", "Guillermo del Toro’s Pinocchio", 2022, "Animation", listOf("cat-other"),
                    episodeIds = listOf("Buzzsprout-2"), streaming = StreamingInfo(stream = listOf(netflix))),
                Movie("amelie-2001", "Amélie", 2001, "Romance", listOf("cat-other")),
                Movie("2001-a-space-odyssey-1968", "2001: A Space Odyssey", 1968, "Science Fiction", listOf("cat-other")),
                Movie("pi-1998", "π", 1998, "Thriller", listOf("cat-other")),
            )
            return MovieCatalog(categories = listOf(horror, cult, other), movies = movies)
        }

        fun makeEpisodes(): List<PodcastEpisode> = listOf(
            PodcastEpisode("Buzzsprout-20099500", "The Devil's Rain", pubDate = day("2025-11-04"), season = 4, episode = 39,
                summary = "Karl and Rachael talk Satanists."),
            PodcastEpisode("Buzzsprout-2", "Pinocchio and the Puppet Masters", pubDate = day("2025-10-28"), season = 4, episode = 38),
            PodcastEpisode("Buzzsprout-3", "Bonus: Listener Mailbag", pubDate = day("2025-11-11"), season = 4, episode = 40),
        )
    }
}
