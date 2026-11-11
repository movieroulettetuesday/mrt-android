package com.movieroulettetuesday.app.actions

import com.movieroulettetuesday.app.catalog.Movie
import com.movieroulettetuesday.app.catalog.MovieCatalog
import com.movieroulettetuesday.app.catalog.RouletteCategory
import com.movieroulettetuesday.app.catalog.StreamingInfo
import com.movieroulettetuesday.app.catalog.StreamingInfo.Provider
import com.movieroulettetuesday.app.episodes.PodcastEpisode
import com.movieroulettetuesday.app.movies.MyServices
import com.movieroulettetuesday.app.movies.PersonalRatingsStore
import com.movieroulettetuesday.app.movies.WatchStatus
import com.movieroulettetuesday.app.movies.WhereToWatchDisplay
import com.movieroulettetuesday.app.widget.WidgetSnapshot
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionCoordinatorTest {
    private val netflix = Provider(8, "Netflix", "/n.png")
    private val shudder = Provider(99, "Shudder")
    private val apple = Provider(2, "Apple TV")
    private val laura = Movie(
        "laura-1944", "Laura", 1944, "Thriller",
        categories = listOf("noir"),
        streaming = StreamingInfo(stream = listOf(netflix), rent = listOf(apple), link = "https://www.themoviedb.org/movie/1/watch?locale=US"),
    )
    private val rain = Movie(
        "the-devils-rain-1975", "The Devil’s Rain", 1975, "Horror",
        categories = listOf("horror"),
        streaming = StreamingInfo(stream = listOf(shudder, netflix)),
    )
    private val bare = Movie("nothing-1980", "Nothing", 1980, "Drama")
    private val catalog = MovieCatalog(
        categories = listOf(RouletteCategory("noir", "Noir", colorHex = "#111111", movieIds = listOf(laura.id))),
        movies = listOf(laura, rain, bare),
    )
    private val episode = PodcastEpisode(
        guid = "Buzzsprout-19913482",
        title = "The Devil’s Rain",
        season = 4,
        episode = 34,
        pubDate = 1_762_214_400_000L, // 2025-11-04 00:00 UTC — value only used for formatting
    )

    private fun coordinator(
        ratings: PersonalRatingsStore = PersonalRatingsStore(null),
        episodes: List<PodcastEpisode> = listOf(episode),
        snap: WidgetSnapshot? = WidgetSnapshot(latestEpisode = WidgetSnapshot.EpisodeInfo.of(episode)),
        services: MyServices = MyServices(),
        cat: MovieCatalog? = catalog,
    ) = ActionCoordinator(
        catalog = { cat },
        ratings = ratings,
        episodes = { episodes },
        snapshot = { snap },
        services = { services },
        appleMap = { emptyMap() },
        zone = { ZoneOffset.UTC },
    )

    @Test
    fun addToWatchlistWritesWantToWatchAndDoesNotInventARating() {
        val store = PersonalRatingsStore(null)
        val plan = coordinator(ratings = store).addToWatchlist(laura.id)
        assertEquals(WatchStatus.WantToWatch, store.ratings.value.status(laura.id))
        assertNull(store.ratings.value.halfStars(laura.id))
        assertEquals(ActionSpeech.addedToWatchlist(laura), plan.speech)
        assertEquals(laura.id, plan.openMovieId)
    }

    @Test
    fun addToWatchlistPreservesAnExistingRating() {
        val store = PersonalRatingsStore(null)
        store.setHalfStars(8, laura.id)
        assertEquals(WatchStatus.Watched, store.ratings.value.status(laura.id)) // rating implies watched
        // Adding to the watchlist after a rating: setStatus replaces status, keeps half stars.
        store.setStatus(WatchStatus.WantToWatch, laura.id)
        val before = store.ratings.value.halfStars(laura.id)
        val plan = coordinator(ratings = store).addToWatchlist(laura.id)
        assertEquals("already on the list", ActionSpeech.alreadyOnWatchlist(laura), plan.speech)
        assertEquals(before, store.ratings.value.halfStars(laura.id))
        assertEquals(8, store.ratings.value.halfStars(laura.id))
    }

    @Test
    fun markWatchedReplacesWantToWatchAndDoesNotInventARating() {
        val store = PersonalRatingsStore(null)
        store.setStatus(WatchStatus.WantToWatch, rain.id)
        val plan = coordinator(ratings = store).markWatched(rain.id)
        assertEquals(WatchStatus.Watched, store.ratings.value.status(rain.id))
        assertNull(store.ratings.value.halfStars(rain.id))
        assertFalse(rain.id in store.ratings.value.watchlist)
        assertEquals(ActionSpeech.markedWatched(rain), plan.speech)
    }

    @Test
    fun showWatchlistListsOnlyWantToWatch() {
        val store = PersonalRatingsStore(null)
        store.setStatus(WatchStatus.WantToWatch, rain.id)
        store.setStatus(WatchStatus.WantToWatch, laura.id)
        store.setStatus(WatchStatus.Watched, bare.id)
        val plan = coordinator(ratings = store).showWatchlist()
        assertTrue(plan.openWatchlist)
        assertTrue(plan.speech.contains("Laura (1944)"))
        assertTrue(plan.speech.contains("The Devil’s Rain (1975)"))
        assertFalse(plan.speech.contains("Nothing"))
    }

    @Test
    fun showWatchlistEmptyIsTyped() {
        val plan = coordinator().showWatchlist()
        assertEquals(ActionSpeech.EMPTY_WATCHLIST, plan.speech)
        assertTrue(plan.openWatchlist)
    }

    @Test
    fun unknownMovieDoesNotWrite() {
        val store = PersonalRatingsStore(null)
        val plan = coordinator(ratings = store).addToWatchlist("no-such-movie")
        assertEquals(ActionSpeech.UNKNOWN_MOVIE, plan.speech)
        assertTrue(store.ratings.value.entries.isEmpty())
        assertNull(plan.openMovieId)
    }

    @Test
    fun latestEpisodeOpensCachedPageAndExposesListenLinks() {
        val plan = coordinator().latestEpisode()
        assertEquals(episode.guid, plan.openEpisodeGuid)
        assertTrue(plan.speech.contains("The Devil’s Rain"))
        assertTrue(plan.listenLinks.isNotEmpty())
        assertTrue(plan.listenLinks.any { it.id == "listen-buzzsprout" })
        assertFalse(plan.openEpisodesTab)
    }

    @Test
    fun latestEpisodeEmptyCacheIsTypedError() {
        val plan = coordinator(episodes = emptyList(), snap = WidgetSnapshot()).latestEpisode()
        assertEquals(ActionSpeech.EMPTY_LATEST, plan.speech)
        assertNull(plan.openEpisodeGuid)
        assertTrue(plan.openEpisodesTab)
        assertTrue(plan.listenLinks.isEmpty())
    }

    @Test
    fun whereToWatchSpeechMatchesDisplayAndFavorsMyServices() {
        val display = WhereToWatchDisplay.of(rain)
        assertTrue(display.showsProviders)
        val mine = coordinator(services = MyServices(setOf(99))).whereToWatch(rain.id)
        assertTrue(mine.focusWhereToWatch)
        assertEquals(rain.id, mine.openMovieId)
        assertTrue(mine.speech.contains("Shudder"))
        assertTrue(mine.speech.contains("your services") || mine.speech.contains("one of your services"))
        assertTrue(mine.speech.contains("Netflix"))
        val empty = coordinator().whereToWatch(bare.id)
        assertTrue(empty.speech.contains("no streaming sources"))
        assertEquals(WhereToWatchDisplay.EMPTY_MESSAGE.isNotEmpty(), true)
    }

    @Test
    fun openOnProviderUsesTheSamePlanAsProviderLaunch() {
        val plan = coordinator().openOnProvider(laura.id, "Netflix")
        assertNotNull(plan.providerPlan)
        assertEquals(ActionSpeech.openingOn("Netflix"), plan.speech)
        assertEquals(laura.id, plan.openMovieId)
        // Same plan a chip tap would use.
        val expected = com.movieroulettetuesday.app.movies.ProviderLaunch.plan(netflix, laura)
        assertEquals(expected, plan.providerPlan)
        val missing = coordinator().openOnProvider(laura.id, "Hulu")
        assertNull(missing.providerPlan)
        assertTrue(missing.speech.contains("Hulu"))
    }

    @Test
    fun findMovieDisambiguatesByYear() {
        val twins = MovieCatalog(
            categories = catalog.categories,
            movies = listOf(
                Movie("dune-1984", "Dune", 1984, "Sci-Fi"),
                Movie("dune-2021", "Dune", 2021, "Sci-Fi"),
                laura,
            ),
        )
        val c = coordinator(cat = twins)
        val both = c.findMovie("Dune", null)
        assertNull(both.openMovieId)
        assertTrue(both.speech.contains("1984"))
        assertTrue(both.speech.contains("2021"))
        val one = c.findMovie("Dune", 2021)
        assertEquals("dune-2021", one.openMovieId)
        assertTrue(one.speech.contains("Dune (2021)"))
        assertEquals(ActionSpeech.noMovieNamed("Zardoz", null), c.findMovie("Zardoz", null).speech)
    }

    @Test
    fun spinAndSuggestOnlyOpenRoulette() {
        val spin = coordinator().plan(DeepLink.Spin)
        assertTrue(spin.startSpin && spin.openRoulette)
        assertTrue(spin.speech.isEmpty())
        val suggest = coordinator().plan(DeepLink.Suggest)
        assertTrue(suggest.startSuggest && suggest.openRoulette)
    }
}
