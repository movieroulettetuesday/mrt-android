package com.movieroulettetuesday.app.roulette

import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import com.movieroulettetuesday.app.data.CorruptFileQuarantine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** One spin, logged locally on this device. */
@Serializable
data class SpinResult(
    val id: String = UUID.randomUUID().toString(),
    val movieId: String,
    val categoryId: String,
    val timestampEpochMs: Long = System.currentTimeMillis(),
)

/**
 * On-device spin history (iOS SpinHistoryLog / SpinHistoryStore, simplified): the most recent
 * [detailLimit] spins are kept in detail; older ones are folded into per-movie / per-category counts.
 * [file] null = in-memory only (tests, previews).
 */
class SpinHistoryStore(private val file: File?, private val detailLimit: Int = 500) {
    @Serializable
    data class Log(
        val version: Int = 2,
        val results: List<SpinResult> = emptyList(),
        val archivedTotal: Int = 0,
        val archivedMovieCounts: Map<String, Int> = emptyMap(),
        val archivedCategoryCounts: Map<String, Int> = emptyMap(),
    )

    private val _log = MutableStateFlow(load())
    val log: StateFlow<Log> = _log.asStateFlow()

    val results: List<SpinResult> get() = _log.value.results
    val totalSpins: Int get() = _log.value.archivedTotal + _log.value.results.size

    fun spinCount(movieId: String): Int =
        (_log.value.archivedMovieCounts[movieId] ?: 0) + _log.value.results.count { it.movieId == movieId }

    @Synchronized
    fun record(result: SpinResult) {
        var log = _log.value
        val all = log.results + result
        if (all.size > detailLimit) {
            val overflow = all.take(all.size - detailLimit)
            val movies = log.archivedMovieCounts.toMutableMap()
            val cats = log.archivedCategoryCounts.toMutableMap()
            overflow.forEach {
                movies[it.movieId] = (movies[it.movieId] ?: 0) + 1
                cats[it.categoryId] = (cats[it.categoryId] ?: 0) + 1
            }
            log = log.copy(
                results = all.takeLast(detailLimit),
                archivedTotal = log.archivedTotal + overflow.size,
                archivedMovieCounts = movies,
                archivedCategoryCounts = cats,
            )
        } else {
            log = log.copy(results = all)
        }
        _log.value = log
        save(log)
    }

    private fun load(): Log {
        val f = file ?: return Log()
        if (!f.exists()) return Log()
        val decoded = runCatching { SpinHistoryCodec.decode(f.readText()) }.getOrNull()
        if (decoded == null) {
            // Unreadable: keep it aside (never silently overwritten) and start empty (iOS behavior).
            CorruptFileQuarantine.quarantine(f)
            return Log()
        }
        return decoded
    }

    private fun save(log: Log) {
        val f = file ?: return
        runCatching {
            f.parentFile?.mkdirs()
            val tmp = File(f.path + ".tmp")
            tmp.writeText(SpinHistoryCodec.encode(log))
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
    }

    companion object {
        const val FILE_NAME = "spin-history.json"
    }
}

/** Per-device numbers derived from spin history, archive included. Port of `SpinStats`. */
class SpinStats(log: SpinHistoryStore.Log) {
    val totalSpins: Int = log.archivedTotal + log.results.size
    val movieCounts: Map<String, Int>
    val categoryCounts: Map<String, Int>

    init {
        val movies = log.archivedMovieCounts.toMutableMap()
        val cats = log.archivedCategoryCounts.toMutableMap()
        log.results.forEach {
            movies[it.movieId] = (movies[it.movieId] ?: 0) + 1
            cats[it.categoryId] = (cats[it.categoryId] ?: 0) + 1
        }
        movieCounts = movies
        categoryCounts = cats
    }

    fun spinCount(movieId: String): Int = movieCounts[movieId] ?: 0
    fun hasBeenSpun(movieId: String): Boolean = spinCount(movieId) > 0
    fun categorySpinCount(categoryId: String): Int = categoryCounts[categoryId] ?: 0
    val distinctMoviesSpun: Int get() = movieCounts.size
}

/**
 * The iOS on-disk format (SpinHistoryLog v2): `{"version":2,"results":[...],"archive":{totalSpins,
 * movieCounts,categoryCounts}}`, timestamps in seconds since 2001-01-01 (Apple reference date). Also
 * reads the legacy bare array and Android's pre-contract format (archivedTotal…, epoch ms).
 * Malformed entries are skipped one by one; a document that isn't a log at all returns null.
 */
object SpinHistoryCodec {
    /** 2001-01-01T00:00:00Z in epoch ms. */
    const val REFERENCE_DATE_MS = 978_307_200_000L
    private val json = Json { ignoreUnknownKeys = true }

    fun decode(text: String): SpinHistoryStore.Log? {
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() ?: return null
        return when (root) {
            is JsonArray -> SpinHistoryStore.Log(results = lossy(root))
            is JsonObject -> {
                val results = (root["results"] as? JsonArray)?.let(::lossy).orEmpty()
                val archive = root["archive"] as? JsonObject
                if (archive != null || root["archivedTotal"] == null) {
                    SpinHistoryStore.Log(
                        results = results,
                        archivedTotal = maxOf(0, archive?.int("totalSpins") ?: 0),
                        archivedMovieCounts = archive?.counts("movieCounts").orEmpty(),
                        archivedCategoryCounts = archive?.counts("categoryCounts").orEmpty(),
                    )
                } else {
                    SpinHistoryStore.Log(
                        results = results,
                        archivedTotal = maxOf(0, root.int("archivedTotal") ?: 0),
                        archivedMovieCounts = root.counts("archivedMovieCounts").orEmpty(),
                        archivedCategoryCounts = root.counts("archivedCategoryCounts").orEmpty(),
                    )
                }
            }
            else -> null
        }
    }

    fun encode(log: SpinHistoryStore.Log): String = buildJsonObject {
        put("version", 2)
        put("results", buildJsonArray {
            log.results.forEach { r ->
                add(buildJsonObject {
                    put("categoryId", r.categoryId)
                    put("id", r.id)
                    put("movieId", r.movieId)
                    put("timestamp", (r.timestampEpochMs - REFERENCE_DATE_MS) / 1000.0)
                })
            }
        })
        put("archive", buildJsonObject {
            put("categoryCounts", JsonObject(log.archivedCategoryCounts.toSortedMap().mapValues { JsonPrimitive(it.value) }))
            put("movieCounts", JsonObject(log.archivedMovieCounts.toSortedMap().mapValues { JsonPrimitive(it.value) }))
            put("totalSpins", log.archivedTotal)
        })
    }.toString()

    private fun lossy(array: JsonArray): List<SpinResult> = array.mapNotNull(::result)

    private fun result(e: JsonElement): SpinResult? {
        val o = e as? JsonObject ?: return null
        val id = o.str("id") ?: return null
        val movieId = o.str("movieId") ?: return null
        val categoryId = o.str("categoryId") ?: return null
        val ms = (o["timestamp"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.let { REFERENCE_DATE_MS + Math.round(it * 1000) }
            ?: (o["timestampEpochMs"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
            ?: return null
        return SpinResult(id, movieId, categoryId, ms)
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
    private fun JsonObject.counts(key: String): Map<String, Int>? =
        (this[key] as? JsonObject)?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull?.let { k to it } }?.toMap()
}
