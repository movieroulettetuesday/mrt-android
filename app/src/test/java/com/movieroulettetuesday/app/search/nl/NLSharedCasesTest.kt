package com.movieroulettetuesday.app.search.nl

import com.movieroulettetuesday.app.movies.MyServices
import com.movieroulettetuesday.app.movies.PersonalRatings
import com.movieroulettetuesday.app.search.CatalogSearch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * All 32 shared cases (mrt-shared `nl-search-test-cases.json`, pinned in repo `shared/`). A real model is
 * non-deterministic, so each case feeds the engine fake replies built from the case and checks, with the
 * file's loose-compare rules, what reaches apply: `required` equal, `optional` allowed, nothing else
 * non-null, `mustBeNull` null, `disallowedValues` absent, the `outcome`, and `switchesTo` from both lists.
 */
@RunWith(Parameterized::class)
class NLSharedCasesTest(private val id: String, private val case: JsonObject) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> = NLFixtures.sharedCases["cases"]!!.jsonArray.map {
            val o = it.jsonObject
            arrayOf(o["id"]!!.jsonPrimitive.content, o)
        }

        val FIELDS = listOf(
            "title", "kind", "yearMin", "yearMax", "genre", "category", "provider", "freeOnly", "ratingMin",
            "ratingMax", "unrated", "watched", "season", "episode", "onMyServices",
        )
    }

    private val question = case["question"]!!.jsonPrimitive.content
    private val outcome = case["outcome"]!!.jsonPrimitive.content
    private val required = case["required"]?.jsonObject ?: JsonObject(emptyMap())
    private val optional = case["optional"]?.jsonObject ?: JsonObject(emptyMap())
    private val mustBeNull = case["mustBeNull"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
    private val disallowed = case["disallowedValues"]?.jsonObject ?: JsonObject(emptyMap())
    private val providerAcceptable = case["providerAcceptable"]?.jsonArray?.map { it.jsonPrimitive.content }
    private val titleFolded = case["titleFolded"]?.jsonPrimitive?.content
    private val switchesTo = case["switchesTo"]!!.jsonPrimitive.content

    /** Replies a well-behaved model could give: exactly the required keys, plus optional keys, plus explicit nulls / any. */
    private fun replies(): List<String> {
        val base = LinkedHashMap<String, JsonElement>(required)
        val out = mutableListOf<JsonObject>()
        out += JsonObject(base)
        if (optional.isNotEmpty()) out += JsonObject(base + optional)
        // Verbose model: every other key spelled out as null, with kind/watched "any" when unset.
        out += buildJsonObject {
            for (f in FIELDS) put(f, base[f] ?: when (f) { "kind", "watched" -> JsonPrimitive("any"); else -> JsonNull })
        }
        providerAcceptable?.forEach { alias -> out += JsonObject(base + ("provider" to JsonPrimitive(alias))) }
        // validate cases: the model also emitted each disallowed value; it must not reach apply.
        for ((field, values) in disallowed) for (v in values.jsonArray) out += JsonObject(base + (field to v))
        return out.map { it.toString() }
    }

    @Test
    fun detectorAndEngineProduceTheSharedFilterAndSwitch() = runTest {
        val looksLikeQuestion = NLQuestionDetector.looksLikeQuestion(question)
        if (outcome == "empty-filter") {
            // Short title-like / punctuation queries never reach the model: keyword CatalogSearch, no apply.
            assertFalse("$id should stay keyword", looksLikeQuestion)
            val model = FakeNLModel { "{}" }
            val result = NLSearchEngine(model, { NLFixtures.providerNames }).resolve(question)
            assertEquals(NLOutcome.Keyword(NLOutcome.Reason.NotAQuestion), result)
            assertTrue("model must not be called", model.questions.isEmpty())
            // And if a model did answer {} or all-null, it is still the keyword path.
            assertEquals(NLParse.Empty, NLSearchValidator.parse("{}", NLFixtures.providerNames))
            assertEquals(NLParse.Empty, NLSearchValidator.parse(replies().last(), NLFixtures.providerNames))
            assertEquals("stay", switchesTo)
            return@runTest
        }
        assertTrue("$id should be detected as a question", looksLikeQuestion)
        for (reply in replies()) {
            val model = FakeNLModel { reply }
            val result = NLSearchEngine(model, { NLFixtures.providerNames }).resolve(question)
            val filter = (result as? NLOutcome.Parsed)?.filter ?: fail("$id: $reply → $result").let { return@runTest }
            assertEquals(listOf(question), model.questions)
            checkLoose(filter, reply)
            checkSwitch(filter)
            checkApplyRuns(filter)
        }
    }

    private fun checkLoose(filter: NLSearchFilter, reply: String) {
        val got = filter.toJson()
        for ((key, want) in required) {
            val have = got[key]
            assertNotNull("$id: required $key missing for $reply", have)
            when {
                key == "provider" && providerAcceptable != null ->
                    assertTrue("$id provider ${have!!.jsonPrimitive.content}", have.jsonPrimitive.content in providerAcceptable)
                key == "title" && titleFolded != null ->
                    assertTrue("$id title", CatalogSearch.fold(have!!.jsonPrimitive.content).contains(titleFolded))
                else -> assertTrue("$id: $key $have != $want ($reply)", same(have!!, want))
            }
        }
        for ((key, value) in got) {
            if (key in required) continue
            val opt = optional[key]
            assertTrue("$id: unexpected $key=$value from $reply", opt != null && same(value, opt))
        }
        for (key in mustBeNull) assertTrue("$id: $key must be null, got ${got[key]}", got[key] == null)
        for ((field, values) in disallowed) {
            val have = got[field]?.jsonPrimitive?.content
            assertFalse("$id: disallowed $field=$have reached apply", values.jsonArray.any { it.jsonPrimitive.content == have })
        }
    }

    private fun same(a: JsonElement, b: JsonElement): Boolean {
        val pa = a as? JsonPrimitive ?: return a == b
        val pb = b as? JsonPrimitive ?: return false
        if (pa.isString || pb.isString) return pa.isString == pb.isString && pa.content == pb.content
        val da = pa.doubleOrNull
        val db = pb.doubleOrNull
        return if (da != null && db != null) da == db else pa.content == pb.content
    }

    private fun checkSwitch(filter: NLSearchFilter) {
        val fromMovies = NLListSwitch.target(filter.kind, NLSearchFilter.Kind.Movie)
        val fromEpisodes = NLListSwitch.target(filter.kind, NLSearchFilter.Kind.Episode)
        when (switchesTo) {
            "movies" -> { assertEquals(null, fromMovies); assertEquals(NLSearchFilter.Kind.Movie, fromEpisodes) }
            "episodes" -> { assertEquals(NLSearchFilter.Kind.Episode, fromMovies); assertEquals(null, fromEpisodes) }
            "stay" -> { assertEquals(null, fromMovies); assertEquals(null, fromEpisodes) }
            else -> fail("unknown switchesTo $switchesTo")
        }
    }

    private fun checkApplyRuns(filter: NLSearchFilter) {
        val catalog = NLFixtures.catalog
        val result = NLSearchApply.movies(filter, catalog.movies, PersonalRatings(), MyServices(), catalog)
        assertTrue(catalog.movies.containsAll(result.movies))
        assertEquals("matcher runs only with title / S#E#", filter.needsMatcher, result.ranks != null)
    }
}

class NLSharedCasesFileTest {
    @Test
    fun pinnedFileHas32CasesAndTheSchemaVocab() {
        val root = NLFixtures.sharedCases
        assertEquals(32, root["cases"]!!.jsonArray.size)
        assertEquals(NLSearchVocab.GENRES, root["genres"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(
            NLSearchVocab.CATEGORIES.map { listOf(it.name, it.fullName, it.id) },
            root["categories"]!!.jsonArray.map { c -> listOf("name", "fullName", "id").map { c.jsonObject[it]!!.jsonPrimitive.content } },
        )
        val ids = root["cases"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun bundledCatalogVocabMatchesTheSchema() {
        val catalog = NLFixtures.catalog
        assertEquals(NLSearchVocab.GENRES.toSet(), catalog.movies.map { it.genre }.toSet())
        assertEquals(NLSearchVocab.CATEGORIES.map { it.id }.toSet(), catalog.categories.map { it.id }.toSet())
        for (c in NLSearchVocab.CATEGORIES) assertEquals(c.id, NLSearchVocab.categoryId(c.name, catalog))
    }
}
