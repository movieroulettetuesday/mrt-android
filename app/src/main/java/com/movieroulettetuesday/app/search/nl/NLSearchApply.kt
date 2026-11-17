package com.movieroulettetuesday.app.search.nl

import com.movieroulettetuesday.app.catalog.Movie
import com.movieroulettetuesday.app.catalog.MovieCatalog
import com.movieroulettetuesday.app.episodes.PodcastEpisode
import com.movieroulettetuesday.app.movies.MyServices
import com.movieroulettetuesday.app.movies.PersonalRatings
import com.movieroulettetuesday.app.movies.StreamingCatalog
import com.movieroulettetuesday.app.movies.WatchStatus
import com.movieroulettetuesday.app.search.CatalogSearch
import java.time.Instant
import java.time.ZoneId

/**
 * Deterministic `apply` from the shared schema. No model here. Every set field is ANDed; the caller has
 * already applied the list's own toolbar filters to the input list (chips never replace the toolbar).
 *
 * - Hard filters: year range, exact genre, category id, provider / free-only (stream + free names only,
 *   folded contains), user rating in half-stars, watched status, On my services.
 * - If [NLSearchFilter.needsMatcher] (title, season or episode), CatalogSearch runs on title + S#E# tokens,
 *   restricted to the hard-filtered set, and its rank order is kept. Otherwise CatalogSearch is skipped
 *   (an empty query has no hits) and the hard-filtered set is returned in input order.
 * - Never call this with an empty filter: keyword-search the raw query instead.
 */
object NLSearchApply {
    data class MovieResult(val movies: List<Movie>, val ranks: Map<String, CatalogSearch.Rank>?)

    fun movies(
        filter: NLSearchFilter,
        movies: List<Movie>,
        ratings: PersonalRatings,
        services: MyServices,
        catalog: MovieCatalog?,
        episodesByGUID: Map<String, PodcastEpisode> = emptyMap(),
    ): MovieResult {
        val categoryId = filter.category?.let { NLSearchVocab.categoryId(it, catalog) }
        val hard = movies.filter { m ->
            yearMatches(filter, m.year) && movieMatches(filter, m, categoryId, ratings, services)
        }
        if (!filter.needsMatcher) return MovieResult(hard, null)
        val hits = CatalogSearch.movies(hard, filter.matcherQuery, catalog, episodesByGUID)
        return MovieResult(hits.map { it.movie }, hits.associate { it.movie.id to it.rank })
    }

    fun episodes(
        filter: NLSearchFilter,
        episodes: List<PodcastEpisode>,
        ratings: PersonalRatings,
        services: MyServices,
        catalog: MovieCatalog?,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<PodcastEpisode> {
        val categoryId = filter.category?.let { NLSearchVocab.categoryId(it, catalog) }
        val movieByGUID = HashMap<String, Movie>()
        for (movie in catalog?.movies.orEmpty()) for (guid in movie.episodeIds) movieByGUID.putIfAbsent(guid, movie)
        val needsMovie = filter.genre != null || filter.category != null || filter.provider != null || filter.freeOnly == true ||
            filter.onMyServices == true || filter.ratingMin != null || filter.ratingMax != null || filter.unrated != null || filter.watched != null
        val hard = episodes.filter { ep ->
            val movie = movieByGUID[ep.guid]
            if (needsMovie && movie == null) return@filter false
            if (filter.yearMin != null || filter.yearMax != null) {
                // Linked movie year, else the episode's pub-year.
                val pubYear = ep.pubDate?.let { Instant.ofEpochMilli(it).atZone(zone).year }
                val year = movie?.year ?: pubYear ?: return@filter false
                if (!yearMatches(filter, year)) return@filter false
            }
            movie == null || movieMatches(filter, movie, categoryId, ratings, services)
        }
        if (!filter.needsMatcher) return hard.sortedByDescending { it.pubDate ?: Long.MIN_VALUE }
        return CatalogSearch.episodes(hard, filter.matcherQuery, catalog, zone).map { it.episode }
    }

    private fun yearMatches(f: NLSearchFilter, year: Int): Boolean =
        (f.yearMin == null || year >= f.yearMin) && (f.yearMax == null || year <= f.yearMax)

    private fun movieMatches(
        f: NLSearchFilter,
        m: Movie,
        categoryId: String?,
        ratings: PersonalRatings,
        services: MyServices,
    ): Boolean {
        if (f.genre != null && m.genre != f.genre) return false
        if (f.category != null && (categoryId == null || categoryId !in m.categories)) return false
        if (!providerMatches(f, m)) return false
        if (!ratingMatches(f, ratings.halfStars(m.id))) return false
        if (!watchedMatches(f.watched, ratings.status(m.id))) return false
        if (f.onMyServices == true && !StreamingCatalog.isOnMyServices(m, services)) return false
        return true
    }

    /** freeOnly: non-empty free list; with a provider, the name must be in the free list. Same matcher as the validator. */
    internal fun providerMatches(f: NLSearchFilter, m: Movie): Boolean {
        val s = m.streaming
        val free = s?.free.orEmpty()
        if (f.freeOnly == true && free.isEmpty()) return false
        val provider = f.provider ?: return true
        val names = if (f.freeOnly == true) free else s?.stream.orEmpty() + free
        return names.any { NLSearchVocab.providerMatches(it.name, provider) }
    }

    /** Half-star comparison (never whole stars). unrated true → no rating; false with no range → any rating. */
    internal fun ratingMatches(f: NLSearchFilter, halfStars: Int?): Boolean {
        if (f.unrated == true) return halfStars == null
        if (f.unrated == false && halfStars == null) return false
        if (f.ratingMin == null && f.ratingMax == null) return true
        val h = halfStars ?: return false
        if (f.ratingMin != null && h < Math.round(f.ratingMin * 2)) return false
        if (f.ratingMax != null && h > Math.round(f.ratingMax * 2)) return false
        return true
    }

    internal fun watchedMatches(w: NLSearchFilter.Watched?, status: WatchStatus?): Boolean = when (w) {
        null -> true
        NLSearchFilter.Watched.Watched -> status == WatchStatus.Watched
        NLSearchFilter.Watched.Unwatched -> status != WatchStatus.Watched
        NLSearchFilter.Watched.Watchlist -> status == WatchStatus.WantToWatch
    }
}
