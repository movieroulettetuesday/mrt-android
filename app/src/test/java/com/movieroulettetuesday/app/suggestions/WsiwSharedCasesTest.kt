package com.movieroulettetuesday.app.suggestions

import com.movieroulettetuesday.app.catalog.Movie
import com.movieroulettetuesday.app.catalog.MovieCatalog
import com.movieroulettetuesday.app.catalog.RouletteCategory
import com.movieroulettetuesday.app.catalog.StreamingInfo
import com.movieroulettetuesday.app.catalog.StreamingInfo.Provider
import com.movieroulettetuesday.app.movies.MyServices
import com.movieroulettetuesday.app.movies.PersonalRatings
import com.movieroulettetuesday.app.movies.WatchStatus
import com.movieroulettetuesday.app.shared.SharedVectors
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every case in the pinned mrt-shared `wsiw/wsiw-cases.json` and `wsiw/banned-phrases.json`
 * (shared/, hash-checked by verifySharedContract), the same files the iOS tests read.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WsiwSharedCasesTest {
    private fun load(name: String): JsonObject =
        Json.parseToJsonElement(File(SharedVectors.root(), "wsiw/$name").readText()).jsonObject

    private val cases = load("wsiw-cases.json")
    private val banned = load("banned-phrases.json")

    /** Stable fake provider ids for the provider names the cases use. */
    private fun provider(name: String) = Provider(name.hashCode() and 0x7fffffff, name)

    private fun strings(o: JsonObject, key: String): List<String> =
        (o[key] as? kotlinx.serialization.json.JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()

    private fun str(o: JsonObject, key: String): String? = (o[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    private fun ratingsFor(id: String, o: JsonObject): PersonalRatings {
        var r = PersonalRatings()
        WatchStatus.fromRaw(str(o, "status"))?.let { r = r.withStatus(it, id, 1) }
        (o["halfStars"] as? JsonPrimitive)?.intOrNull?.let { r = r.withHalfStars(it, id, 2) }
        // withHalfStars implies Watched; restore the case's own status when it set one.
        WatchStatus.fromRaw(str(o, "status"))?.let { r = r.withStatus(it, id, 3) }
        return r
    }

    @Test
    fun runtimeBannedPatternsAreThePinnedSharedList() {
        assertEquals(1, banned["version"]!!.jsonPrimitive.intOrNull)
        assertEquals(strings(banned, "patterns"), BannedPhrases.PATTERNS)
    }

    @Test
    fun composerCases() {
        val list = cases["composer"]!!.jsonArray
        assertEquals(4, list.size)
        for (el in list) {
            val c = el.jsonObject
            val name = str(c, "name")
            val category = str(c, "category")
            val cats = category?.let { listOf(RouletteCategory("cat-x", it, colorHex = "#111111", movieIds = listOf("m"))) }.orEmpty()
            val movie = Movie(
                "m", "Christmas Evil", 1980, "Horror",
                categories = cats.map { it.id },
                streaming = StreamingInfo(stream = strings(c, "stream").map(::provider), free = strings(c, "free").map(::provider)),
            )
            val catalog = MovieCatalog(categories = cats, movies = listOf(movie))
            val services = MyServices(strings(c, "myServices").map { provider(it).id }.toSet())
            val facts = ReasonComposer.facts(movie, catalog, ratingsFor("m", c), services).map { it.text }
            assertEquals("composer: $name", strings(c, "expectFacts"), facts)
            for (f in strings(c, "forbid")) {
                assertTrue("composer: $name must not say “$f”: $facts", facts.none { it.contains(f) })
            }
        }
    }

    @Test
    fun validatorCases() {
        val providers = BannedPhrases.providerNames(com.movieroulettetuesday.app.search.nl.NLFixtures.catalog)
        val list = cases["validator"]!!.jsonArray
        assertEquals(3, list.size)
        for (el in list) {
            val c = el.jsonObject
            val text = str(c, "text")!!
            val expectReject = str(c, "expect") == "reject"
            assertEquals("validator: “$text”", expectReject, BannedPhrases.matches(text, providers))
        }
    }

    @Test
    fun candidateCases() {
        val list = cases["candidates"]!!.jsonArray
        assertEquals(2, list.size)
        val streamer = provider("Netflix")
        val tubi = provider("Tubi")
        for (el in list) {
            val c = el.jsonObject
            val name = str(c, "name")
            val specs = c["movies"]!!.jsonArray.map { it.jsonObject }
            var ratings = PersonalRatings()
            val recent = HashSet<String>()
            val movies = specs.map { m ->
                val id = str(m, "id")!!
                ratings = PersonalRatings(ratings.entries + ratingsFor(id, m).entries)
                if ((m["recent"] as? JsonPrimitive)?.booleanOrNull == true) recent += id
                val onService = (m["onService"] as? JsonPrimitive)?.booleanOrNull == true
                val free = (m["free"] as? JsonPrimitive)?.booleanOrNull == true
                Movie(
                    id, "Movie $id", 1990, "Horror",
                    streaming = StreamingInfo(stream = if (onService) listOf(streamer) else emptyList(), free = if (free) listOf(tubi) else emptyList()),
                )
            }
            val catalog = MovieCatalog(categories = emptyList(), movies = movies)
            for (seed in 0L..20L) {
                val got = PickCandidates.select(catalog, ratings, recent, MyServices(setOf(streamer.id)), seed).map { it.movie.id }
                strings(c, "expectExcluded").forEach { assertFalse("candidates: $name excludes $it (seed $seed): $got", it in got) }
                str(c, "expectFirst")?.let { assertEquals("candidates: $name first (seed $seed)", it, got.first()) }
                (c["cap"] as? JsonPrimitive)?.intOrNull?.let { assertEquals(it, PickCandidates.CAP); assertTrue(got.size <= it) }
                if (c.containsKey("expectCandidates")) assertEquals("candidates: $name (seed $seed)", strings(c, "expectCandidates"), got)
            }
        }
    }

    private fun pickFixture(n: Int, genre: String): Pair<MovieCatalog, List<PickCandidates.Candidate>> {
        val movies = (1..n).map { Movie("m$it", "Movie $it", 1990 + it, genre) }
        val catalog = MovieCatalog(categories = emptyList(), movies = movies)
        val input = PickInput(PersonalRatings(), emptySet(), MyServices(), seed = 7)
        return catalog to PickCandidates.select(catalog, input.ratings, input.recentIds, input.services, input.seed)
    }

    @Test
    fun pickCases() = runTest {
        val list = cases["picks"]!!.jsonArray
        assertEquals(3, list.size)
        for (el in list) {
            val c = el.jsonObject
            val n = (c["n"] as? JsonPrimitive)?.intOrNull ?: PickCandidates.CAP
            val genre = str(c, "genre") ?: "Horror"
            val (catalog, candidates) = pickFixture(n, genre)
            assertEquals(n, candidates.size)
            val input = PickInput(PersonalRatings(), emptySet(), MyServices(), seed = 7)
            val modelEl = c["model"]!!
            val out: SuggestionOutcome.Ready = if (modelEl is JsonPrimitive && modelEl.content == "timeout") {
                val hung = object : SuggestionModel {
                    override suspend fun availability() = SuggestionAvailability.Available
                    override suspend fun generate(prompt: String): String? = awaitCancellation()
                }
                val result = WatchSuggester(hung) { catalog }.suggest(input, fromShortcut = false) as SuggestionOutcome.Ready
                assertEquals(WatchSuggester.GENERATE_TIMEOUT_MS, currentTime)
                result
            } else {
                WatchSuggester.resolve(modelEl.toString(), candidates, catalog, input)
            }
            when (val expect = c["expect"]!!) {
                is JsonPrimitive -> {
                    assertEquals("templateFallback", expect.content)
                    assertEquals("fallback is the app's top-scored candidate: $c", candidates.first().movie, out.movie)
                    assertFalse(out.reason.fromModel)
                    assertEquals(null, out.reason.vibe)
                }
                is JsonObject -> {
                    val pick = expect["pick"]!!.jsonPrimitive.intOrNull!!
                    assertEquals(candidates[pick - 1].movie, out.movie)
                    assertTrue(out.reason.fromModel)
                    val vibe = str(expect, "vibe")!!
                    val want = if (vibe == "fromGenre") Vibes.fromGenre(out.movie) else Vibe.fromRaw(vibe)
                    assertEquals("pick case $c", want, out.reason.vibe)
                }
                else -> error("unexpected expect $expect")
            }
        }
    }
}
