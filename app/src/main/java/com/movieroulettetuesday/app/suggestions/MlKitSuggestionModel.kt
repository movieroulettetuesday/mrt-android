package com.movieroulettetuesday.app.suggestions

import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.GenerateContentRequest
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.CancellationException

/**
 * Gemini Nano through ML Kit GenAI (Prompt API / AICore), on-device only.
 *
 * Uses the typed, suspend Prompt API (`com.google.mlkit:genai-prompt` 1.0.0-beta5, checked against
 * the beta5 jar): [GenerativeModel.checkStatus], [GenerativeModel.warmup],
 * [GenerativeModel.getTokenLimit], [GenerativeModel.countTokens] and
 * [GenerativeModel.generateContent] `(GenerateContentRequest)`. Each request caps output at
 * [MAX_OUTPUT_TOKENS] (the reply is `{"pick":N,"vibe":"…"}`) with [TEMPERATURE]; a prompt the model
 * reports as over its token limit is not sent.
 *
 * Every call is guarded so a missing AICore, an old Play services build, or a preview-API change
 * can never crash the app: failures become [SuggestionAvailability.Unavailable] or a null reply
 * (which the app turns into its own factual pick). Cancellation is not swallowed. The library is
 * not invoked at process start: the Roulette screen asks for status when it opens, which creates the
 * client, and calls [warmup] once the status is AVAILABLE.
 */
class MlKitSuggestionModel(
    private val client: Client = PromptApiClient,
) : SuggestionModel {

    interface Client {
        suspend fun status(): SuggestionAvailability
        suspend fun warmup() {}
        suspend fun generate(prompt: String): String?
    }

    override suspend fun availability(): SuggestionAvailability =
        guarded(SuggestionAvailability.Unavailable) { client.status() }

    override suspend fun warmup() = guarded(Unit) { client.warmup() }

    override suspend fun generate(prompt: String): String? = guarded(null) { client.generate(prompt) }

    /**
     * Typed ML Kit Prompt API (`com.google.mlkit.genai.prompt.Generation.getClient()`).
     * FeatureStatus values: AVAILABLE, DOWNLOADABLE, DOWNLOADING, UNAVAILABLE.
     */
    object PromptApiClient : Client {
        @Volatile private var cached: GenerativeModel? = null
        @Volatile private var tokenLimit: Int? = null

        private fun model(): GenerativeModel? =
            cached ?: runCatching { Generation.getClient() }.getOrNull()?.also { cached = it }

        override suspend fun status(): SuggestionAvailability {
            val m = model() ?: return SuggestionAvailability.Unavailable
            return when (m.checkStatus()) {
                FeatureStatus.AVAILABLE -> SuggestionAvailability.Available
                FeatureStatus.DOWNLOADABLE -> SuggestionAvailability.Downloadable
                FeatureStatus.DOWNLOADING -> SuggestionAvailability.Downloading
                else -> SuggestionAvailability.Unavailable
            }
        }

        override suspend fun warmup() {
            val m = model() ?: return
            m.warmup()
            if (tokenLimit == null) tokenLimit = runCatching { m.getTokenLimit() }.getOrNull()
        }

        override suspend fun generate(prompt: String): String? {
            val m = model() ?: return null
            val req = request(prompt)
            val limit = tokenLimit ?: m.getTokenLimit().also { tokenLimit = it }
            if (!fitsTokenLimit(m.countTokens(req).totalTokens, limit)) return null
            return m.generateContent(req).candidates.firstOrNull()?.text
        }
    }

    companion object {
        const val MAX_OUTPUT_TOKENS = 24
        const val TEMPERATURE = 0.3f

        fun request(prompt: String): GenerateContentRequest = generateContentRequest(TextPart(prompt)) {
            maxOutputTokens = MAX_OUTPUT_TOKENS
            temperature = TEMPERATURE
        }

        /** Room for the reply as well as the prompt. A non-positive limit means “unknown”: try anyway. */
        fun fitsTokenLimit(promptTokens: Int, limit: Int): Boolean =
            limit <= 0 || promptTokens + MAX_OUTPUT_TOKENS <= limit
    }
}

private suspend fun <T> guarded(fallback: T, block: suspend () -> T): T = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (_: Throwable) {
    fallback
}
