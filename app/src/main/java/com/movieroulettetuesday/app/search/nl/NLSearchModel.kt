package com.movieroulettetuesday.app.search.nl

import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.CancellationException

/** `GenerativeModel.checkStatus()` (FeatureStatus), mapped. Only [Available] shows any AI UI. */
enum class NLModelStatus { Available, Downloadable, Downloading, Unavailable }

/** The on-device model, behind an interface so tests inject fake replies. */
interface NLSearchModel {
    suspend fun status(): NLModelStatus

    /** Raw model text for [prompt], or null. May throw; callers treat any failure as keyword fallback. */
    suspend fun generate(prompt: String): String?

    /** Ask AICore to fetch the model when [status] is [NLModelStatus.Downloadable]. No-op by default. */
    suspend fun download() {}
}

/** Unsupported device, tests, or ML Kit missing: every AI affordance stays hidden. */
object UnavailableNLSearchModel : NLSearchModel {
    override suspend fun status() = NLModelStatus.Unavailable
    override suspend fun generate(prompt: String): String? = null
}

/**
 * Gemini Nano through the ML Kit GenAI Prompt API (`com.google.mlkit:genai-prompt`, beta), on-device only.
 * There is no cloud or Firebase fallback: an unsupported device (e.g. Galaxy Tab SM-X133) reports
 * UNAVAILABLE and search stays keyword-only. Every call is guarded so a missing AICore, an old Play
 * services, or a preview-API change can never crash the app.
 */
class MlKitNLSearchModel : NLSearchModel {
    private val model: GenerativeModel? by lazy { runCatching { Generation.getClient() }.getOrNull() }

    override suspend fun status(): NLModelStatus = guarded(NLModelStatus.Unavailable) {
        val m = model ?: return@guarded NLModelStatus.Unavailable
        when (m.checkStatus()) {
            FeatureStatus.AVAILABLE -> NLModelStatus.Available
            FeatureStatus.DOWNLOADABLE -> NLModelStatus.Downloadable
            FeatureStatus.DOWNLOADING -> NLModelStatus.Downloading
            else -> NLModelStatus.Unavailable
        }
    }

    override suspend fun generate(prompt: String): String? = guarded(null) {
        val m = model ?: return@guarded null
        val request = generateContentRequest(TextPart(prompt)) {
            temperature = 0f
            topK = 1
            candidateCount = 1
            maxOutputTokens = 200
        }
        m.generateContent(request).candidates.firstOrNull()?.text
    }

    override suspend fun download() {
        guarded(Unit) { model?.download()?.collect { } }
    }

    private suspend fun <T> guarded(fallback: T, block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        // GenAiException (BUSY, NOT_AVAILABLE, …), NoClassDefFoundError on a stripped classpath, etc.
        fallback
    }
}
