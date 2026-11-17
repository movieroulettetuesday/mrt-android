package com.movieroulettetuesday.app.search.nl

import com.movieroulettetuesday.app.movies.HalfStars
import com.movieroulettetuesday.app.search.CatalogSearch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.floor

/** What became of one model reply. */
sealed interface NLParse {
    /** A non-empty, validated filter: run [NLSearchApply]. */
    data class Filter(val filter: NLSearchFilter) : NLParse
    /** Valid reply with nothing set (or nothing left after validation): keyword CatalogSearch. */
    data object Empty : NLParse
    /** Not a JSON object at all: keyword CatalogSearch. */
    data class Rejected(val reason: String) : NLParse
}

/**
 * Strict mapper from a model reply to [NLSearchFilter], field for field with the shared schema.
 *
 * The Prompt API cannot constrain values, so nothing passes through unchecked:
 * - The reply must contain one JSON object (a Markdown code fence or a stray sentence around it is
 *   tolerated); anything else is [NLParse.Rejected].
 * - Unknown keys are ignored. A field with the wrong type or an unknown value (genre, category, kind,
 *   watched, or a provider that does not match any catalog stream/free name) is nulled, so it can never reach
 *   apply; the rest of the reply is kept (shared cases `unknown-genre-animation`, `unknown-provider-blockbuster`).
 * - Out-of-range values are dropped, never clamped (Matthew, 17 Nov 2026). Years: each kept only if it is an
 *   integer in 1880–2100, then swapped if reversed. Ratings: each dropped if not finite or if the raw value is
 *   outside 0.5–5 (checked before rounding, so 5.2 is dropped); in range, rounded to the nearest half star with
 *   ties away from zero (3.25 → 3.5), then swapped if reversed.
 *   `unrated: true` drops min/max; `unrated: false` is kept ("has any rating"), with or without min/max.
 * - `kind: any`, `watched: any`, and `false` for freeOnly / onMyServices mean "no filter" and become null.
 */
object NLSearchValidator {
    const val YEAR_MIN = 1880
    const val YEAR_MAX = 2100

    private val json = Json { isLenient = false }

    fun parse(reply: String?, providerNames: List<String>): NLParse {
        val obj = extractObject(reply) ?: return NLParse.Rejected("no JSON object")
        val filter = validate(obj, providerNames)
        return if (filter.isEmpty) NLParse.Empty else NLParse.Filter(filter)
    }

    /** The first balanced `{…}` in [reply], parsed; null if there is none or it is not valid JSON. */
    internal fun extractObject(reply: String?): JsonObject? {
        if (reply.isNullOrBlank()) return null
        val start = reply.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        var end = -1
        for (i in start until reply.length) {
            val c = reply[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) { end = i; break } }
            }
        }
        if (end < 0) return null
        return runCatching { json.parseToJsonElement(reply.substring(start, end + 1)) as? JsonObject }.getOrNull()
    }

    fun validate(obj: JsonObject, providerNames: List<String>): NLSearchFilter {
        val title = string(obj["title"])?.trim()?.takeIf { CatalogSearch.fold(it).isNotEmpty() }
        val kind = when (string(obj["kind"])?.trim()?.lowercase()) {
            "movie" -> NLSearchFilter.Kind.Movie
            "episode" -> NLSearchFilter.Kind.Episode
            else -> null
        }
        var yearMin = year(obj["yearMin"])
        var yearMax = year(obj["yearMax"])
        if (yearMin != null && yearMax != null && yearMin > yearMax) yearMin = yearMax.also { yearMax = yearMin }
        val genre = string(obj["genre"])?.let(NLSearchVocab::genre)
        val category = string(obj["category"])?.let(NLSearchVocab::category)?.name
        val provider = string(obj["provider"])?.trim()?.takeIf { isKnownProvider(it, providerNames) }
        val freeOnly = bool(obj["freeOnly"])?.takeIf { it }
        val unrated = bool(obj["unrated"])
        var ratingMin = rating(obj["ratingMin"])
        var ratingMax = rating(obj["ratingMax"])
        if (ratingMin != null && ratingMax != null && ratingMin > ratingMax) ratingMin = ratingMax.also { ratingMax = ratingMin }
        if (unrated == true) { ratingMin = null; ratingMax = null }
        val unratedOut = unrated
        val watched = when (string(obj["watched"])?.trim()?.lowercase()) {
            "watched" -> NLSearchFilter.Watched.Watched
            "unwatched" -> NLSearchFilter.Watched.Unwatched
            "watchlist" -> NLSearchFilter.Watched.Watchlist
            else -> null
        }
        val season = int(obj["season"])?.takeIf { it in 1..999 }
        val episode = int(obj["episode"])?.takeIf { it in 1..9999 }
        val onMyServices = bool(obj["onMyServices"])?.takeIf { it }
        return NLSearchFilter(
            title = title, kind = kind, yearMin = yearMin, yearMax = yearMax, genre = genre, category = category,
            provider = provider, freeOnly = freeOnly, ratingMin = ratingMin, ratingMax = ratingMax, unrated = unratedOut,
            watched = watched, season = season, episode = episode, onMyServices = onMyServices,
        )
    }

    /** Kept only if [NLSearchVocab.providerMatches] matches at least one catalog stream/free name ("Max" → "HBO Max"). */
    fun isKnownProvider(raw: String, providerNames: List<String>): Boolean =
        providerNames.any { NLSearchVocab.providerMatches(it, raw) }

    private fun string(e: JsonElement?): String? = (e as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content

    private fun bool(e: JsonElement?): Boolean? = (e as? JsonPrimitive)?.takeIf { it !is JsonNull && !it.isString }?.booleanOrNull

    private fun number(e: JsonElement?): Double? = (e as? JsonPrimitive)?.takeIf { it !is JsonNull && !it.isString }?.doubleOrNull
        ?.takeIf { it.isFinite() }

    private fun int(e: JsonElement?): Int? = number(e)?.takeIf { it == floor(it) && it in -1e9..1e9 }?.toInt()

    private fun year(e: JsonElement?): Int? = int(e)?.takeIf { it in YEAR_MIN..YEAR_MAX }

    /** Range check on the raw value, then half-star rounding (ties away from zero). */
    private fun rating(e: JsonElement?): Double? = number(e)?.takeIf { it in 0.5..5.0 }?.let(HalfStars::roundedToHalf)
}
