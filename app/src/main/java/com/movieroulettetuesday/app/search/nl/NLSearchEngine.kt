package com.movieroulettetuesday.app.search.nl

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** Why a query stayed on keyword CatalogSearch, or the filter to apply. */
sealed interface NLOutcome {
    data class Parsed(val filter: NLSearchFilter) : NLOutcome
    data class Keyword(val reason: Reason) : NLOutcome

    enum class Reason { NotAQuestion, ModelUnavailable, Timeout, ModelError, Invalid, EmptyFilter }
}

/**
 * Turns one search-field value into an [NLOutcome]. The caller runs [resolve] in a coroutine keyed on the
 * query (Compose `LaunchedEffect(query)`), so a new keystroke cancels the previous debounce or in-flight
 * parse. Typing is never blocked: the list shows keyword CatalogSearch until a parse lands.
 *
 * Order: debounce (~400 ms) → plain-keyword check → `checkStatus()` (must be AVAILABLE) → model with a
 * ~2 s timeout → strict validation. Every failure is [NLOutcome.Keyword]; nothing is shown as an error.
 */
class NLSearchEngine(
    private val model: NLSearchModel,
    private val providerNames: () -> List<String>,
    val debounceMs: Long = DEBOUNCE_MS,
    val timeoutMs: Long = TIMEOUT_MS,
) {
    suspend fun status(): NLModelStatus = runCatchingNonCancel { model.status() } ?: NLModelStatus.Unavailable

    suspend fun resolve(query: String): NLOutcome {
        if (query.isBlank()) return NLOutcome.Keyword(NLOutcome.Reason.NotAQuestion)
        delay(debounceMs)
        if (!NLQuestionDetector.looksLikeQuestion(query)) return NLOutcome.Keyword(NLOutcome.Reason.NotAQuestion)
        if (status() != NLModelStatus.Available) return NLOutcome.Keyword(NLOutcome.Reason.ModelUnavailable)
        // Wrapped so "timed out" (null) differs from "model returned nothing or threw".
        val reply: Result<String?> = withTimeoutOrNull(timeoutMs) {
            try {
                Result.success(model.generate(NLSearchPrompt.build(query)))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                Result.failure(e)
            }
        } ?: return NLOutcome.Keyword(NLOutcome.Reason.Timeout)
        val text = reply.getOrNull() ?: return NLOutcome.Keyword(NLOutcome.Reason.ModelError)
        return when (val parse = NLSearchValidator.parse(text, providerNames())) {
            is NLParse.Filter -> NLOutcome.Parsed(parse.filter)
            NLParse.Empty -> NLOutcome.Keyword(NLOutcome.Reason.EmptyFilter)
            is NLParse.Rejected -> NLOutcome.Keyword(NLOutcome.Reason.Invalid)
        }
    }

    private suspend fun <T> runCatchingNonCancel(block: suspend () -> T): T? = try {
        block()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Throwable) {
        null
    }

    companion object {
        const val DEBOUNCE_MS = 400L
        const val TIMEOUT_MS = 2_000L
    }
}

/** The shared list-switch rule (normative, both apps), as one pure function. */
object NLListSwitch {
    /**
     * The list to switch to, or null to stay. `kind` null/any stays; a `kind` naming the list already
     * showing stays (no reset); a `kind` naming the other list switches. Only a validated parse on an
     * AVAILABLE model ever reaches this, so an empty parse or an unavailable model never switches.
     */
    fun target(kind: NLSearchFilter.Kind?, current: NLSearchFilter.Kind): NLSearchFilter.Kind? =
        kind?.takeIf { it != current }
}
