package com.movieroulettetuesday.app.search.nl

import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Debounce, availability gating, timeout, cancel, and failure fallback with a fake model (virtual time). */
class NLSearchEngineTest {
    private val question = "free horror I haven't watched"
    private val good = """{"genre":"Horror","freeOnly":true,"watched":"unwatched"}"""
    private fun engine(model: NLSearchModel) = NLSearchEngine(model, { NLFixtures.providerNames })

    @Test
    fun validReplyParsesAfterTheDebounce() = runTest {
        val model = FakeNLModel { good }
        val job = async { engine(model).resolve(question) }
        advanceTimeBy(NLSearchEngine.DEBOUNCE_MS - 1)
        runCurrent()
        assertTrue("no model call inside the debounce", model.questions.isEmpty())
        val out = job.await()
        assertEquals(NLOutcome.Parsed(NLSearchFilter(genre = "Horror", freeOnly = true, watched = NLSearchFilter.Watched.Unwatched)), out)
        assertEquals(listOf(question), model.questions)
        assertEquals(NLSearchEngine.DEBOUNCE_MS, currentTime)
    }

    @Test
    fun plainKeywordsNeverCallTheModel() = runTest {
        val model = FakeNLModel { good }
        for (q in listOf("the thing", "Shudder", "???", "", "   ", "alien")) {
            assertEquals(q, NLOutcome.Keyword(NLOutcome.Reason.NotAQuestion), engine(model).resolve(q))
        }
        assertTrue(model.questions.isEmpty())
    }

    @Test
    fun unavailableDownloadableAndDownloadingFallBackWithoutGenerating() = runTest {
        for (s in listOf(NLModelStatus.Unavailable, NLModelStatus.Downloadable, NLModelStatus.Downloading)) {
            val model = FakeNLModel(currentStatus = s) { good }
            assertEquals(s.name, NLOutcome.Keyword(NLOutcome.Reason.ModelUnavailable), engine(model).resolve(question))
            assertTrue(model.questions.isEmpty())
        }
        assertEquals(NLOutcome.Keyword(NLOutcome.Reason.ModelUnavailable), engine(UnavailableNLSearchModel).resolve(question))
        val throwing = object : NLSearchModel {
            override suspend fun status(): NLModelStatus = error("AICore gone")
            override suspend fun generate(prompt: String): String = error("unreachable")
        }
        assertEquals(NLModelStatus.Unavailable, engine(throwing).status())
        assertEquals(NLOutcome.Keyword(NLOutcome.Reason.ModelUnavailable), engine(throwing).resolve(question))
    }

    @Test
    fun timeoutAfterTwoSecondsFallsBack() = runTest {
        val model = FakeNLModel(hang = true) { good }
        val out = engine(model).resolve(question)
        assertEquals(NLOutcome.Keyword(NLOutcome.Reason.Timeout), out)
        assertEquals(NLSearchEngine.DEBOUNCE_MS + NLSearchEngine.TIMEOUT_MS, currentTime)
        assertEquals("the hung generate call was cancelled", 1, model.cancelled)
    }

    @Test
    fun modelErrorsAndNullRepliesFallBack() = runTest {
        assertEquals(NLOutcome.Keyword(NLOutcome.Reason.ModelError), engine(FakeNLModel(throwOnGenerate = IllegalStateException("BUSY")) { good }).resolve(question))
        assertEquals(NLOutcome.Keyword(NLOutcome.Reason.ModelError), engine(FakeNLModel { null }).resolve(question))
    }

    @Test
    fun invalidJsonAndEmptyParseFallBack() = runTest {
        assertEquals(NLOutcome.Keyword(NLOutcome.Reason.Invalid), engine(FakeNLModel { "Here are some horror movies!" }).resolve(question))
        assertEquals(NLOutcome.Keyword(NLOutcome.Reason.Invalid), engine(FakeNLModel { "{\"genre\": " }).resolve(question))
        assertEquals(NLOutcome.Keyword(NLOutcome.Reason.EmptyFilter), engine(FakeNLModel { "{}" }).resolve(question))
        assertEquals(NLOutcome.Keyword(NLOutcome.Reason.EmptyFilter), engine(FakeNLModel { """{"genre":"Animation"}""" }).resolve(question))
    }

    @Test
    fun outOfRangeValuesNeverReachApply() = runTest {
        val out = engine(FakeNLModel { """{"genre":"Horror","yearMin":3000,"yearMax":1200,"ratingMin":9,"season":-2,"provider":"Blockbuster"}""" })
            .resolve(question)
        assertEquals(NLOutcome.Parsed(NLSearchFilter(genre = "Horror", yearMin = 1880, yearMax = 2100)), out)
    }

    @Test
    fun cancelDuringDebounceSkipsTheModel() = runTest {
        val model = FakeNLModel { good }
        val job = async { engine(model).resolve(question) }
        advanceTimeBy(100)
        job.cancel()
        advanceTimeBy(5_000)
        assertTrue(job.isCancelled)
        assertTrue(model.questions.isEmpty())
    }

    @Test
    fun cancelDuringGenerationCancelsTheModelCall() = runTest {
        val model = FakeNLModel(hang = true) { good }
        val job = async { engine(model).resolve(question) }
        advanceTimeBy(NLSearchEngine.DEBOUNCE_MS + 500)
        runCurrent()
        assertEquals(listOf(question), model.questions)
        job.cancel()
        runCurrent()
        assertTrue(job.isCancelled)
        assertEquals(1, model.cancelled)
    }

    @Test
    fun listSwitchRule() {
        val m = NLSearchFilter.Kind.Movie
        val e = NLSearchFilter.Kind.Episode
        assertEquals(e, NLListSwitch.target(e, m))
        assertEquals(m, NLListSwitch.target(m, e))
        assertEquals(null, NLListSwitch.target(m, m))
        assertEquals(null, NLListSwitch.target(e, e))
        assertEquals(null, NLListSwitch.target(null, m))
        assertEquals(null, NLListSwitch.target(null, e))
    }

    @Test
    fun detectorTriggers() {
        val yes = listOf(
            "find alien", "what's on", "show me horror", "free", "80s horror", "1980s", "horror from 1975 to 1985", "unrated",
            "watchlist", "rated 4", "on my services", "horror on tubi", "S4E39", "4x39", "season 4", "episode 39", "movies",
            "films", "episodes about alien", "before 1980 horror", "a b c d e",
        )
        val no = listOf("the thing", "Shudder", "???", "alien", "horror", "on", "blade runner 2049", "2001", "the devils rain", "episode", "s4")
        for (q in yes) assertTrue(q, NLQuestionDetector.looksLikeQuestion(q))
        for (q in no) assertTrue(q, !NLQuestionDetector.looksLikeQuestion(q))
    }
}
