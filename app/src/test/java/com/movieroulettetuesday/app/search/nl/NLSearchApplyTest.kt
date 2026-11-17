package com.movieroulettetuesday.app.search.nl

import com.movieroulettetuesday.app.episodes.PodcastEpisode
import com.movieroulettetuesday.app.movies.MovieListFilter
import com.movieroulettetuesday.app.movies.MovieQuery
import com.movieroulettetuesday.app.movies.MovieSort
import com.movieroulettetuesday.app.movies.MyServices
import com.movieroulettetuesday.app.movies.PersonalMovieEntry
import com.movieroulettetuesday.app.movies.PersonalRatings
import com.movieroulettetuesday.app.movies.WatchStatus
import com.movieroulettetuesday.app.roulette.SpinHistoryStore
import com.movieroulettetuesday.app.roulette.SpinStats
import com.movieroulettetuesday.app.search.CatalogSearch
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Deterministic apply against the bundled 184-movie catalog (exact results, no model). */
class NLSearchApplyTest {
    private val catalog = NLFixtures.catalog
    private val movies = catalog.movies
    private val none = PersonalRatings()
    private val noServices = MyServices()

    private fun ids(f: NLSearchFilter, ratings: PersonalRatings = none, services: MyServices = noServices) =
        NLSearchApply.movies(f, movies, ratings, services, catalog).movies.map { it.id }

    private fun ratings(vararg e: Pair<String, PersonalMovieEntry>) = PersonalRatings(mapOf(*e))

    @Test
    fun free80sHorrorUnwatchedIsTheHardFilteredSetWithoutCatalogSearch() {
        val f = NLSearchFilter(genre = "Horror", yearMin = 1980, yearMax = 1989, freeOnly = true, watched = NLSearchFilter.Watched.Unwatched)
        val expected = listOf(
            "christmas-evil-1980", "deadly-friend-1986", "deadly-games-1989", "fright-night-1985", "harlequin-1980", "leviathan-1989",
            "piranha-ii-the-spawning-1982", "possession-1981", "the-hitcher-1986",
        )
        assertEquals(expected, ids(f).sorted())
        assertEquals(null, NLSearchApply.movies(f, movies, none, noServices, catalog).ranks)
        // Watched one: it drops out; a watchlist entry is still "unwatched".
        val r = ratings("possession-1981" to PersonalMovieEntry(status = WatchStatus.Watched), "leviathan-1989" to PersonalMovieEntry(status = WatchStatus.WantToWatch))
        assertEquals(expected - "possession-1981", ids(f, r).sorted())
    }

    @Test
    fun titleAndGenreAnd() {
        // CatalogSearch alone can't AND a title with a genre: "thing" hits Poor Things (Comedy) too.
        assertEquals(setOf("poor-things-2023", "the-thing-1982"), CatalogSearch.movies(movies, "thing", catalog).map { it.movie.id }.filter { "thing" in it }.toSet())
        val r = NLSearchApply.movies(NLSearchFilter(title = "thing", genre = "Horror"), movies, none, noServices, catalog)
        assertEquals(listOf("the-thing-1982"), r.movies.map { it.id })
        assertEquals(CatalogSearch.Rank.ExactTitle, r.ranks!!["the-thing-1982"])
    }

    @Test
    fun yearRangeIsInclusive() {
        assertEquals(movies.filter { it.year in 1980..1989 }.map { it.id }, ids(NLSearchFilter(yearMin = 1980, yearMax = 1989)))
        assertEquals(movies.filter { it.year >= 2020 }.map { it.id }, ids(NLSearchFilter(yearMin = 2020)))
        assertEquals(movies.filter { it.year <= 1950 }.map { it.id }, ids(NLSearchFilter(yearMax = 1950)))
    }

    @Test
    fun freeOnlyAndProviderUseStreamAndFreeListsOnly() {
        assertEquals(96, ids(NLSearchFilter(freeOnly = true)).size)
        val shudder = ids(NLSearchFilter(provider = "Shudder"))
        assertEquals(8, shudder.size)
        // Channel variants count: The Texas Chain Saw Massacre is only on "Shudder Amazon Channel".
        assertTrue("the-texas-chainsaw-massacre-1974" in shudder)
        // Rent/buy names never match.
        assertEquals(emptyList<String>(), ids(NLSearchFilter(provider = "Vudu")))
        // freeOnly + provider: the name must be in the free list.
        assertEquals(43, ids(NLSearchFilter(provider = "Tubi", freeOnly = true)).size)
        assertEquals(emptyList<String>(), ids(NLSearchFilter(provider = "Netflix", freeOnly = true)).filter { id ->
            catalog.movie(id)!!.streaming!!.free.none { CatalogSearch.fold(it.name).contains("netflix") }
        })
    }

    @Test
    fun multiWordProviderMatchedOnEveryWordReturnsResults() {
        val got = ids(NLSearchFilter(provider = "Shudder Channel"))
        val channelVariants = movies.filter { m ->
            m.streaming?.let { it.stream + it.free }.orEmpty().any { it.name == "Shudder Amazon Channel" || it.name == "Shudder Apple TV channel" }
        }.map { it.id }
        assertEquals(channelVariants, got)
        assertTrue(got.size >= 4)
        assertTrue("the-texas-chainsaw-massacre-1974" in got)
    }

    @Test
    fun anyProviderTheValidatorKeepsMatchesInApply() {
        val queries = NLFixtures.providerNames + listOf(
            "Shudder", "Shudder Channel", "Prime Amazon", "Max", "Disney+", "Tubi", "Pluto", "Netflix Ads", "Roku", "Criterion",
            "Blockbuster", "Vudu", "HBO Go", "x", "Netflix a",
        )
        for (q in queries) {
            val kept = (NLSearchValidator.parse("""{"provider":${kotlinx.serialization.json.JsonPrimitive(q)}}""", NLFixtures.providerNames) as? NLParse.Filter)
                ?.filter?.provider ?: continue
            assertTrue("validator kept $q but apply is empty", ids(NLSearchFilter(provider = kept)).isNotEmpty())
        }
    }

    @Test
    fun unratedFalseWithMinKeepsOnlyRatedMoviesInRange() {
        val r = ratings("the-thing-1982" to PersonalMovieEntry(halfStars = 8), "possession-1981" to PersonalMovieEntry(halfStars = 5))
        assertEquals(listOf("the-thing-1982"), ids(NLSearchFilter(ratingMin = 4.0, unrated = false), r))
    }

    @Test
    fun ratingsUseHalfStars() {
        val r = ratings(
            "the-thing-1982" to PersonalMovieEntry(halfStars = 8),
            "possession-1981" to PersonalMovieEntry(halfStars = 7),
            "fright-night-1985" to PersonalMovieEntry(halfStars = 10),
            "harlequin-1980" to PersonalMovieEntry(status = WatchStatus.Watched),
        )
        assertEquals(setOf("the-thing-1982", "fright-night-1985"), ids(NLSearchFilter(ratingMin = 4.0), r).toSet())
        assertEquals(setOf("possession-1981", "the-thing-1982"), ids(NLSearchFilter(ratingMin = 3.5, ratingMax = 4.0), r).toSet())
        assertEquals(setOf("the-thing-1982", "possession-1981", "fright-night-1985"), ids(NLSearchFilter(unrated = false), r).toSet())
        assertEquals(movies.size - 3, ids(NLSearchFilter(unrated = true), r).size)
    }

    @Test
    fun watchedUnwatchedWatchlist() {
        val r = ratings(
            "the-thing-1982" to PersonalMovieEntry(status = WatchStatus.Watched),
            "possession-1981" to PersonalMovieEntry(status = WatchStatus.WantToWatch),
        )
        assertEquals(listOf("the-thing-1982"), ids(NLSearchFilter(watched = NLSearchFilter.Watched.Watched), r))
        assertEquals(listOf("possession-1981"), ids(NLSearchFilter(watched = NLSearchFilter.Watched.Watchlist), r))
        assertEquals(movies.size - 1, ids(NLSearchFilter(watched = NLSearchFilter.Watched.Unwatched), r).size)
    }

    @Test
    fun categoryByNameAndOnMyServices() {
        assertEquals(movies.filter { "cat-criterion" in it.categories }.map { it.id }, ids(NLSearchFilter(category = "Criterion")))
        assertEquals(movies.filter { "cat-listener" in it.categories }.map { it.id }, ids(NLSearchFilter(category = "Listeners")))
        val shudderId = movies.first { it.id == "possession-1981" }.streaming!!.stream.first { it.name == "Shudder" }.id
        val services = MyServices(setOf(shudderId))
        assertEquals(7, ids(NLSearchFilter(onMyServices = true), services = services).size)
        assertEquals(emptyList<String>(), ids(NLSearchFilter(onMyServices = true)))
    }

    @Test
    fun hostsAndShowNotesNeverMatch() {
        assertEquals(emptyList<String>(), ids(NLSearchFilter(title = "karl")))
        assertEquals(emptyList<String>(), ids(NLSearchFilter(title = "rachael")))
    }

    @Test
    fun chipRemovalChangesTheSet() {
        val f = NLSearchFilter(genre = "Horror", freeOnly = true)
        val before = ids(f)
        val after = ids(f.removing(NLChip.Field.Free))
        assertEquals(27, before.size)
        assertEquals(44, after.size)
        assertTrue(after.containsAll(before))
    }

    @Test
    fun chipsAndWithToolbarFiltersWithoutResettingThem() {
        val stats = SpinStats(SpinHistoryStore.Log())
        val r = ratings("possession-1981" to PersonalMovieEntry(status = WatchStatus.WantToWatch), "the-thing-1982" to PersonalMovieEntry(status = WatchStatus.WantToWatch))
        val chips = NLSearchFilter(genre = "Horror", yearMin = 1980, yearMax = 1989)
        val q = MovieQuery("80s horror", categoryId = "cat-cult", listFilter = MovieListFilter.Watchlist, sort = MovieSort.Title, nl = chips)
        assertEquals(listOf("possession-1981"), q.apply(movies, r, stats, noServices, catalog).map { it.id })
        // Toolbar state is untouched by the chips.
        assertEquals("cat-cult", q.categoryId)
        assertEquals(MovieListFilter.Watchlist, q.listFilter)
        // Empty chips: plain keyword CatalogSearch on the raw text, exactly as before.
        val keyword = MovieQuery("possession", nl = NLSearchFilter.EMPTY).apply(movies, none, stats, noServices, catalog)
        assertEquals(MovieQuery("possession").apply(movies, none, stats, noServices, catalog), keyword)
    }

    @Test
    fun noTitleFiltersFollowTheListSort() {
        val stats = SpinStats(SpinHistoryStore.Log())
        val chips = NLSearchFilter(genre = "Horror", freeOnly = true)
        val newest = MovieQuery("free horror", sort = MovieSort.YearNewest, nl = chips).apply(movies, none, stats, noServices, catalog)
        assertEquals(newest.sortedWith(compareByDescending<com.movieroulettetuesday.app.catalog.Movie> { it.year }.thenBy { it.title }), newest)
        assertEquals(27, newest.size)
    }

    @Test
    fun episodesUseTheLinkedMovieAndPubYear() {
        val zone = ZoneId.of("America/New_York")
        fun ms(y: Int) = ZonedDateTime.of(y, 6, 1, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
        val devils = PodcastEpisode("Buzzsprout-20099500", "The Devil's Rain", ms(2025), season = 4, episode = 39)
        val thing = PodcastEpisode("Buzzsprout-19314551", "The Thing", ms(2025), season = 4, episode = 30)
        val bonus = PodcastEpisode("Buzzsprout-bonus", "Bonus: Listener Mailbag", ms(2024), season = 4, episode = 40)
        val eps = listOf(devils, thing, bonus)
        fun guids(f: NLSearchFilter, r: PersonalRatings = none) = NLSearchApply.episodes(f, eps, r, noServices, catalog, zone).map { it.guid }
        // Year: linked movie year (1975, 1982); no linked movie → pub-year.
        assertEquals(listOf(devils.guid), guids(NLSearchFilter(yearMin = 1970, yearMax = 1979)))
        assertEquals(listOf(bonus.guid), guids(NLSearchFilter(yearMin = 2024, yearMax = 2024)))
        // Movie-level fields need a linked movie.
        assertEquals(listOf(devils.guid, thing.guid), guids(NLSearchFilter(genre = "Horror")))
        assertEquals(listOf(devils.guid, thing.guid), guids(NLSearchFilter(watched = NLSearchFilter.Watched.Unwatched)))
        val r = ratings("the-thing-1982" to PersonalMovieEntry(halfStars = 9, status = WatchStatus.Watched))
        assertEquals(listOf(thing.guid), guids(NLSearchFilter(ratingMin = 4.5), r))
        assertEquals(listOf(devils.guid), guids(NLSearchFilter(watched = NLSearchFilter.Watched.Unwatched), r))
        // S#E# and title go through the matcher on the filtered set.
        assertEquals(listOf(devils.guid), guids(NLSearchFilter(season = 4, episode = 39, kind = NLSearchFilter.Kind.Episode)))
        assertEquals(listOf(devils.guid), guids(NLSearchFilter(title = "The Devil's Rain", season = 4, episode = 39)))
        assertEquals(listOf(thing.guid), guids(NLSearchFilter(title = "The Thing")))
        assertEquals(emptyList<String>(), guids(NLSearchFilter(title = "karl")))
    }

    @Test
    fun matcherQueryBuildsSeasonEpisodeTokens() {
        assertEquals("s4e39", NLSearchFilter(season = 4, episode = 39).matcherQuery)
        assertEquals("The Devil's Rain s4e39", NLSearchFilter(title = "The Devil's Rain", season = 4, episode = 39).matcherQuery)
        assertEquals("season 4", NLSearchFilter(season = 4).matcherQuery)
        assertEquals("episode 39", NLSearchFilter(episode = 39).matcherQuery)
        assertEquals(CatalogSearch.Query(text = "devils rain", textWithYear = "devils rain", year = null, season = 4, episode = 39),
            CatalogSearch.Query.parse(NLSearchFilter(title = "The Devil's Rain", season = 4, episode = 39).matcherQuery))
    }
}
