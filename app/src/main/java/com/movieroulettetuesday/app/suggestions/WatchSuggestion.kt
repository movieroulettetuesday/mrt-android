package com.movieroulettetuesday.app.suggestions

import com.movieroulettetuesday.app.catalog.Movie
import com.movieroulettetuesday.app.catalog.MovieCatalog
import com.movieroulettetuesday.app.movies.MyServices
import com.movieroulettetuesday.app.movies.PersonalRatings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/** Runtime availability of the on-device Gemini Nano / ML Kit GenAI model. */
enum class SuggestionAvailability {
    /** Hide the Roulette-tab button; shortcuts offer Spin. */
    Unavailable,
    /** Button may stay visible and say it isn’t ready yet. */
    Downloadable,
    Downloading,
    Available,
}

sealed class SuggestionOutcome {
    /** A pick. [reason] is written by the app from real data; the model only ever chose the index and a [Vibe]. */
    data class Ready(val movie: Movie, val reason: PickReason) : SuggestionOutcome()
    /** Model is available but temporarily not ready; keep the in-app button. */
    data object NotReady : SuggestionOutcome()
    /** Hide the in-app button (device is not eligible). */
    data object Hidden : SuggestionOutcome()
    /** Say Pick for Me isn’t available (or has nothing to pick) and offer Spin. Do not spin automatically. */
    data object OfferSpin : SuggestionOutcome()
}

/**
 * The on-device model. It returns raw text; [WatchSuggester] parses it strictly ([PickParser]) and
 * never shows it, except as a [Vibe] enum value.
 */
fun interface SuggestionModel {
    suspend fun availability(): SuggestionAvailability
    /** Called once the Roulette screen sees [SuggestionAvailability.Available], so the first tap is warm. */
    suspend fun warmup() {}
    suspend fun generate(prompt: String): String? = null
}

/** Always-unavailable model used under tests and when ML Kit cannot be reached. */
object UnavailableSuggestionModel : SuggestionModel {
    override suspend fun availability() = SuggestionAvailability.Unavailable
}

/** Everything the app knows about this listener that a pick may use. Never leaves the device. */
data class PickInput(
    val ratings: PersonalRatings,
    val recentIds: Set<String>,
    val services: MyServices,
    /** Tie-break seed: one per tap, so equal-scored movies rotate between taps but a seed is reproducible. */
    val seed: Long,
)

/**
 * Pick for Me (plan §2a): the app chooses ≤12 candidates and every fact; the model only picks an
 * index and a vibe. One attempt, [GENERATE_TIMEOUT_MS]; on a timeout or any model failure the app’s
 * own top-scored candidate is shown with a factual reason (never Spin, unless nothing is left to pick).
 */
class WatchSuggester(
    private val model: SuggestionModel,
    private val timeoutMs: Long = GENERATE_TIMEOUT_MS,
    private val catalog: () -> MovieCatalog?,
) {
    /**
     * @param fromShortcut when the model is unavailable, shortcuts offer Spin; the in-app button is hidden.
     */
    suspend fun suggest(input: PickInput, fromShortcut: Boolean): SuggestionOutcome {
        return when (model.availability()) {
            SuggestionAvailability.Unavailable -> if (fromShortcut) SuggestionOutcome.OfferSpin else SuggestionOutcome.Hidden
            SuggestionAvailability.Downloadable, SuggestionAvailability.Downloading -> SuggestionOutcome.NotReady
            SuggestionAvailability.Available -> pick(input)
        }
    }

    private suspend fun pick(input: PickInput): SuggestionOutcome {
        val cat = catalog() ?: return SuggestionOutcome.OfferSpin
        val candidates = PickCandidates.select(cat, input.ratings, input.recentIds, input.services, input.seed)
        if (candidates.isEmpty()) return SuggestionOutcome.OfferSpin
        val raw = withTimeoutOrNull(timeoutMs) {
            try {
                model.generate(PickPrompt.build(candidates, cat, input.ratings, input.services))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                null
            }
        }
        return resolve(raw, candidates, cat, input)
    }

    companion object {
        /** One attempt, no retry (plan §2b, Android). */
        const val GENERATE_TIMEOUT_MS = 3_000L

        /** Spins that count as recent for Pick for Me (plan §2a: the 10 most recent). */
        const val RECENT_SPINS = 10

        /**
         * Turns the model’s raw text (null = timeout / failure) into the outcome. Pure, so the shared
         * `picks` cases run against it directly.
         */
        fun resolve(raw: String?, candidates: List<PickCandidates.Candidate>, catalog: MovieCatalog, input: PickInput): SuggestionOutcome.Ready {
            val parsed = PickParser.parse(raw, candidates.size)
            if (parsed == null) {
                val top = candidates.first()
                return SuggestionOutcome.Ready(top.movie, ReasonComposer.fallback(top.movie, catalog, input.ratings, input.services))
            }
            val chosen = candidates[parsed.index]
            // Defence in depth: any banned phrase anywhere in the model’s text drops its vibe too.
            val banned = BannedPhrases.matches(raw.orEmpty(), BannedPhrases.providerNames(catalog))
            val vibe = if (banned) Vibes.fromGenre(chosen.movie) else Vibes.validated(parsed.vibe, chosen.movie)
            return SuggestionOutcome.Ready(chosen.movie, ReasonComposer.picked(chosen.movie, vibe, catalog, input.ratings, input.services))
        }

        /** Recent spins: the most recently recorded [limit] unique movie ids. */
        fun recentIds(movieIdsNewestFirst: List<String>, limit: Int = RECENT_SPINS): Set<String> =
            movieIdsNewestFirst.distinct().take(limit).toSet()
    }
}
