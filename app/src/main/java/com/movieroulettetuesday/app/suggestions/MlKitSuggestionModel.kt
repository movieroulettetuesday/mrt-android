package com.movieroulettetuesday.app.suggestions

import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import kotlinx.coroutines.CancellationException

/**
 * Gemini Nano through ML Kit GenAI (Prompt API / AICore), on-device only.
 *
 * Calls the typed, suspend Prompt API (`com.google.mlkit:genai-prompt` 1.0.0-beta5):
 * [GenerativeModel.checkStatus] and [GenerativeModel.generateContent] `(prompt: String)`.
 * Beta5 has no non-suspend `generateContent(String)`; looking that method up reflectively
 * never resolved, so this client never received a model reply.
 *
 * Every call is guarded so a missing AICore, an old Play services build, or a preview-API
 * change can never crash the app: failures become [SuggestionAvailability.Unavailable] or
 * a null suggestion. Cancellation is not swallowed. The library is not invoked at process
 * start. Inference is only attempted while the caller is in the foreground (the Roulette
 * tab / `mrt://suggest` opens the app first).
 */
class MlKitSuggestionModel(
    private val client: Client = PromptApiClient,
) : SuggestionModel {

    interface Client {
        suspend fun status(): SuggestionAvailability
        suspend fun generate(prompt: String): String?
    }

    override suspend fun availability(): SuggestionAvailability =
        guarded(SuggestionAvailability.Unavailable) { client.status() }

    override suspend fun generate(prompt: String): WatchSuggestion? {
        val text = guarded(null) { client.generate(prompt) }
        return WatchSuggester.parseResponse(text)
    }

    /**
     * Typed ML Kit Prompt API (`com.google.mlkit.genai.prompt.Generation.getClient()`).
     * FeatureStatus values: AVAILABLE, DOWNLOADABLE, DOWNLOADING, UNAVAILABLE.
     */
    object PromptApiClient : Client {
        private val model: GenerativeModel? by lazy { runCatching { Generation.getClient() }.getOrNull() }

        override suspend fun status(): SuggestionAvailability {
            val m = model ?: return SuggestionAvailability.Unavailable
            return when (m.checkStatus()) {
                FeatureStatus.AVAILABLE -> SuggestionAvailability.Available
                FeatureStatus.DOWNLOADABLE -> SuggestionAvailability.Downloadable
                FeatureStatus.DOWNLOADING -> SuggestionAvailability.Downloading
                else -> SuggestionAvailability.Unavailable
            }
        }

        override suspend fun generate(prompt: String): String? {
            val m = model ?: return null
            // Suspend convenience overload (beta5): wraps the prompt in a default GenerateContentRequest.
            return m.generateContent(prompt).candidates.firstOrNull()?.text
        }
    }
}

private suspend fun <T> guarded(fallback: T, block: suspend () -> T): T = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (_: Throwable) {
    fallback
}
