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
    fun successfulReplyIsParsed() = runTest {
        val model = MlKitSuggestionModel(
            client { """{"movieId":"laura-1944","why":"It's on your watchlist."}""" },
        )
        assertEquals(
            WatchSuggestion("laura-1944", "It's on your watchlist."),
            model.generate("pick one"),
        )
        assertEquals(SuggestionAvailability.Available, model.availability())
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
        assertEquals(null, MlKitSuggestionModel(client { "not json" }).generate("pick one"))
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
