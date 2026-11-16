package com.movieroulettetuesday.app.search

import com.movieroulettetuesday.app.catalog.Movie
import com.movieroulettetuesday.app.catalog.MovieCatalog
import com.movieroulettetuesday.app.episodes.PodcastEpisode
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * One matcher for every Android search surface: the Movies and Episodes lists and the
 * `mrt://find` / `mrt://search` links. A line-for-line port of iOS 1.1
 * `RouletteCore/CatalogSearch.swift` (movie-roulette-tuesday PR #32). Local data only.
 *
 * - [fold]: lowercase, strip diacritics, delete apostrophes (' ’ ‘ ʼ ` ´), turn every other run of
 *   non-letters/non-digits into one space, trim.
 * - Query tokens: `s4e39`, `s4 e39`, `4x39`, `season 4 episode 39` set a season and/or episode;
 *   `e39`, `ep39`, `ep 39`, `episode 39` set an episode number; the first 4-digit number in
 *   1880..2100 sets a year. Everything else is text. A leading "the" / "a" / "an" is dropped from
 *   the text when words remain.
 * - Season/episode and year are filters. A year also stays in a second text for titles that
 *   contain it ("2001", "Blade Runner 2049").
 * - Ranks, best first: exact title, title prefix, title words, episode, year only, genre or
 *   category, streaming provider (stream + free lists only). Hosts and show notes are never searched.
 */
object CatalogSearch {
    enum class Rank { ExactTitle, TitlePrefix, TitleWords, Episode, Year, GenreOrCategory, Provider }

    data class MovieHit(val movie: Movie, val rank: Rank)
    data class EpisodeHit(val episode: PodcastEpisode, val rank: Rank)

    /** A parsed query. [text] has year and S#E# tokens removed and a leading article dropped; [textWithYear] keeps the year. */
    data class Query(
        val text: String,
        val textWithYear: String,
        val year: Int?,
        val season: Int?,
        val episode: Int?,
    ) {
        val isEmpty: Boolean get() = text.isEmpty() && year == null && season == null && episode == null
        internal val words: List<String> get() = text.split(' ').filter { it.isNotEmpty() }
        internal val hasEpisodeFilter: Boolean get() = season != null || episode != null

        companion object {
            fun parse(raw: String): Query {
                val tokens = fold(raw).split(' ').filter { it.isNotEmpty() }
                val textTokens = mutableListOf<String>()
                val withYear = mutableListOf<String>()
                var year: Int? = null
                var season: Int? = null
                var episode: Int? = null
                var i = 0
                fun next(offset: Int): String? = tokens.getOrNull(i + offset)
                while (i < tokens.size) {
                    val token = tokens[i]
                    val pair = seasonEpisode(token)
                    if (pair != null) {
                        season = pair.first; episode = pair.second; i += 1; continue
                    }
                    if (token.startsWith("s")) {
                        val s = number(token.drop(1), 3)
                        val nextToken = next(1)
                        val e = if (nextToken != null && nextToken.startsWith("e")) number(nextToken.drop(1), 4) else null
                        if (s != null && e != null) {
                            season = s; episode = e; i += 2; continue
                        }
                    }
                    if (token == "season") {
                        val n = next(1)?.let { number(it, 3) }
                        if (n != null) { season = n; i += 2; continue }
                    }
                    if (token == "episode" || token == "ep") {
                        val n = next(1)?.let { number(it, 4) }
                        if (n != null) { episode = n; i += 2; continue }
                    }
                    if (token.startsWith("ep")) {
                        val n = number(token.drop(2), 4)
                        if (n != null) { episode = n; i += 1; continue }
                    }
                    if (token.startsWith("e")) {
                        val n = number(token.drop(1), 4)
                        if (n != null) { episode = n; i += 1; continue }
                    }
                    if (year == null && token.length == 4) {
                        val n = number(token, 4)
                        if (n != null && n in 1880..2100) {
                            year = n; withYear += token; i += 1; continue
                        }
                    }
                    textTokens += token
                    withYear += token
                    i += 1
                }
                return Query(
                    text = droppingArticle(textTokens.joinToString(" ")),
                    textWithYear = droppingArticle(withYear.joinToString(" ")),
                    year = year,
                    season = season,
                    episode = episode,
                )
            }

            private fun number(s: String, maxDigits: Int): Int? {
                if (s.isEmpty() || s.length > maxDigits || !s.all { it in '0'..'9' }) return null
                return s.toInt()
            }

            /** "s4e39" or "4x39". */
            private fun seasonEpisode(token: String): Pair<Int, Int>? {
                for ((prefix, mid) in listOf("s" to 'e', "" to 'x')) {
                    if (!token.startsWith(prefix)) continue
                    val body = token.drop(prefix.length)
                    val split = body.indexOf(mid)
                    if (split < 0) continue
                    val s = number(body.substring(0, split), 3) ?: continue
                    val e = number(body.substring(split + 1), 4) ?: continue
                    return s to e
                }
                return null
            }
        }
    }

    // Normalization

    private val apostrophes = setOf('\''.code, 0x2019, 0x2018, 0x02BC, '`'.code, 0x00B4)
    private val combiningMarks = Regex("\\p{M}+")

    /** Lowercased, diacritics stripped, apostrophes deleted, other punctuation and whitespace collapsed to single spaces. */
    fun fold(text: String): String {
        val folded = combiningMarks.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "").lowercase(Locale.ROOT)
        val out = StringBuilder()
        var pendingSpace = false
        var i = 0
        while (i < folded.length) {
            val cp = folded.codePointAt(i)
            i += Character.charCount(cp)
            if (cp in apostrophes) continue
            if (isAlphanumeric(cp)) {
                if (pendingSpace && out.isNotEmpty()) out.append(' ')
                pendingSpace = false
                out.appendCodePoint(cp)
            } else {
                pendingSpace = true
            }
        }
        return Normalizer.normalize(out, Normalizer.Form.NFC)
    }

    /** Foundation `CharacterSet.alphanumerics`: letters, marks, and numbers. */
    private fun isAlphanumeric(cp: Int): Boolean = Character.isLetterOrDigit(cp) || when (Character.getType(cp).toByte()) {
        Character.LETTER_NUMBER, Character.OTHER_NUMBER,
        Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK -> true
        else -> false
    }

    /** Drops one leading "the", "a", or "an" when something follows it. */
    internal fun droppingArticle(folded: String): String {
        for (article in listOf("the ", "a ", "an ")) {
            if (folded.startsWith(article)) {
                val rest = folded.substring(article.length)
                if (rest.isNotEmpty()) return rest
            }
        }
        return folded
    }

    // Movies

    /** Ranked hits, best first; ties by folded title, then year, then id. Empty / punctuation-only query: no hits. */
    fun movies(
        movies: List<Movie>,
        raw: String,
        catalog: MovieCatalog?,
        episodesByGUID: Map<String, PodcastEpisode> = emptyMap(),
    ): List<MovieHit> {
        val query = Query.parse(raw)
        if (query.isEmpty) return emptyList()
        val categoryNames = categoryLookup(catalog)
        return movies.mapNotNull { movie -> rank(movie, query, categoryNames, episodesByGUID)?.let { MovieHit(movie, it) } }
            .sortedWith(compareBy<MovieHit> { it.rank }.thenBy { fold(it.movie.title) }.thenBy { it.movie.year }.thenBy { it.movie.id })
    }

    internal fun rank(
        movie: Movie,
        query: Query,
        categoryNames: Map<String, List<String>>,
        episodesByGUID: Map<String, PodcastEpisode>,
    ): Rank? {
        if (query.hasEpisodeFilter) {
            val linked = movie.episodeIds.mapNotNull { episodesByGUID[it] }
            if (linked.none { matchesEpisodeFilter(it, query) }) return null
        }
        var best: Rank? = null
        if (query.year != null && query.textWithYear != query.text) {
            titleRank(movie.title, query.textWithYear)?.let { best = it }
        }
        if (query.year == null || movie.year == query.year) {
            best = if (query.text.isEmpty()) {
                minRank(best, if (query.hasEpisodeFilter) Rank.Episode else Rank.Year)
            } else {
                minRank(best, textRank(movie, query, categoryNames, episodesByGUID))
            }
        }
        return best
    }

    private fun textRank(
        movie: Movie,
        query: Query,
        categoryNames: Map<String, List<String>>,
        episodesByGUID: Map<String, PodcastEpisode>,
    ): Rank? {
        titleRank(movie.title, query.text)?.let { return it }
        val episodeTitles = listOfNotNull(movie.episodeTitle) + movie.episodeIds.mapNotNull { episodesByGUID[it]?.title }
        if (episodeTitles.any { contains(fold(it), query) }) return Rank.Episode
        val fields = listOf(movie.genre) + movie.categories.flatMap { categoryNames[it].orEmpty() }
        if (fields.any { contains(fold(it), query) }) return Rank.GenreOrCategory
        val streaming = movie.streaming
        if (streaming != null && (streaming.stream + streaming.free).any { contains(fold(it.name), query) }) return Rank.Provider
        return null
    }

    // Episodes

    /** Ranked hits, best first; ties newest first, then folded title. */
    fun episodes(
        episodes: List<PodcastEpisode>,
        raw: String,
        catalog: MovieCatalog?,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<EpisodeHit> {
        val query = Query.parse(raw)
        if (query.isEmpty) return emptyList()
        val categoryNames = categoryLookup(catalog)
        val movieByGUID = HashMap<String, Movie>()
        for (movie in catalog?.movies.orEmpty()) {
            for (guid in movie.episodeIds) movieByGUID.putIfAbsent(guid, movie)
        }
        return episodes.mapNotNull { ep -> rank(ep, query, movieByGUID[ep.guid], categoryNames, zone)?.let { EpisodeHit(ep, it) } }
            .sortedWith(
                compareBy<EpisodeHit> { it.rank }
                    .thenByDescending { it.episode.pubDate ?: Long.MIN_VALUE }
                    .thenBy { fold(it.episode.title) },
            )
    }

    internal fun rank(
        episode: PodcastEpisode,
        query: Query,
        movie: Movie?,
        categoryNames: Map<String, List<String>>,
        zone: ZoneId,
    ): Rank? {
        if (query.hasEpisodeFilter && !matchesEpisodeFilter(episode, query)) return null
        var best: Rank? = null
        if (query.year != null && query.textWithYear != query.text) {
            titleRank(episode.title, query.textWithYear)?.let { best = it }
        }
        val pubYear = episode.pubDate?.let { Instant.ofEpochMilli(it).atZone(zone).year }
        val year = query.year
        if (year != null && pubYear != year && movie?.year != year) return best
        if (query.text.isEmpty()) return minRank(best, if (query.hasEpisodeFilter) Rank.Episode else Rank.Year)
        titleRank(episode.title, query.text)?.let { return minRank(best, it) }
        if (movie == null) return best
        if (titleRank(movie.title, query.text) != null) return minRank(best, Rank.Episode)
        val fields = listOf(movie.genre) + movie.categories.flatMap { categoryNames[it].orEmpty() }
        if (fields.any { contains(fold(it), query) }) return minRank(best, Rank.GenreOrCategory)
        val streaming = movie.streaming
        if (streaming != null && (streaming.stream + streaming.free).any { contains(fold(it.name), query) }) {
            return minRank(best, Rank.Provider)
        }
        return best
    }

    // Helpers

    internal fun titleRank(title: String, text: String): Rank? {
        if (text.isEmpty()) return null
        val full = fold(title)
        val bare = droppingArticle(full)
        if (bare == text || full == text) return Rank.ExactTitle
        if (bare.startsWith(text) || full.startsWith(text)) return Rank.TitlePrefix
        val words = text.split(' ').filter { it.isNotEmpty() }
        if (full.contains(text) || words.all { full.contains(it) }) return Rank.TitleWords
        return null
    }

    private fun contains(folded: String, query: Query): Boolean {
        if (query.text.isEmpty()) return false
        return folded.contains(query.text) || query.words.all { folded.contains(it) }
    }

    private fun matchesEpisodeFilter(episode: PodcastEpisode, query: Query): Boolean {
        if (query.season != null && episode.season != query.season) return false
        if (query.episode != null && episode.episode != query.episode) return false
        return true
    }

    private fun minRank(a: Rank?, b: Rank?): Rank? = when {
        a != null && b != null -> minOf(a, b)
        else -> a ?: b
    }

    private fun categoryLookup(catalog: MovieCatalog?): Map<String, List<String>> =
        catalog?.categories.orEmpty().associate { it.id to listOfNotNull(it.name, it.fullName) }
}
