package com.movieroulettetuesday.app.community

import com.movieroulettetuesday.app.data.KeyValueStore
import com.movieroulettetuesday.app.movies.HalfStars
import com.movieroulettetuesday.app.net.Http
import com.movieroulettetuesday.app.suggest.InAppSuggestion
import java.io.IOException
import java.net.URLEncoder
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Build-time community configuration (BuildConfig from env MRT_CLOUDKIT_API_TOKEN / MRT_RELAY_URL).
 * No token → the community UI is hidden. Token without relay → results are readable, writes are off.
 */
data class CommunityConfig(
    val apiToken: String,
    val relayUrl: String,
    val container: String = CONTAINER,
    val environment: String = "production",
    val cloudKitBase: String = "https://api.apple-cloudkit.com",
) {
    val canRead: Boolean get() = apiToken.isNotEmpty()
    val canWrite: Boolean get() = canRead && relayUrl.isNotEmpty()

    companion object {
        const val CONTAINER = "iCloud.com.movieroulettetuesday.app"
        val disabled = CommunityConfig("", "")

        fun fromBuild(token: String, relay: String): CommunityConfig {
            val t = token.trim()
            val r = relay.trim().trimEnd('/')
            return CommunityConfig(t, if (r.startsWith("https://")) r else "")
        }
    }
}

/** Random per-install identifier (never sent anywhere; only sha256(installId + pollId) leaves the device). */
class InstallId(private val store: KeyValueStore) {
    @get:Synchronized
    val value: String
        get() = store.getString(KEY)?.takeIf { runCatching { UUID.fromString(it) }.isSuccess }
            ?: UUID.randomUUID().toString().lowercase().also { store.putString(KEY, it) }

    companion object { const val KEY = "community.installId" }
}

/** The backend (iOS `CommunityStore`); all calls are suspending and run off the main thread. */
interface CommunityStore {
    val canWrite: Boolean
    /** recordName → option id. */
    suspend fun fetchVotes(pollId: String): Map<String, String>
    /** Returns the saved record name. */
    suspend fun submitVote(pollId: String, option: String): String
    /** recordName → half-stars 1...10. */
    suspend fun fetchRatings(movieId: String): Map<String, Int>
    suspend fun submitRating(movieId: String, halfStars: Int): String
    /**
     * Bulk read for the jackpot averages (iOS 56606a6): movieId → (recordName → half-stars) for the
     * given ids (callers chunk to ≤ 100). Default: one [fetchRatings] per movie.
     */
    suspend fun fetchRatings(movieIds: List<String>): Map<String, Map<String, Int>> = movieIds.associateWith { fetchRatings(it) }
    suspend fun submitSuggestion(suggestion: InAppSuggestion, appVersion: String)
}

/**
 * Option A: reads the CloudKit public database with CloudKit Web Services (read-only API token);
 * writes go through the relay (relay/), which holds the server-to-server key. Same record types,
 * fields and record names as iOS's CloudKitCommunityStore.
 */
class WebCommunityStore(
    private val config: CommunityConfig,
    private val installId: () -> String,
    private val timeoutMs: Int = 20_000,
) : CommunityStore {
    override val canWrite: Boolean get() = config.canWrite

    private val queryUrl: String
        get() = "${config.cloudKitBase}/database/1/${config.container}/${config.environment}/public/records/query?ckAPIToken=" +
            URLEncoder.encode(config.apiToken, "UTF-8")

    override suspend fun fetchVotes(pollId: String): Map<String, String> =
        query(CommunityRecordNames.VOTE_RECORD_TYPE, "pollId", JsonPrimitive(pollId), "STRING", listOf("option")) { fields ->
            fields.string("option")
        }

    override suspend fun fetchRatings(movieId: String): Map<String, Int> =
        query(CommunityRecordNames.RATING_RECORD_TYPE, "movieId", JsonPrimitive(movieId), "STRING", listOf("halfStars", "stars")) { fields ->
            HalfStars.resolved(fields.int("halfStars"), fields.int("stars"))
        }

    /** `ListenerRating WHERE movieId IN <chunk>`, 200 per page, at most 5,000 records per chunk. */
    override suspend fun fetchRatings(movieIds: List<String>): Map<String, Map<String, Int>> {
        if (movieIds.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, MutableMap<String, Int>>()
        val rows = query(CommunityRecordNames.RATING_RECORD_TYPE, "movieId", JsonArray(movieIds.map(::JsonPrimitive)), "STRING_LIST",
            listOf("movieId", "halfStars", "stars"), BULK_MAX_RECORDS) { fields ->
            val movie = fields.string("movieId") ?: return@query null
            HalfStars.resolved(fields.int("halfStars"), fields.int("stars"))?.let { movie to it }
        }
        rows.forEach { (name, pair) -> out.getOrPut(pair.first) { LinkedHashMap() }[name] = pair.second }
        return out
    }

    override suspend fun submitVote(pollId: String, option: String): String {
        val key = CommunityRecordNames.voteKey(pollId, installId())
        val body = buildJsonObject { put("pollId", pollId); put("option", option); put("voterKey", key) }
        return relay("/v1/vote", body) ?: CommunityRecordNames.voteName(pollId, key)
    }

    override suspend fun submitRating(movieId: String, halfStars: Int): String {
        if (halfStars !in HalfStars.range) throw CommunityError.InvalidStars()
        val key = CommunityRecordNames.ratingKey(movieId, installId())
        val body = buildJsonObject { put("movieId", movieId); put("halfStars", halfStars); put("voterKey", key) }
        return relay("/v1/rating", body) ?: CommunityRecordNames.ratingName(movieId, key)
    }

    override suspend fun submitSuggestion(suggestion: InAppSuggestion, appVersion: String) {
        val body = buildJsonObject {
            put("title", suggestion.trimmedTitle)
            put("category", suggestion.trimmedCategory ?: "")
            put("note", suggestion.trimmedNote)
            put("creditName", suggestion.trimmedCreditName)
            put("appVersion", appVersion)
        }
        relay("/v1/suggestion", body)
    }

    // region CloudKit Web Services (records/query)

    private suspend fun <V> query(
        recordType: String,
        field: String,
        value: JsonElement,
        valueType: String,
        desiredKeys: List<String>,
        maxRecords: Int = MAX_RECORDS,
        decode: (JsonObject) -> V?,
    ): Map<String, V> {
        if (!config.canRead) throw CommunityError.NotSetUp()
        val out = LinkedHashMap<String, V>()
        var marker: String? = null
        do {
            val body = buildJsonObject {
                put("zoneID", buildJsonObject { put("zoneName", "_defaultZone") })
                put("resultsLimit", PAGE_SIZE)
                putJsonObject("query") {
                    put("recordType", recordType)
                    putJsonArray("filterBy") {
                        add(buildJsonObject {
                            put("fieldName", field)
                            put("comparator", if (valueType == "STRING_LIST") "IN" else "EQUALS")
                            putJsonObject("fieldValue") { put("value", value); put("type", valueType) }
                        })
                    }
                }
                put("desiredKeys", buildJsonArray { desiredKeys.forEach { add(JsonPrimitive(it)) } })
                marker?.let { put("continuationMarker", it) }
            }
            val json = post(queryUrl, body, cloudKit = true) ?: return out // unknown record type: no records yet
            val records = json["records"] as? JsonArray ?: JsonArray(emptyList())
            for (r in records) {
                val o = r as? JsonObject ?: continue
                if (o["serverErrorCode"] != null) continue
                val name = (o["recordName"] as? JsonPrimitive)?.content ?: continue
                val fields = o["fields"] as? JsonObject ?: continue
                decode(fields)?.let { out[name] = it }
                if (out.size >= maxRecords) return out
            }
            marker = (json["continuationMarker"] as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() }
        } while (marker != null)
        return out
    }

    // endregion

    /** Returns the relay's saved recordName (records/modify shape: `{"records":[{"recordName":…}]}`). */
    private suspend fun relay(path: String, body: JsonObject): String? {
        if (!config.canWrite) throw CommunityError.NotConfigured()
        val json = post(config.relayUrl + path, body, cloudKit = false)
        val first = (json?.get("records") as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
        first["serverErrorCode"]?.let { throw mapServerError(it.str(), first, null) }
        return (first["recordName"] as? JsonPrimitive)?.content
    }

    /** null = "no such record type / item" (treated as no records). */
    private suspend fun post(url: String, body: JsonObject, cloudKit: Boolean): JsonObject? = withContext(Dispatchers.IO) {
        val response = try {
            Http.request("POST", url, body.toString().toByteArray(), "application/json", timeoutMs)
        } catch (e: IOException) {
            throw CommunityError.Offline()
        }
        val json = runCatching { Json.parseToJsonElement(response.body.decodeToString()).jsonObject }.getOrNull()
        val retryHeader = response.headers.entries.firstOrNull { it.key.equals("Retry-After", true) }?.value?.firstOrNull()?.trim()?.toIntOrNull()
        if (response.code in 200..299) return@withContext json ?: throw CommunityError.Service("Unexpected response from the listener service.")
        val code = json?.get("serverErrorCode")?.str() ?: json?.get("error")?.str()
        if (cloudKit && isUnknownRecordType(code, json)) return@withContext null
        throw mapServerError(code, json, retryHeader, response.code)
    }

    companion object {
        const val PAGE_SIZE = 200
        const val MAX_RECORDS = 2000
        const val BULK_MAX_RECORDS = 5000
        const val BULK_CHUNK = 100

        private fun JsonElement.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
        private fun JsonObject.string(k: String) = this[k]?.jsonObjectOrNull()?.get("value")?.str()
        private fun JsonObject.int(k: String): Int? =
            (this[k]?.jsonObjectOrNull()?.get("value") as? JsonPrimitive)?.let { it.intOrNull ?: it.longOrNull?.toInt() }
        private fun JsonElement.jsonObjectOrNull() = this as? JsonObject

        /** Before the schema is deployed CloudKit answers BAD_REQUEST "Did not find record type". */
        private fun isUnknownRecordType(code: String?, json: JsonObject?): Boolean {
            val reason = json?.get("reason")?.str().orEmpty()
            return code == "NOT_FOUND" || code == "UNKNOWN_ITEM" ||
                (code == "BAD_REQUEST" && reason.contains("record type", ignoreCase = true))
        }

        fun mapServerError(code: String?, json: JsonObject?, retryHeader: Int?, http: Int = 0): CommunityError {
            val retry = (json?.get("retryAfter") as? JsonPrimitive)?.intOrNull ?: retryHeader
            return when (code) {
                "THROTTLED", "ZONE_BUSY", "TRY_AGAIN_LATER", "SERVICE_UNAVAILABLE", "rate_limited", "busy" -> CommunityError.Busy(retry)
                "QUOTA_EXCEEDED", "LIMIT_EXCEEDED", "quota" -> CommunityError.QuotaExceeded()
                "AUTHENTICATION_FAILED", "AUTHENTICATION_REQUIRED", "ACCESS_DENIED", "not_configured" -> CommunityError.NotSetUp()
                "invalid_option" -> CommunityError.InvalidOption()
                "invalid_stars" -> CommunityError.InvalidStars()
                else -> when (http) {
                    429, 503 -> CommunityError.Busy(retry)
                    401, 403 -> CommunityError.NotSetUp() // bad/revoked API token (body may be unreadable)
                    else -> CommunityError.Service("Something went wrong. Try again later.")
                }
            }
        }
    }
}
