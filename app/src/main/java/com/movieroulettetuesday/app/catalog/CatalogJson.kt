package com.movieroulettetuesday.app.catalog

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/** Thrown when movies.json doesn't decode (malformed JSON, a missing required key, a wrong type). */
class CatalogDecodingException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Decodes movies.json with the same rules as the iOS `Codable` implementation:
 * - unknown keys (e.g. `_note`) are ignored;
 * - required keys missing or of the wrong type fail the whole catalog;
 * - optional keys may be missing or null; when present they must have the right type
 *   (iOS `decodeIfPresent`), except the newer `tmdbId` / `streaming` fields, which read as null
 *   when malformed so they can never break the wheel.
 */
object CatalogJson {
    private val json = Json { ignoreUnknownKeys = true }

    fun decode(text: String): MovieCatalog {
        val root = try {
            json.parseToJsonElement(text)
        } catch (e: Exception) {
            throw CatalogDecodingException("Not valid JSON", e)
        }
        return catalog(root.obj("root"))
    }

    fun decode(bytes: ByteArray): MovieCatalog = decode(bytes.toString(Charsets.UTF_8))

    private fun catalog(o: JsonObject) = MovieCatalog(
        schemaVersion = o.optInt("schemaVersion") ?: 1,
        show = o.opt("show")?.let { show(it.obj("show")) },
        categories = o.req("categories").arr("categories").map { category(it.obj("category")) },
        movies = o.req("movies").arr("movies").map { movie(it.obj("movie")) },
    )

    private fun show(o: JsonObject) = ShowInfo(
        name = o.reqString("name"),
        hosts = o.req("hosts").arr("hosts").map { it.string("hosts[]") },
        rssURL = o.optString("rssURL"),
        applePodcastsURL = o.optString("applePodcastsURL"),
        spotifyURL = o.optString("spotifyURL"),
        applePodcastID = o.optString("applePodcastID"),
        spotifyShowID = o.optString("spotifyShowID"),
        buzzsproutShowID = o.optString("buzzsproutShowID"),
    )

    private fun category(o: JsonObject) = RouletteCategory(
        id = o.reqString("id"),
        name = o.reqString("name"),
        fullName = o.optString("fullName"),
        colorHex = o.reqString("colorHex"),
        weight = o.optDouble("weight"),
        movieIds = o.optStringList("movieIds") ?: emptyList(),
    )

    private fun movie(o: JsonObject) = Movie(
        id = o.reqString("id"),
        title = o.reqString("title"),
        year = o.optInt("year") ?: throw CatalogDecodingException("Missing key 'year'"),
        genre = o.reqString("genre"),
        categories = o.optStringList("categories") ?: emptyList(),
        episodeIds = o.optStringList("episodeIds") ?: emptyList(),
        episodeTitle = o.optString("episodeTitle"),
        posterURL = o.optString("posterURL"),
        isInfamous = o.opt("isInfamous")?.let {
            (it as? JsonPrimitive)?.takeUnless { p -> p.isString }?.booleanOrNull
                ?: throw CatalogDecodingException("'isInfamous' must be a boolean")
        } ?: false,
        hostRating = o.optDouble("hostRating"),
        tmdbId = runCatching { o.optInt("tmdbId") }.getOrNull(),
        streaming = runCatching { o.opt("streaming")?.let { streaming(it.obj("streaming")) } }.getOrNull(),
    )

    /** Tolerant: lists may be missing or malformed (read as empty); region defaults to US. */
    private fun streaming(o: JsonObject): StreamingInfo {
        fun list(key: String): List<StreamingInfo.Provider> = runCatching {
            o.opt(key)?.arr(key)?.map { provider(it.obj(key)) } ?: emptyList()
        }.getOrDefault(emptyList())
        return StreamingInfo(
            region = runCatching { o.optString("region") }.getOrNull() ?: "US",
            updated = runCatching { o.optString("updated") }.getOrNull(),
            link = runCatching { o.optString("link") }.getOrNull(),
            stream = list("stream"),
            free = list("free"),
            rent = list("rent"),
            buy = list("buy"),
        )
    }

    private fun provider(o: JsonObject) = StreamingInfo.Provider(
        id = o.optInt("id") ?: throw CatalogDecodingException("Missing provider id"),
        name = o.reqString("name"),
        logo = o.optString("logo"),
        priority = o.optInt("priority"),
    )

    // --- helpers -------------------------------------------------------------------------

    private fun JsonElement.obj(what: String): JsonObject =
        this as? JsonObject ?: throw CatalogDecodingException("'$what' must be an object")

    private fun JsonElement.arr(what: String): JsonArray =
        this as? JsonArray ?: throw CatalogDecodingException("'$what' must be an array")

    private fun JsonElement.string(what: String): String {
        val p = this as? JsonPrimitive
        if (p == null || !p.isString) throw CatalogDecodingException("'$what' must be a string")
        return p.content
    }

    /** Present and not null. */
    private fun JsonObject.opt(key: String): JsonElement? = this[key]?.takeUnless { it is JsonNull }

    private fun JsonObject.req(key: String): JsonElement =
        opt(key) ?: throw CatalogDecodingException("Missing key '$key'")

    private fun JsonObject.reqString(key: String): String = req(key).string(key)

    private fun JsonObject.optString(key: String): String? = opt(key)?.string(key)

    private fun JsonObject.optStringList(key: String): List<String>? =
        opt(key)?.arr(key)?.map { it.string("$key[]") }

    private fun JsonObject.optDouble(key: String): Double? = opt(key)?.let {
        (it as? JsonPrimitive)?.takeUnless { p -> p.isString }?.doubleOrNull
            ?: throw CatalogDecodingException("'$key' must be a number")
    }

    /** Integer; like Swift's JSONDecoder, 1983.0 is accepted but 1983.5 is not. */
    private fun JsonObject.optInt(key: String): Int? = opt(key)?.let {
        val d = (it as? JsonPrimitive)?.takeUnless { p -> p.isString }?.doubleOrNull
        if (d == null || d % 1.0 != 0.0 || d > Int.MAX_VALUE || d < Int.MIN_VALUE) {
            throw CatalogDecodingException("'$key' must be an integer")
        }
        d.toInt()
    }
}
