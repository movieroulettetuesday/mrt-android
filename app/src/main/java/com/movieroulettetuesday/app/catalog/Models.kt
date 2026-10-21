package com.movieroulettetuesday.app.catalog

/**
 * Catalog model, a 1:1 port of RouletteCore/Models.swift (iOS). Field names match movies.json
 * (schema in the iOS repo's CATALOG.md). Decoding rules live in [CatalogJson].
 */
data class ShowInfo(
    val name: String = "Movie Roulette Tuesday: The Podcast",
    val hosts: List<String> = listOf("Rachael", "Karl", "Matt"),
    val rssURL: String? = "https://rss.buzzsprout.com/2117292.rss",
    val applePodcastsURL: String? = "https://podcasts.apple.com/us/podcast/movie-roulette-tuesday-the-podcast/id1666123849",
    val spotifyURL: String? = "https://open.spotify.com/show/0jJt5G1XRlbLYWJuyIQnj1",
    val applePodcastID: String? = "1666123849",
    val spotifyShowID: String? = "0jJt5G1XRlbLYWJuyIQnj1",
    val buzzsproutShowID: String? = "2117292",
) {
    companion object {
        val MRT = ShowInfo()
    }
}

/** Where a movie streams (TMDB watch/providers, data by JustWatch), precomputed into movies.json. */
data class StreamingInfo(
    val region: String = "US",
    /** "YYYY-MM-DD" */
    val updated: String? = null,
    /** TMDB "All options" page. */
    val link: String? = null,
    val stream: List<Provider> = emptyList(),
    val free: List<Provider> = emptyList(),
    val rent: List<Provider> = emptyList(),
    val buy: List<Provider> = emptyList(),
) {
    data class Provider(val id: Int, val name: String, val logo: String? = null, val priority: Int? = null) {
        val logoURL: String?
            get() = logo?.takeIf { it.isNotEmpty() }?.let { LOGO_BASE + if (it.startsWith("/")) it else "/$it" }

        companion object {
            const val LOGO_BASE = "https://image.tmdb.org/t/p/w92"
        }
    }

    val isEmpty: Boolean get() = stream.isEmpty() && free.isEmpty() && rent.isEmpty() && buy.isEmpty()
}

/** A movie that can come up on the wheel. Spin counts etc. are derived from history, never stored here. */
data class Movie(
    /** `title-slug-year`; stable forever (history, ratings and votes are keyed by it). */
    val id: String,
    val title: String,
    val year: Int,
    val genre: String,
    /** Ids of the [RouletteCategory] values this movie belongs to. */
    val categories: List<String> = emptyList(),
    /** Buzzsprout RSS item guids (e.g. "Buzzsprout-19913482"). */
    val episodeIds: List<String> = emptyList(),
    val episodeTitle: String? = null,
    val posterURL: String? = null,
    val isInfamous: Boolean = false,
    val hostRating: Double? = null,
    val tmdbId: Int? = null,
    val streaming: StreamingInfo? = null,
)

/** One slice of the wheel. */
data class RouletteCategory(
    val id: String,
    /** Short label drawn on the wheel. */
    val name: String,
    val fullName: String? = null,
    /** "#RRGGBB" or "#RRGGBBAA". */
    val colorHex: String,
    /** Relative slice size; null means 1. Slice size == landing odds. */
    val weight: Double? = null,
    val movieIds: List<String> = emptyList(),
) {
    val displayName: String get() = fullName ?: name
}

/**
 * The full data set behind the wheel. Categories are in wheel order: index 0 starts at 12 o'clock,
 * then clockwise.
 */
data class MovieCatalog(
    val schemaVersion: Int = 1,
    val show: ShowInfo? = null,
    val categories: List<RouletteCategory>,
    val movies: List<Movie>,
) {
    val showInfo: ShowInfo get() = show ?: ShowInfo.MRT

    fun movie(withId: String): Movie? = movies.firstOrNull { it.id == withId }
    fun category(withId: String): RouletteCategory? = categories.firstOrNull { it.id == withId }
    fun movieForEpisodeGuid(guid: String): Movie? = movies.firstOrNull { guid in it.episodeIds }

    /** Copy with duplicate movie / category ids removed (first occurrence wins). */
    fun deduplicated(): MovieCatalog {
        val seenMovies = HashSet<String>()
        val seenCategories = HashSet<String>()
        return copy(
            movies = movies.filter { seenMovies.add(it.id) },
            categories = categories.filter { seenCategories.add(it.id) },
        )
    }

    /** Human-readable data problems, sorted. Empty means the catalog is consistent. */
    fun validationIssues(): List<String> {
        val issues = mutableListOf<String>()
        val movieIds = movies.map { it.id }
        val categoryIds = categories.map { it.id }
        movieIds.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            .forEach { issues += "Duplicate movie id '$it'" }
        categoryIds.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            .forEach { issues += "Duplicate category id '$it'" }
        val knownMovies = movieIds.toSet()
        val knownCategories = categoryIds.toSet()
        for (category in categories) {
            if (HexColor.parse(category.colorHex) == null) {
                issues += "Category '${category.id}' has invalid colorHex '${category.colorHex}'"
            }
            if (category.movieIds.isEmpty()) issues += "Category '${category.id}' has no movies"
            for (movieId in category.movieIds) {
                val movie = movie(movieId)
                if (movieId !in knownMovies) {
                    issues += "Category '${category.id}' references unknown movie '$movieId'"
                } else if (movie != null && category.id !in movie.categories) {
                    issues += "Movie '$movieId' is listed in '${category.id}' but doesn't list that category"
                }
            }
        }
        for (movie in movies) {
            for (categoryId in movie.categories) {
                val category = category(categoryId)
                if (categoryId !in knownCategories) {
                    issues += "Movie '${movie.id}' references unknown category '$categoryId'"
                } else if (category != null && movie.id !in category.movieIds) {
                    issues += "Movie '${movie.id}' lists '$categoryId' but that category doesn't list it"
                }
            }
        }
        return issues.sorted()
    }

    /** "1983 · Listener Picks, Cult & Bizarre" (catalog categories, else genre). */
    fun subtitle(movie: Movie): String {
        val names = movie.categories.mapNotNull { category(it)?.displayName }
        return if (names.isEmpty()) "${movie.year} · ${movie.genre}" else "${movie.year} · " + names.joinToString(", ")
    }
}
