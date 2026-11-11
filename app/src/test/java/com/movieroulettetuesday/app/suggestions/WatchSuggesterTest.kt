package com.movieroulettetuesday.app.suggestions

import com.movieroulettetuesday.app.catalog.Movie
import com.movieroulettetuesday.app.catalog.MovieCatalog
import com.movieroulettetuesday.app.catalog.RouletteCategory
import com.movieroulettetuesday.app.catalog.StreamingInfo
import com.movieroulettetuesday.app.catalog.StreamingInfo.Provider
import com.movieroulettetuesday.app.movies.MyServices
import com.movieroulettetuesday.app.movies.PersonalRatings
import com.movieroulettetuesday.app.movies.WatchStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeSuggestionModel(
    var availability: SuggestionAvailability = SuggestionAvailability.Available,
    var responses: MutableList<WatchSuggestion?> = mutableListOf(),
    var lastPrompt: String? = null,
    var calls: Int = 0,
) : SuggestionModel {
    override suspend fun availability() = availability
    override suspend fun generate(prompt: String): WatchSuggestion? {
        lastPrompt = prompt
        calls++
        return if (responses.isEmpty()) null else responses.removeAt(0)
    }
}

class WatchSuggesterTest {
    private val netflix = Provider(8, "Netflix")
    private val laura = Movie("laura-1944", "Laura", 1944, "Thriller", categories = listOf("noir"), streaming = StreamingInfo(stream = listOf(netflix)))
    private val rain = Movie("the-devils-rain-1975", "The Devil’s Rain", 1975, "Horror", categories = listOf("horror"))
    private val catalog = MovieCatalog(
        categories = listOf(RouletteCategory("noir", "Noir", colorHex = "#111", movieIds = listOf(laura.id))),
        movies = listOf(laura, rain),
    )

    private fun suggester(model: SuggestionModel) = WatchSuggester(model) { catalog }

    @Test
    fun contextIsOnDeviceOnlyAndHasNoNetwork() {
        val ratings = PersonalRatings()
            .withStatus(WatchStatus.WantToWatch, laura.id, 1)
            .withHalfStars(8, rain.id, 2)
        val ctx = suggester(FakeSuggestionModel()).buildContext(
            ratings,
            recentIds = setOf(rain.id),
            services = MyServices(setOf(8)),
        )!!
        val lines = ctx.asPromptLines().joinToString("\n")
        val prompt = suggester(FakeSuggestionModel()).prompt(ctx)
        assertFalse(lines.contains("http://") || lines.contains("https://"))
        assertFalse(prompt.contains("http://") || prompt.contains("https://"))
        assertFalse(prompt.contains("movies.json"))
        assertFalse(prompt.contains("personal-ratings"))
        // Compact facts are present in the on-device context rows.
        assertTrue(lines.contains("laura-1944"))
        assertTrue(lines.contains("Laura"))
        assertTrue(lines.contains("watchlist"))
        assertTrue(lines.contains("rated:4"))
        assertTrue(lines.contains("recent"))
        assertTrue(lines.contains("Netflix"))
        // No plots, no raw JSON, no other listeners' ratings in the context rows.
        assertFalse(lines.contains("plot"))
        assertFalse(lines.contains("movies.json"))
    }

    @Test
    fun validIdIsAccepted() = runBlocking {
        val model = FakeSuggestionModel(responses = mutableListOf(WatchSuggestion("laura-1944", "It’s on your watchlist.")))
        val ctx = suggester(model).buildContext(PersonalRatings(), emptySet(), MyServices())!!
        val out = suggester(model).suggest(ctx, fromShortcut = false)
        val ready = out as SuggestionOutcome.Ready
        assertEquals("laura-1944", ready.movie.id)
        assertEquals("It’s on your watchlist.", ready.why)
        assertEquals(1, model.calls)
    }

    @Test
    fun unknownIdIsRejectedAndRetriedOnce() = runBlocking {
        val model = FakeSuggestionModel(
            responses = mutableListOf(
                WatchSuggestion("not-a-movie", "nope"),
                WatchSuggestion("still-wrong", "nope"),
            ),
        )
        val ctx = suggester(model).buildContext(PersonalRatings(), emptySet(), MyServices())!!
        val out = suggester(model).suggest(ctx, fromShortcut = false)
        assertEquals(SuggestionOutcome.OfferSpin, out)
        assertEquals(2, model.calls)
    }

    @Test
    fun retrySucceedsWhenTheSecondIdIsValid() = runBlocking {
        val model = FakeSuggestionModel(
            responses = mutableListOf(
                WatchSuggestion("ghost", "no"),
                WatchSuggestion("laura-1944", "A classic."),
            ),
        )
        val ctx = suggester(model).buildContext(PersonalRatings(), emptySet(), MyServices())!!
        val out = suggester(model).suggest(ctx, fromShortcut = false) as SuggestionOutcome.Ready
        assertEquals("laura-1944", out.movie.id)
        assertEquals(2, model.calls)
    }

    @Test
    fun availabilityBranches() = runBlocking {
        val ctx = suggester(FakeSuggestionModel()).buildContext(PersonalRatings(), emptySet(), MyServices())!!
        suspend fun run(avail: SuggestionAvailability, fromShortcut: Boolean): SuggestionOutcome {
            val model = FakeSuggestionModel(availability = avail)
            return suggester(model).suggest(ctx, fromShortcut)
        }
        assertEquals(SuggestionOutcome.Hidden, run(SuggestionAvailability.Unavailable, fromShortcut = false))
        assertEquals(SuggestionOutcome.OfferSpin, run(SuggestionAvailability.Unavailable, fromShortcut = true))
        assertEquals(SuggestionOutcome.NotReady, run(SuggestionAvailability.Downloadable, fromShortcut = false))
        assertEquals(SuggestionOutcome.NotReady, run(SuggestionAvailability.Downloading, fromShortcut = true))
    }

    @Test
    fun parseResponseReadsJsonAndIgnoresFences() {
        val raw = WatchSuggester.parseResponse("""```json
{"movieId":"laura-1944","why":"A stylish noir."}
```""")
        assertEquals("laura-1944", raw!!.movieId)
        assertEquals("A stylish noir.", raw.why)
        assertEquals(null, WatchSuggester.parseResponse("not json"))
        assertEquals(null, WatchSuggester.parseResponse("""{"why":"no id"}"""))
    }

    @Test
    fun unavailableModelNeverCallsGenerate() = runBlocking {
        val model = FakeSuggestionModel(availability = SuggestionAvailability.Unavailable, responses = mutableListOf(WatchSuggestion("laura-1944", "x")))
        val ctx = suggester(model).buildContext(PersonalRatings(), emptySet(), MyServices())!!
        assertEquals(SuggestionOutcome.Hidden, suggester(model).suggest(ctx, fromShortcut = false))
        assertEquals(0, model.calls)
    }
}
