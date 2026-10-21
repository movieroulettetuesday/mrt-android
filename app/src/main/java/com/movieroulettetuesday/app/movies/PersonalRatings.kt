package com.movieroulettetuesday.app.movies

import com.movieroulettetuesday.app.catalog.Movie
import com.movieroulettetuesday.app.catalog.MovieCatalog
import com.movieroulettetuesday.app.data.AtomicFile
import com.movieroulettetuesday.app.roulette.SpinStats
import java.io.File
import kotlin.math.floor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Port of `WatchStatus` (raw values match iOS). */
enum class WatchStatus(val raw: String, val label: String) {
    WantToWatch("wantToWatch", "Want to watch"),
    Watched("watched", "Watched");

    companion object {
        fun fromRaw(raw: String?): WatchStatus? = entries.firstOrNull { it.raw == raw }
    }
}

/** Half-star ratings 1..10 (0.5..5 stars). Port of `HalfStars`. */
object HalfStars {
    val range = 1..10

    fun clamped(halfStars: Int?): Int? = halfStars?.takeIf { it > 0 }?.coerceIn(range)

    fun fromLegacyStars(stars: Int?): Int? = stars?.takeIf { it > 0 }?.coerceIn(1, 5)?.times(2)

    fun resolved(halfStars: Int?, legacyStars: Int?): Int? = clamped(halfStars) ?: fromLegacyStars(legacyStars)

    fun roundedStars(halfStars: Int): Int = (((clamped(halfStars) ?: 1) + 1) / 2).coerceIn(1, 5)

    fun stars(halfStars: Int): Double = halfStars / 2.0

    /** Half-star precision, halves rounded away from zero (4.25 -> 4.5). */
    fun roundedToHalf(stars: Double): Double {
        if (!stars.isFinite()) return 0.0
        val x = stars * 2
        val r = if (x >= 0) floor(x + 0.5) else -floor(-x + 0.5)
        return r / 2
    }

    fun label(halfStars: Int): String = if (halfStars % 2 == 0) "${halfStars / 2}" else "${halfStars / 2}.5"

    fun label(stars: Double): String = label(Math.round(roundedToHalf(stars) * 2).toInt())

    /** Left half of a star gives .5, the right half the whole star. Always 1..10. */
    fun fromLocation(x: Double, width: Double): Int {
        if (width <= 0 || !x.isFinite()) return 1
        val f = (x / width).coerceIn(0.0, 1.0)
        return clamped(Math.ceil(f * 10).toInt()) ?: 1
    }

    fun fill(star: Int, halfStars: Int?): Double {
        val h = halfStars ?: 0
        return when {
            h >= star * 2 -> 1.0
            h == star * 2 - 1 -> 0.5
            else -> 0.0
        }
    }

    /** Accessibility adjust: ±0.5 star; decrementing below 0.5 clears. */
    fun adjusted(halfStars: Int?, increment: Boolean): Int? {
        val h = halfStars ?: 0
        return if (increment) minOf(h + 1, range.last) else if (h <= 1) null else h - 1
    }
}

data class PersonalMovieEntry(val halfStars: Int? = null, val status: WatchStatus? = null, val updatedAt: Long = 0) {
    val isEmpty: Boolean get() = halfStars == null && status == null
    val rating: Int? get() = halfStars?.let(HalfStars::roundedStars)
}

/** Immutable set of personal entries keyed by movie id. Port of `PersonalRatings`. */
data class PersonalRatings(val entries: Map<String, PersonalMovieEntry> = emptyMap()) {
    fun entry(movieId: String) = entries[movieId]
    fun halfStars(movieId: String) = entries[movieId]?.halfStars
    fun rating(movieId: String) = entries[movieId]?.rating
    fun status(movieId: String) = entries[movieId]?.status

    fun withHalfStars(halfStars: Int?, movieId: String, now: Long): PersonalRatings {
        var e = (entries[movieId] ?: PersonalMovieEntry(updatedAt = now)).copy(halfStars = HalfStars.clamped(halfStars))
        // Rating a movie implies you've seen it.
        if (e.halfStars != null && (e.status == null || e.status == WatchStatus.WantToWatch)) e = e.copy(status = WatchStatus.Watched)
        return store(e.copy(updatedAt = now), movieId)
    }

    fun withStatus(status: WatchStatus?, movieId: String, now: Long): PersonalRatings =
        store((entries[movieId] ?: PersonalMovieEntry(updatedAt = now)).copy(status = status, updatedAt = now), movieId)

    private fun store(e: PersonalMovieEntry, movieId: String) =
        PersonalRatings(if (e.isEmpty) entries - movieId else entries + (movieId to e))

    val watchlist: Set<String> get() = entries.filterValues { it.status == WatchStatus.WantToWatch }.keys
    val watched: Set<String> get() = entries.filterValues { it.status == WatchStatus.Watched }.keys

    companion object {
        private val json = Json { prettyPrint = false }

        /** Tolerant decode: bad entries are skipped, legacy whole-star `rating` is read ×2. */
        fun decode(text: String): PersonalRatings {
            val root = json.parseToJsonElement(text).jsonObject
            val entries = root["entries"] as? JsonObject ?: return PersonalRatings()
            val out = HashMap<String, PersonalMovieEntry>()
            for ((id, el) in entries) {
                val o = el as? JsonObject ?: continue
                fun int(k: String): Int? = (o[k] as? JsonPrimitive)?.doubleOrNull?.takeIf { it == floor(it) }?.toInt()
                val e = PersonalMovieEntry(
                    halfStars = HalfStars.resolved(int("halfStars"), int("rating")),
                    status = WatchStatus.fromRaw((o["status"] as? JsonPrimitive)?.takeIf { it.isString }?.content),
                    updatedAt = (o["updatedAt"] as? JsonPrimitive)?.longOrNull ?: 0,
                )
                if (!e.isEmpty) out[id] = e
            }
            return PersonalRatings(out)
        }

        /** Writes `halfStars` and the rounded `rating` (like iOS, so older readers still work). */
        fun encode(r: PersonalRatings): String = buildJsonObject {
            put("entries", buildJsonObject {
                for (id in r.entries.keys.sorted()) {
                    val e = r.entries.getValue(id)
                    put(id, buildJsonObject {
                        e.halfStars?.let { put("halfStars", it) }
                        e.rating?.let { put("rating", it) }
                        e.status?.let { put("status", it.raw) }
                        put("updatedAt", e.updatedAt)
                    })
                }
            })
        }.toString()
    }
}

/** "Your ratings" numbers for Stats. Port of `PersonalRatingsSummary`. */
data class PersonalRatingsSummary(
    val ratedCount: Int,
    val averageStars: Double?,
    val topRated: List<Rated>,
    val watchlistCount: Int,
    val watchedCount: Int,
) {
    data class Rated(val movieId: String, val title: String, val halfStars: Int)

    companion object {
        fun of(ratings: PersonalRatings, catalog: MovieCatalog?, topLimit: Int = 5): PersonalRatingsSummary {
            val rated = ratings.entries.mapNotNull { (id, e) ->
                e.halfStars?.let { Rated(id, catalog?.movie(id)?.title ?: id, it) }
            }
            return PersonalRatingsSummary(
                ratedCount = rated.size,
                averageStars = if (rated.isEmpty()) null else rated.sumOf { it.halfStars } / 2.0 / rated.size,
                topRated = rated.sortedWith(compareByDescending<Rated> { it.halfStars }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.title })
                    .take(maxOf(0, topLimit)),
                watchlistCount = ratings.watchlist.size,
                watchedCount = ratings.watched.size,
            )
        }
    }
}

/**
 * Local JSON store (filesDir/personal-ratings.json); never leaves the device. An unreadable file is
 * kept as a `.corrupt-<time>` backup, never silently overwritten (iOS CorruptFileQuarantine).
 */
class PersonalRatingsStore(private val file: File?, private val clock: () -> Long = System::currentTimeMillis) {
    private val _ratings = MutableStateFlow(PersonalRatings())
    val ratings: StateFlow<PersonalRatings> = _ratings.asStateFlow()
    var lastError: String? = null
        private set

    init {
        if (file != null && file.isFile) {
            try {
                _ratings.value = PersonalRatings.decode(file.readText())
            } catch (_: Exception) {
                val backup = File(file.path + ".corrupt-" + clock())
                lastError = if (file.renameTo(backup)) {
                    "Your saved ratings couldn't be read. A backup was kept and ratings were reset."
                } else {
                    "Your saved ratings couldn't be read, so they were reset."
                }
            }
        }
    }

    @Synchronized
    fun setHalfStars(halfStars: Int?, movieId: String) = update { it.withHalfStars(halfStars, movieId, clock()) }

    @Synchronized
    fun setStatus(status: WatchStatus?, movieId: String) = update { it.withStatus(status, movieId, clock()) }

    private fun update(f: (PersonalRatings) -> PersonalRatings) {
        val next = f(_ratings.value)
        _ratings.value = next
        val file = file ?: return
        lastError = try {
            AtomicFile.write(file, PersonalRatings.encode(next).toByteArray())
            null
        } catch (e: Exception) {
            "Couldn’t save your ratings: ${e.message}"
        }
    }

    companion object {
        const val FILE_NAME = "personal-ratings.json"
    }
}

/** Movies-tab list filter. Port of `MovieListFilter`. */
enum class MovieListFilter(val label: String) {
    All("All movies"), Watchlist("Watchlist"), Watched("Watched"), Rated("Rated by me"), NotSpunYet("Not spun yet");

    fun apply(movies: List<Movie>, ratings: PersonalRatings, stats: SpinStats): List<Movie> = when (this) {
        All -> movies
        Watchlist -> movies.filter { ratings.status(it.id) == WatchStatus.WantToWatch }
        Watched -> movies.filter { ratings.status(it.id) == WatchStatus.Watched }
        Rated -> movies.filter { ratings.halfStars(it.id) != null }
        NotSpunYet -> movies.filter { !stats.hasBeenSpun(it.id) }
    }
}
