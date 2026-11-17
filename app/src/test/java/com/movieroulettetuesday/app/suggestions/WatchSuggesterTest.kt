package com.movieroulettetuesday.app.suggestions

import com.movieroulettetuesday.app.catalog.Movie
import com.movieroulettetuesday.app.catalog.MovieCatalog
import com.movieroulettetuesday.app.catalog.RouletteCategory
import com.movieroulettetuesday.app.catalog.StreamingInfo
import com.movieroulettetuesday.app.catalog.StreamingInfo.Provider
import com.movieroulettetuesday.app.movies.MyServices
import com.movieroulettetuesday.app.movies.PersonalRatings
import com.movieroulettetuesday.app.movies.WatchStatus
import com.movieroulettetuesday.app.search.nl.NLFixtures
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeSuggestionModel(
    var availability: SuggestionAvailability = SuggestionAvailability.Available,
    var responses: MutableList<String?> = mutableListOf(),
    var lastPrompt: String? = null,
    var calls: Int = 0,
    var warmups: Int = 0,
) : SuggestionModel {
    override suspend fun availability() = availability
    override suspend fun warmup() { warmups++ }
    override suspend fun generate(prompt: String): String? {
        lastPrompt = prompt
        calls++
        return if (responses.isEmpty()) null else responses.removeAt(0)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class WatchSuggesterTest {
    private val netflix = Provider(8, "Netflix")
    private val tubi = Provider(73, "Tubi")
    private val laura = Movie("laura-1944", "Laura", 1944, "Thriller", categories = listOf("noir"), streaming = StreamingInfo(stream = listOf(netflix)))
    private val rain = Movie("the-devils-rain-1975", "The Devil’s Rain", 1975, "Horror", categories = listOf("horror"), streaming = StreamingInfo(free = listOf(tubi)))
    private val evil = Movie("christmas-evil-1980", "Christmas Evil", 1980, "Horror", categories = listOf("horror"))
    private val catalog = MovieCatalog(
        categories = listOf(
            RouletteCategory("noir", "Noir", colorHex = "#111", movieIds = listOf(laura.id)),
            RouletteCategory("horror", "Horror", colorHex = "#222", movieIds = listOf(rain.id, evil.id)),
        ),
        movies = listOf(laura, rain, evil),
    )

    private fun suggester(model: SuggestionModel) = WatchSuggester(model) { catalog }
    private fun input(ratings: PersonalRatings = PersonalRatings(), recent: Set<String> = emptySet(), services: MyServices = MyServices(), seed: Long = 1) =
        PickInput(ratings, recent, services, seed)

    @Test
    fun regressionUnratedNotOnWatchlistNeverClaimsWatchlistOrRating() = runBlocking {
        // Matthew, 1.1 (15): the reason said “on your watchlist, you gave it 4 stars” for neither.
        val model = FakeSuggestionModel(responses = mutableListOf("""{"pick":1,"vibe":"scares","why":"It's on your watchlist and you gave it 4 stars."}"""))
        val only = MovieCatalog(categories = catalog.categories, movies = listOf(evil))
        val out = WatchSuggester(model) { only }.suggest(input(), fromShortcut = false) as SuggestionOutcome.Ready
        val shown = listOfNotNull(out.reason.headline, out.reason.factsLine, out.reason.spoken(out.movie)).joinToString(" | ")
        assertFalse(shown, shown.contains("watchlist", ignoreCase = true))
        assertFalse(shown, shown.contains("star", ignoreCase = true))
        assertFalse(shown, shown.contains("gave it"))
        assertEquals(listOf("Not watched yet", "From the Horror category"), out.reason.facts.map { it.text })
    }

    @Test
    fun modelOnlyPicksTheIndexAndVibeAndTheAppWritesTheFacts() = runBlocking {
        val ratings = PersonalRatings().withStatus(WatchStatus.WantToWatch, laura.id, 1)
        val model = FakeSuggestionModel(responses = mutableListOf("""{"pick":1,"vibe":"thrills"}"""))
        val out = suggester(model).suggest(input(ratings, services = MyServices(setOf(8))), fromShortcut = false) as SuggestionOutcome.Ready
        assertEquals(laura, out.movie) // watchlist + service ranks first
        assertEquals(Vibe.Thrills, out.reason.vibe)
        assertEquals("Edge-of-your-seat.", out.reason.headline)
        assertEquals(listOf("On your watchlist", "Not watched yet", "Streaming on Netflix", "From the Noir category"), out.reason.facts.map { it.text })
        assertEquals(
            "Try Laura (1944). Edge-of-your-seat. It’s on your watchlist and streaming on Netflix.",
            out.reason.spoken(out.movie),
        )
        assertEquals("one attempt only", 1, model.calls)
    }

    @Test
    fun promptRowsStateEveryFieldAsYesOrNoAndCarryNoIdsOrUrls() = runBlocking {
        val ratings = PersonalRatings().withStatus(WatchStatus.WantToWatch, laura.id, 1)
        val model = FakeSuggestionModel()
        suggester(model).suggest(input(ratings, services = MyServices(setOf(8))), fromShortcut = false)
        val prompt = model.lastPrompt!!
        val rows = prompt.lines().filter { it.matches(Regex("""^\d+\. .*""")) }
        assertEquals(3, rows.size)
        assertTrue(rows[0], rows[0].startsWith("1. Laura (1944) · Thriller · categories: Noir · watchlist: yes · watched: no · rated: no · on your services: Netflix · free: no"))
        rows.forEach { row ->
            listOf("watchlist: ", "watched: ", "rated: ", "on your services: ", "free: ").forEach { assertTrue("$row lacks $it", row.contains(it)) }
        }
        assertTrue(rows.any { it.contains("watchlist: no") })
        assertTrue(rows.any { it.contains("free: yes") })
        assertTrue(prompt.contains("""{"pick":"""))
        assertFalse(prompt.contains("laura-1944"))
        assertFalse(prompt.contains("http"))
        assertFalse(prompt.contains("movies.json"))
    }

    @Test
    fun fullCatalogPromptStaysNearFourHundredTokens() = runBlocking {
        val cat = NLFixtures.catalog
        val model = FakeSuggestionModel()
        WatchSuggester(model) { cat }.suggest(input(services = MyServices(setOf(8, 9, 1899, 337, 15))), fromShortcut = false)
        val prompt = model.lastPrompt!!
        val rows = prompt.lines().count { it.matches(Regex("""^\d+\. .*""")) }
        assertEquals(PickCandidates.CAP, rows)
        // ~4 characters per token: well under 1,000 tokens (plan: ~400), against Gemini Nano's ~4,000-token input.
        assertTrue("prompt is ${prompt.length} chars", prompt.length < 2_400)
    }

    @Test
    fun watchedRatedAndRecentAreExcludedWhenEnoughRemain() {
        val extra = (1..5).map { Movie("m$it", "M$it", 2000 + it, "Comedy") }
        val cat = catalog.copy(movies = catalog.movies + extra)
        val ratings = PersonalRatings()
            .withStatus(WatchStatus.Watched, laura.id, 1)
            .withHalfStars(4, rain.id, 2) // rated = watched
        val got = PickCandidates.select(cat, ratings, setOf(evil.id), MyServices(), seed = 3).map { it.movie.id }
        assertFalse(laura.id in got)
        assertFalse(rain.id in got)
        assertFalse(evil.id in got)
        assertEquals(5, got.size)
    }

    @Test
    fun relaxesToRecentThenToRewatchesRatedFourOrMore() {
        val ratings = PersonalRatings().withHalfStars(8, laura.id, 1).withHalfStars(7, rain.id, 2)
        // Only evil is unwatched, and it is recent: relax to recent, then add laura (4 stars), never rain (3.5).
        val got = PickCandidates.select(catalog, ratings, setOf(evil.id), MyServices(), seed = 1).map { it.movie.id }.toSet()
        assertEquals(setOf(evil.id, laura.id), got)
    }

    @Test
    fun capsAtTwelveAndTieBreaksAreSeededAndReproducible() {
        val many = (1..40).map { Movie("m$it", "M$it", 2000, "Comedy") }
        val cat = MovieCatalog(categories = emptyList(), movies = many)
        val a = PickCandidates.select(cat, PersonalRatings(), emptySet(), MyServices(), seed = 42).map { it.movie.id }
        val b = PickCandidates.select(cat, PersonalRatings(), emptySet(), MyServices(), seed = 42).map { it.movie.id }
        val c = PickCandidates.select(cat, PersonalRatings(), emptySet(), MyServices(), seed = 43).map { it.movie.id }
        assertEquals(12, a.size)
        assertEquals(a, b)
        assertNotEquals(a, c)
    }

    @Test
    fun scoresWatchlistThreeServiceTwoFreeOne() {
        val ratings = PersonalRatings().withStatus(WatchStatus.WantToWatch, rain.id, 1)
        assertEquals(2, PickCandidates.score(laura, PersonalRatings(), MyServices(setOf(8))))
        assertEquals(0, PickCandidates.score(laura, PersonalRatings(), MyServices()))
        assertEquals(4, PickCandidates.score(rain, ratings, MyServices()))
        assertEquals(0, PickCandidates.score(evil, PersonalRatings(), MyServices(setOf(8))))
    }

    @Test
    fun noCandidatesAtAllOffersSpin() = runBlocking {
        val ratings = PersonalRatings().withHalfStars(2, laura.id, 1).withHalfStars(2, rain.id, 1).withHalfStars(2, evil.id, 1)
        assertEquals(SuggestionOutcome.OfferSpin, suggester(FakeSuggestionModel()).suggest(input(ratings), fromShortcut = false))
    }

    @Test
    fun anyModelFailureShowsTheTopScoredCandidateWithFactsNotSpin() = runBlocking {
        val ratings = PersonalRatings().withStatus(WatchStatus.WantToWatch, laura.id, 1)
        for (reply in listOf(null, "", "not json", """{"pick":9,"vibe":"laughs"}""", """{"pick":"1","vibe":"laughs"}""",
            """{"pick":1,"vibe":"spooky"}""", """{"pick":1}""", """{"pick":1,"vibe":"thrills","why":"x"}""", """[1,"laughs"]""")) {
            val model = FakeSuggestionModel(responses = mutableListOf(reply))
            val out = suggester(model).suggest(input(ratings, services = MyServices(setOf(8))), fromShortcut = false)
            val ready = out as SuggestionOutcome.Ready
            assertEquals("reply $reply", laura, ready.movie)
            assertFalse(ready.reason.fromModel)
            assertEquals(PickReason.FALLBACK_LINE, ready.reason.headline)
            assertEquals(1, model.calls)
        }
        val throwing = object : SuggestionModel {
            override suspend fun availability() = SuggestionAvailability.Available
            override suspend fun generate(prompt: String): String? = error("AICore quota")
        }
        val out = suggester(throwing).suggest(input(ratings, services = MyServices(setOf(8))), fromShortcut = false) as SuggestionOutcome.Ready
        assertEquals(laura, out.movie)
        assertFalse(out.reason.fromModel)
    }

    @Test
    fun fallbackLineOnlyWhenThePickReallyCameFromTheWatchlistOrServices() {
        val plain = PickReason(null, listOf(Fact.NotWatched), fromModel = false)
        assertEquals(null, plain.headline)
        assertEquals(PickReason.FALLBACK_LINE, PickReason(null, listOf(Fact.Streaming("Netflix")), fromModel = false).headline)
    }

    @Test
    fun aMismatchedVibeIsReplacedByTheGenresAndBannedTextDropsIt() {
        val cands = PickCandidates.select(catalog, PersonalRatings(), emptySet(), MyServices(), seed = 1)
        val i = cands.indexOfFirst { it.movie == evil } + 1
        val laughs = WatchSuggester.resolve("""{"pick":$i,"vibe":"laughs"}""", cands, catalog, input())
        assertEquals(evil, laughs.movie)
        assertEquals(Vibe.Scares, laughs.reason.vibe)
        val classicTooNew = WatchSuggester.resolve("""{"pick":$i,"vibe":"classic"}""", cands, catalog, input())
        assertEquals("1980 is not old-school", Vibe.Scares, classicTooNew.reason.vibe)
        val j = cands.indexOfFirst { it.movie == laura } + 1
        assertEquals(Vibe.Classic, WatchSuggester.resolve("""{"pick":$j,"vibe":"classic"}""", cands, catalog, input()).reason.vibe)
    }

    @Test
    fun bannedCheckIsForModelTextOnlyAppCopyLikeACultFavouriteIsExempt() {
        // The app's own line would trip the list (“favourite”) if it were checked; it must not be.
        assertTrue(BannedPhrases.matches(Vibe.Cult.line, emptySet()))
        val cands = PickCandidates.select(catalog, PersonalRatings(), emptySet(), MyServices(), seed = 1)
        val i = cands.indexOfFirst { it.movie == evil } + 1
        val out = WatchSuggester.resolve("""{"pick":$i,"vibe":"cult"}""", cands, catalog, input())
        assertEquals(evil, out.movie)
        assertTrue(out.reason.fromModel)
        assertEquals("A cult favourite.", out.reason.headline)
        // Facts are app-written too and say “watchlist”/“Streaming” freely.
        val ratings = PersonalRatings().withStatus(WatchStatus.WantToWatch, laura.id, 1)
        val j = PickCandidates.select(catalog, ratings, emptySet(), MyServices(setOf(8)), seed = 1).indexOfFirst { it.movie == laura } + 1
        val lauraOut = WatchSuggester.resolve(
            """{"pick":$j,"vibe":"thrills"}""",
            PickCandidates.select(catalog, ratings, emptySet(), MyServices(setOf(8)), seed = 1), catalog, input(ratings, services = MyServices(setOf(8))),
        )
        assertTrue(lauraOut.reason.fromModel)
        assertEquals(listOf("On your watchlist", "Not watched yet", "Streaming on Netflix", "From the Noir category"), lauraOut.reason.facts.map { it.text })
    }

    @Test
    fun spokenPickFollowsTheIosFormat() {
        val reason = PickReason(Vibe.Scares, listOf(Fact.OnWatchlist, Fact.NotWatched, Fact.Streaming("Netflix"), Fact.Category("Horror")), fromModel = true)
        assertEquals("Try Christmas Evil (1980). Good for a scare. It’s on your watchlist and streaming on Netflix.", reason.spoken(evil))
        val alien = Movie("alien-1979", "Alien", 1979, "Sci-Fi")
        val rewatch = PickReason(null, listOf(Fact.Rated(9), Fact.Free("Tubi")), fromModel = false)
        assertEquals("Try Alien (1979). You rated it 4.5 stars. It’s free on Tubi.", rewatch.spoken(alien))
        assertEquals(reason.spoken(evil), com.movieroulettetuesday.app.actions.ActionSpeech.suggestion(evil, reason))
    }

    @Test
    fun bannedPhrasesCoverWatchlistRatingsServicesAndProviders() {
        val providers = setOf("Netflix", "Max")
        listOf("on your watchlist", "Watch list pick", "you rated it", "4 stars", "★★★", "3/5", "out of ten", "you haven’t seen it",
            "streaming now", "free tonight", "Oscar winner", "critics loved it", "on Netflix", "HBO MAX").forEach {
            assertTrue(it, BannedPhrases.matches(it, providers))
        }
        listOf("Gloriously weird.", "Big, loud fun.", "maximum dread", "{\"pick\":3,\"vibe\":\"cult\"}").forEach {
            assertFalse(it, BannedPhrases.matches(it, providers))
        }
    }

    @Test
    fun availabilityBranches() = runBlocking {
        suspend fun run(avail: SuggestionAvailability, fromShortcut: Boolean): SuggestionOutcome {
            val model = FakeSuggestionModel(availability = avail)
            return suggester(model).suggest(input(), fromShortcut)
        }
        assertEquals(SuggestionOutcome.Hidden, run(SuggestionAvailability.Unavailable, fromShortcut = false))
        assertEquals(SuggestionOutcome.OfferSpin, run(SuggestionAvailability.Unavailable, fromShortcut = true))
        assertEquals(SuggestionOutcome.NotReady, run(SuggestionAvailability.Downloadable, fromShortcut = false))
        assertEquals(SuggestionOutcome.NotReady, run(SuggestionAvailability.Downloading, fromShortcut = true))
    }

    @Test
    fun unavailableModelNeverCallsGenerate() = runBlocking {
        val model = FakeSuggestionModel(availability = SuggestionAvailability.Unavailable, responses = mutableListOf("""{"pick":1,"vibe":"laughs"}"""))
        assertEquals(SuggestionOutcome.Hidden, suggester(model).suggest(input(), fromShortcut = false))
        assertEquals(0, model.calls)
    }

    @Test
    fun hungGenerateTimesOutAfterThreeSecondsAndShowsTheAppsPick() = runTest {
        var cancelled = 0
        val model = object : SuggestionModel {
            override suspend fun availability() = SuggestionAvailability.Available
            override suspend fun generate(prompt: String): String? {
                try {
                    awaitCancellation()
                } finally {
                    cancelled += 1
                }
            }
        }
        val out = suggester(model).suggest(input(), fromShortcut = false)
        assertTrue(out is SuggestionOutcome.Ready)
        assertFalse((out as SuggestionOutcome.Ready).reason.fromModel)
        assertEquals(3_000L, WatchSuggester.GENERATE_TIMEOUT_MS)
        assertEquals(WatchSuggester.GENERATE_TIMEOUT_MS, currentTime)
        assertEquals("the hung generate call was cancelled", 1, cancelled)
    }

    @Test
    fun recentIsTheTenMostRecentUniqueSpins() {
        val ids = (1..15).map { "m$it" } + listOf("m1", "m2")
        assertEquals((1..10).map { "m$it" }.toSet(), WatchSuggester.recentIds(ids))
    }

    @Test
    fun strictParser() {
        assertEquals(PickParser.ModelPick(2, Vibe.Cult), PickParser.parse("""{"pick":3,"vibe":"cult"}""", 12))
        assertEquals(PickParser.ModelPick(0, Vibe.FeelGood), PickParser.parse("```json\n{\"vibe\":\"feelGood\",\"pick\":1}\n```", 12))
        assertEquals(null, PickParser.parse("""{"pick":0,"vibe":"cult"}""", 12))
        assertEquals(null, PickParser.parse("""{"pick":13,"vibe":"cult"}""", 12))
        assertEquals(null, PickParser.parse("""{"pick":2.5,"vibe":"cult"}""", 12))
        assertEquals(null, PickParser.parse("""Sure! {"pick":3,"vibe":"cult"}""", 12))
        assertEquals(null, PickParser.parse("""{"pick":3,"vibe":"Cult"}""", 12))
    }
}
