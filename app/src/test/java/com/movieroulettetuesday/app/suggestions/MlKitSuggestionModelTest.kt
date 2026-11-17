package com.movieroulettetuesday.app.suggestions

import com.google.mlkit.genai.prompt.GenerateContentRequest
import com.google.mlkit.genai.prompt.GenerateContentResponse
import com.google.mlkit.genai.prompt.GenerativeModel
import kotlin.coroutines.Continuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Compile-time bindings to Prompt API 1.0.0-beta5. This file will not compile if
 * [GenerativeModel.generateContent] or [GenerativeModel.checkStatus] stop being suspend functions.
 */
private suspend fun beta5Generate(model: GenerativeModel, prompt: String): String? =
    model.generateContent(prompt).candidates.firstOrNull()?.text

private suspend fun beta5Generate(model: GenerativeModel, request: GenerateContentRequest): GenerateContentResponse =
    model.generateContent(request)

private suspend fun beta5Status(model: GenerativeModel): Int = model.checkStatus()

/** Pick for Me uses these beta5 suspend calls too (verified against the 1.0.0-beta5 jar). */
private suspend fun beta5Warmup(model: GenerativeModel) = model.warmup()

private suspend fun beta5Budget(model: GenerativeModel, request: GenerateContentRequest): Pair<Int, Int> =
    model.countTokens(request).totalTokens to model.getTokenLimit()

@OptIn(ExperimentalCoroutinesApi::class)
class MlKitSuggestionModelTest {
    private fun client(
        status: SuggestionAvailability = SuggestionAvailability.Available,
        generate: suspend (String) -> String?,
    ) = object : MlKitSuggestionModel.Client {
        override suspend fun status() = status
        override suspend fun generate(prompt: String) = generate(prompt)
    }

    @Test
    fun beta5ExposesSuspendGenerateContentNotABlockingStringOverload() {
        // Touch the compile-time bindings so they cannot be treated as unused.
        val fromPrompt: suspend (GenerativeModel, String) -> String? = ::beta5Generate
        val fromRequest: suspend (GenerativeModel, GenerateContentRequest) -> GenerateContentResponse = ::beta5Generate
        val status: suspend (GenerativeModel) -> Int = ::beta5Status
        assertNotNull(fromPrompt)
        assertNotNull(fromRequest)
        assertNotNull(status)
        val warmup: suspend (GenerativeModel) -> Unit = ::beta5Warmup
        val budget: suspend (GenerativeModel, GenerateContentRequest) -> Pair<Int, Int> = ::beta5Budget
        assertNotNull(warmup)
        assertNotNull(budget)

        val blockingString = GenerativeModel::class.java.methods.filter { method ->
            method.name == "generateContent" &&
                method.parameterTypes.contentEquals(arrayOf(String::class.java))
        }
        assertTrue(
            "beta5 has no non-suspend generateContent(String); the old reflective client looked this up and missed",
            blockingString.isEmpty(),
        )

        val suspendString = GenerativeModel::class.java.methods.filter { method ->
            method.name == "generateContent" &&
                method.parameterTypes.size == 2 &&
                method.parameterTypes[0] == String::class.java &&
                Continuation::class.java.isAssignableFrom(method.parameterTypes[1])
        }
        assertTrue(suspendString.isNotEmpty())

        val blockingStatus = GenerativeModel::class.java.methods.filter { method ->
            method.name == "checkStatus" && method.parameterCount == 0
        }
        assertTrue(
            "beta5 checkStatus is suspend; a 0-arg reflective lookup would also miss",
            blockingStatus.isEmpty(),
        )
    }

    @Test
    fun replyTextIsPassedThroughForTheStrictParser() = runTest {
        val model = MlKitSuggestionModel(client { """{"pick":3,"vibe":"cult"}""" })
        assertEquals("""{"pick":3,"vibe":"cult"}""", model.generate("pick one"))
        assertEquals(SuggestionAvailability.Available, model.availability())
    }

    @Test
    fun requestCapsOutputAtTwentyFourTokensWithLowTemperature() {
        val req = MlKitSuggestionModel.request("1. Laura (1944) · Thriller")
        assertEquals(24, req.maxOutputTokens)
        assertEquals(0.3f, req.temperature, 0.0001f)
        assertEquals("1. Laura (1944) · Thriller", req.text?.textString)
    }

    @Test
    fun tokenBudgetLeavesRoomForTheReply() {
        assertTrue(MlKitSuggestionModel.fitsTokenLimit(500, 4_000))
        assertTrue(MlKitSuggestionModel.fitsTokenLimit(3_976, 4_000))
        assertTrue(!MlKitSuggestionModel.fitsTokenLimit(3_977, 4_000))
        assertTrue("unknown limit: try", MlKitSuggestionModel.fitsTokenLimit(9_999, 0))
    }

    @Test
    fun warmupIsForwardedAndAFailedWarmupIsHarmless() = runTest {
        var warmed = 0
        val ok = object : MlKitSuggestionModel.Client {
            override suspend fun status() = SuggestionAvailability.Available
            override suspend fun warmup() { warmed++ }
            override suspend fun generate(prompt: String): String? = null
        }
        MlKitSuggestionModel(ok).warmup()
        assertEquals(1, warmed)
        val broken = object : MlKitSuggestionModel.Client {
            override suspend fun status() = SuggestionAvailability.Available
            override suspend fun warmup(): Unit = error("AICore busy")
            override suspend fun generate(prompt: String): String? = null
        }
        MlKitSuggestionModel(broken).warmup()
    }

    @Test
    fun hungGenerateTimesOutAndIsCancelled() = runTest {
        var cancelled = 0
        val model = MlKitSuggestionModel(
            client {
                try {
                    awaitCancellation()
                } finally {
                    cancelled += 1
                }
            },
        )
        val out = withTimeoutOrNull(2_000) { model.generate("pick one") }
        assertEquals("timeout yields no suggestion", null, out)
        assertEquals("the hung generate call was cancelled", 1, cancelled)
        assertEquals(2_000, currentTime)
    }

    @Test
    fun timeoutCancellationIsNotSwallowedAsANullReply() = runTest {
        val model = MlKitSuggestionModel(client { awaitCancellation() })
        val job = async { model.generate("pick one") }
        advanceTimeBy(2_000)
        job.cancel()
        try {
            val result = job.await()
            fail("cancellation must propagate, got $result")
        } catch (_: CancellationException) {
            // expected: a swallowed cancel would have returned null instead
        }
    }

    @Test
    fun generateFailureOrUnusableReplyIsNull() = runTest {
        assertEquals(null, MlKitSuggestionModel(client { error("BUSY") }).generate("pick one"))
        assertEquals(null, MlKitSuggestionModel(client { null }).generate("pick one"))
    }

    @Test
    fun statusFailureIsUnavailable() = runTest {
        val client = object : MlKitSuggestionModel.Client {
            override suspend fun status(): SuggestionAvailability = error("AICore gone")
            override suspend fun generate(prompt: String) = error("unused")
        }
        assertEquals(SuggestionAvailability.Unavailable, MlKitSuggestionModel(client).availability())
    }
}
