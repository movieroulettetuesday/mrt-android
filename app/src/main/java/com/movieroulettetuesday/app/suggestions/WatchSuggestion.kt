package com.movieroulettetuesday.app.suggestions

import com.movieroulettetuesday.app.catalog.Movie
import com.movieroulettetuesday.app.catalog.MovieCatalog
import com.movieroulettetuesday.app.movies.HalfStars
import com.movieroulettetuesday.app.movies.MyServices
import com.movieroulettetuesday.app.movies.PersonalRatings
import com.movieroulettetuesday.app.movies.StreamingCatalog
import com.movieroulettetuesday.app.movies.WatchStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Runtime availability of the on-device Gemini Nano / ML Kit GenAI model. */
enum class SuggestionAvailability {
    /** Hide the Roulette-tab button; shortcuts offer Spin. */
    Unavailable,
    /** Button may stay visible and say it isn’t ready yet. */
    Downloadable,
    Downloading,
    Available,
}

data class MovieContextRow(
    val id: String,
    val title: String,
    val year: Int,
    val genre: String,
    val categories: List<String>,
    val onWatchlist: Boolean,
    val halfStars: Int?,
    val recentlySpun: Boolean,
    val onServices: List<String>,
)

/**
 * Compact on-device prompt context: public catalog fields plus this device’s watchlist, rating,
 * recent-spin flag, and My Services. Never includes plots, raw `movies.json`, network URLs,
 * other listeners’ ratings, or spin-history details beyond a boolean.
 */
data class SuggestionContext(val movies: List<MovieContextRow>) {
    fun asPromptLines(): List<String> = movies.map { row ->
        buildString {
            append(row.id)
            append('|')
            append(row.title)
            append('|')
            append(row.year)
            append('|')
            append(row.genre)
            append('|')
            append(row.categories.joinToString(","))
            if (row.onWatchlist) append("|watchlist")
            row.halfStars?.let { append("|rated:${HalfStars.label(it)}") }
            if (row.recentlySpun) append("|recent")
            if (row.onServices.isNotEmpty()) append("|services:${row.onServices.joinToString(",")}")
        }
    }
}

data class WatchSuggestion(val movieId: String, val why: String)

sealed class SuggestionOutcome {
    data class Ready(val movie: Movie, val why: String) : SuggestionOutcome()
    /** Model is available but temporarily not ready; keep the in-app button. */
    data object NotReady : SuggestionOutcome()
    /** Hide the in-app button (device is not eligible). */
    data object Hidden : SuggestionOutcome()
    /** Say suggestions aren’t available (or failed) and offer Spin. Do not spin automatically. */
    data object OfferSpin : SuggestionOutcome()
}

fun interface SuggestionModel {
    suspend fun availability(): SuggestionAvailability
    suspend fun generate(prompt: String): WatchSuggestion? = null
}

/** Always-unavailable model used under tests and when ML Kit cannot be reached. */
object UnavailableSuggestionModel : SuggestionModel {
    override suspend fun availability() = SuggestionAvailability.Unavailable
}

class WatchSuggester(
    private val model: SuggestionModel,
    private val catalog: () -> MovieCatalog?,
) {
    fun buildContext(
        ratings: PersonalRatings,
        recentIds: Set<String>,
        services: MyServices,
    ): SuggestionContext? {
        val cat = catalog() ?: return null
        val rows = cat.movies.map { m ->
            MovieContextRow(
                id = m.id,
                title = m.title,
                year = m.year,
                genre = m.genre,
                categories = m.categories,
                onWatchlist = ratings.status(m.id) == WatchStatus.WantToWatch,
                halfStars = ratings.halfStars(m.id),
                recentlySpun = m.id in recentIds,
                onServices = StreamingCatalog.matchingServices(m, services).map { it.name },
            )
        }
        return SuggestionContext(rows)
    }

    fun prompt(ctx: SuggestionContext): String = buildString {
        appendLine(INSTRUCTIONS)
        appendLine("Catalog (id|title|year|genre|categories|flags):")
        ctx.asPromptLines().forEach { appendLine(it) }
        appendLine("Reply with JSON only: {\"movieId\":\"<exact catalog id>\",\"why\":\"<one or two sentences>\"}")
    }

    /**
     * @param fromShortcut when the model is unavailable, shortcuts offer Spin; the in-app button is hidden.
     */
    suspend fun suggest(ctx: SuggestionContext, fromShortcut: Boolean): SuggestionOutcome {
        return when (val avail = model.availability()) {
            SuggestionAvailability.Unavailable -> if (fromShortcut) SuggestionOutcome.OfferSpin else SuggestionOutcome.Hidden
            SuggestionAvailability.Downloadable, SuggestionAvailability.Downloading -> SuggestionOutcome.NotReady
            SuggestionAvailability.Available -> generateValidated(ctx)
        }
    }

    private suspend fun generateValidated(ctx: SuggestionContext): SuggestionOutcome {
        val cat = catalog() ?: return SuggestionOutcome.OfferSpin
        val text = prompt(ctx)
        repeat(2) {
            val raw = runCatching { model.generate(text) }.getOrNull() ?: return@repeat
            val movie = cat.movie(raw.movieId.trim())
            if (movie != null) {
                val why = raw.why.trim()
                if (why.isNotEmpty()) return SuggestionOutcome.Ready(movie, why)
            }
        }
        return SuggestionOutcome.OfferSpin
    }

    companion object {
        const val INSTRUCTIONS =
            "Pick one catalog movie for tonight. Prefer watchlist titles that are on the listener’s services. " +
                "Weigh their ratings. Avoid movies flagged recent. Do not invent a movie or an id: copy movieId " +
                "exactly from the catalog. Write a short why (one or two sentences) that may mention facts already " +
                "in the context, such as the watchlist or a service. One suggestion, not a chat. On-device only."

        private val json = Json { ignoreUnknownKeys = true }

        fun parseResponse(text: String?): WatchSuggestion? {
            if (text.isNullOrBlank()) return null
            val trimmed = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val obj = runCatching { json.parseToJsonElement(trimmed).jsonObject }.getOrNull() ?: return null
            val id = obj["movieId"]?.jsonPrimitive?.content?.trim().orEmpty()
            val why = obj["why"]?.jsonPrimitive?.content?.trim().orEmpty()
            if (id.isEmpty()) return null
            return WatchSuggestion(id, why)
        }

        /** Recent spins: the most recently recorded [limit] unique movie ids. */
        fun recentIds(movieIdsNewestFirst: List<String>, limit: Int = 8): Set<String> =
            movieIdsNewestFirst.distinct().take(limit).toSet()
    }
}
