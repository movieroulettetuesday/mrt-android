package com.movieroulettetuesday.app.roulette

import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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
        return runCatching { json.decodeFromString<Log>(f.readText()) }.getOrDefault(Log())
    }

    private fun save(log: Log) {
        val f = file ?: return
        runCatching {
            f.parentFile?.mkdirs()
            val tmp = File(f.path + ".tmp")
            tmp.writeText(json.encodeToString(Log.serializer(), log))
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
    }

    companion object {
        const val FILE_NAME = "spin-history.json"
        private val json = Json { ignoreUnknownKeys = true }
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
