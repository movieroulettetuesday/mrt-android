package com.movieroulettetuesday.app.movies

import com.movieroulettetuesday.app.catalog.Movie
import com.movieroulettetuesday.app.catalog.StreamingInfo
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The user's streaming services (provider ids), saved locally as "8,9,337". Port of `MyServices`. */
data class MyServices(val ids: Set<Int> = emptySet()) {
    val serialized: String get() = ids.sorted().joinToString(",")
    val isEmpty: Boolean get() = ids.isEmpty()
    fun contains(id: Int) = id in ids
    fun toggled(id: Int) = MyServices(if (id in ids) ids - id else ids + id)

    companion object {
        const val STORAGE_KEY = "streaming.myServices"
        fun parse(serialized: String?): MyServices =
            MyServices(serialized.orEmpty().split(",").mapNotNull { it.trim().toIntOrNull() }.toSet())
    }
}

/** What "Where to Watch" shows (always present). Port of `WhereToWatchDisplay`. */
data class WhereToWatchDisplay(val region: String, val showsProviders: Boolean, val link: String?, val footer: String?) {
    val showsEmptyMessage: Boolean get() = !showsProviders

    companion object {
        const val EMPTY_MESSAGE = "No streaming sources found."

        fun of(movie: Movie): WhereToWatchDisplay {
            val s = movie.streaming
            val region = s?.region ?: "US"
            val shows = !(s?.isEmpty ?: true)
            val link = s?.link?.takeIf { it.startsWith("https://") }
                ?: movie.tmdbId?.let { "https://www.themoviedb.org/movie/$it/watch?locale=$region" }
            val footer = if (link != null || shows) {
                StreamingCatalog.updatedLabel(s?.updated)?.let { "${StreamingCatalog.ATTRIBUTION} · updated $it" } ?: StreamingCatalog.ATTRIBUTION
            } else {
                null
            }
            return WhereToWatchDisplay(region, shows, link, footer)
        }
    }
}

object StreamingCatalog {
    const val ATTRIBUTION = "Streaming data by JustWatch via TMDB"
    const val TMDB_NOTICE = "This product uses the TMDB API but is not endorsed or certified by TMDB."

    /** Subscription/free services in the catalog: most titles first, then by name. */
    fun services(movies: List<Movie>): List<StreamingInfo.Provider> {
        val byId = HashMap<Int, StreamingInfo.Provider>()
        val counts = HashMap<Int, Int>()
        for (m in movies) {
            val s = m.streaming ?: continue
            val seen = HashSet<Int>()
            for (p in s.stream + s.free) {
                if (!seen.add(p.id)) continue
                val cur = byId[p.id]
                if (cur == null || (cur.logo == null && p.logo != null)) byId[p.id] = p
                counts[p.id] = (counts[p.id] ?: 0) + 1
            }
        }
        return byId.values.sortedWith(compareByDescending<StreamingInfo.Provider> { counts[it.id] ?: 0 }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    fun matchingServices(movie: Movie, services: MyServices): List<StreamingInfo.Provider> {
        val s = movie.streaming ?: return emptyList()
        val seen = HashSet<Int>()
        return (s.stream + s.free).filter { services.contains(it.id) && seen.add(it.id) }
    }

    fun isOnMyServices(movie: Movie, services: MyServices) = matchingServices(movie, services).isNotEmpty()

    fun isStreamable(movie: Movie): Boolean = movie.streaming?.let { it.stream.isNotEmpty() || it.free.isNotEmpty() } ?: false

    fun filter(movies: List<Movie>, services: MyServices) = movies.filter { isOnMyServices(it, services) }

    /** Stable partition: available, then streamable elsewhere, then rent/buy only, then no data. */
    fun availableFirst(movies: List<Movie>, services: MyServices): List<Movie> {
        fun rank(m: Movie): Int = when {
            if (services.isEmpty) isStreamable(m) else isOnMyServices(m, services) -> 0
            !services.isEmpty && isStreamable(m) -> 1
            m.streaming?.let { it.rent.isNotEmpty() || it.buy.isNotEmpty() } == true -> 2
            else -> 3
        }
        return movies.withIndex().sortedWith(compareBy({ rank(it.value) }, { it.index })).map { it.value }
    }

    fun rentOrBuy(info: StreamingInfo): List<StreamingInfo.Provider> {
        val seen = HashSet<Int>()
        return (info.rent + info.buy).filter { seen.add(it.id) }
    }

    /**
     * One Paramount+ entry (iOS StreamingCatalog.collapsingParamountPlusDuplicates, PR #8).
     * TMDB lists Premium, Essential and store channels as separate provider ids; keep the
     * best-priority one (lowest priority, then earliest). Every other provider stays in order.
     */
    fun collapsingParamountPlusDuplicates(providers: List<StreamingInfo.Provider>): List<StreamingInfo.Provider> {
        val best = providers.indices.filter { isParamountPlusService(providers[it]) }
            .minWithOrNull(compareBy({ providers[it].priority ?: Int.MAX_VALUE }, { it })) ?: return providers
        return providers.filterIndexed { i, p -> !isParamountPlusService(p) || i == best }
    }

    /** Legacy id 531, or any name containing "paramount" (case-insensitive). */
    private fun isParamountPlusService(p: StreamingInfo.Provider): Boolean =
        p.id == 531 || p.name.lowercase(Locale.ROOT).contains("paramount")

    private val outFmt = DateTimeFormatter.ofPattern("MMM d", Locale.US)

    /** "Oct 13" from "2026-10-13"; null when unparseable. */
    fun updatedLabel(updated: String?): String? =
        updated?.let { runCatching { outFmt.format(LocalDate.parse(it)) }.getOrNull() }
}

enum class MovieSort(val label: String) {
    Title("Title"), YearNewest("Newest first"), YearOldest("Oldest first"), Spins("Most spun"), AvailableFirst("Available first")
}

/** Movies-tab query: filters, search and sort in one pure function (iOS MoviesView.filtered). */
data class MovieQuery(
    val text: String = "",
    val categoryId: String? = null,
    val listFilter: MovieListFilter = MovieListFilter.All,
    val sort: MovieSort = MovieSort.Title,
    val onMyServices: Boolean = false,
) {
    val isPlain: Boolean get() = text.isEmpty() && categoryId == null && listFilter == MovieListFilter.All && !onMyServices

    fun apply(movies: List<Movie>, ratings: PersonalRatings, stats: com.movieroulettetuesday.app.roulette.SpinStats, services: MyServices): List<Movie> {
        var list = listFilter.apply(movies, ratings, stats)
        categoryId?.let { c -> list = list.filter { c in it.categories } }
        if (onMyServices) list = StreamingCatalog.filter(list, services)
        if (text.isNotEmpty()) {
            val q = text.lowercase(Locale.ROOT)
            list = list.filter {
                it.title.lowercase(Locale.ROOT).contains(q) || it.genre.lowercase(Locale.ROOT).contains(q) ||
                    (it.episodeTitle?.lowercase(Locale.ROOT)?.contains(q) ?: false)
            }
        }
        val byTitle = compareBy(String.CASE_INSENSITIVE_ORDER) { m: Movie -> m.title }
        return when (sort) {
            MovieSort.Title -> list.sortedWith(byTitle)
            MovieSort.YearNewest -> list.sortedWith(compareByDescending<Movie> { it.year }.thenBy { it.title })
            MovieSort.YearOldest -> list.sortedWith(compareBy<Movie> { it.year }.thenBy { it.title })
            MovieSort.Spins -> list.sortedWith(compareByDescending<Movie> { stats.spinCount(it.id) }.thenBy { it.title })
            MovieSort.AvailableFirst -> StreamingCatalog.availableFirst(list.sortedWith(byTitle), services)
        }
    }
}
