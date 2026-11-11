package com.movieroulettetuesday.app.search

import com.movieroulettetuesday.app.catalog.MovieCatalog
import com.movieroulettetuesday.app.episodes.PodcastEpisode

/**
 * Public-catalog documents for the AppSearch (Spotlight-equivalent) index.
 * Movie: id, title, year, genre. Episode: guid, title, season. Never ratings, watchlist, or spins.
 */
data class CatalogDocument(
    val schema: String,
    val id: String,
    val title: String,
    val year: Int? = null,
    val genre: String? = null,
    val season: Int? = null,
    val uri: String,
) {
    /** Every string that would be written as an indexed property. */
    fun indexedValues(): List<String> = buildList {
        add(id)
        add(title)
        year?.let { add(it.toString()) }
        genre?.takeIf { it.isNotBlank() }?.let { add(it) }
        season?.let { add(it.toString()) }
        add(uri)
        add(schema)
    }
}

object CatalogDocuments {
    const val NAMESPACE = "mrt"
    const val MOVIE_SCHEMA = "MrtMovie"
    const val EPISODE_SCHEMA = "MrtEpisode"

    fun movies(catalog: MovieCatalog): List<CatalogDocument> = catalog.movies.map { m ->
        CatalogDocument(
            schema = MOVIE_SCHEMA,
            id = m.id,
            title = m.title,
            year = m.year,
            genre = m.genre,
            uri = "mrt://movie/${m.id}",
        )
    }

    fun episodes(episodes: List<PodcastEpisode>): List<CatalogDocument> = episodes.map { e ->
        CatalogDocument(
            schema = EPISODE_SCHEMA,
            id = e.guid,
            title = e.title,
            season = e.season,
            uri = "mrt://episode/${e.guid}",
        )
    }

    fun all(catalog: MovieCatalog, episodes: List<PodcastEpisode>): List<CatalogDocument> =
        movies(catalog) + episodes(episodes)

    fun indexedValues(docs: List<CatalogDocument>): Set<String> =
        docs.flatMap { it.indexedValues() }.toSet()
}
