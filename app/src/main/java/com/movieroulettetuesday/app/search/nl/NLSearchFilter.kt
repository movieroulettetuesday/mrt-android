package com.movieroulettetuesday.app.search.nl

import com.movieroulettetuesday.app.catalog.MovieCatalog
import com.movieroulettetuesday.app.search.CatalogSearch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * The shared natural-language search filter (`mrt-shared/nl-search-filter-schema.md`). Every field is
 * optional; null means "do not filter on this". Values here are already validated by [NLSearchValidator]:
 * [genre] is one of [NLSearchVocab.GENRES], [category] is a wheel category short name, `kind: any` and
 * `watched: any` are stored as null (the same meaning), and ranges are in order.
 */
data class NLSearchFilter(
    val title: String? = null,
    val kind: Kind? = null,
    val yearMin: Int? = null,
    val yearMax: Int? = null,
    val genre: String? = null,
    val category: String? = null,
    val provider: String? = null,
    val freeOnly: Boolean? = null,
    val ratingMin: Double? = null,
    val ratingMax: Double? = null,
    val unrated: Boolean? = null,
    val watched: Watched? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val onMyServices: Boolean? = null,
) {
    enum class Kind(val raw: String) { Movie("movie"), Episode("episode") }
    enum class Watched(val raw: String) { Watched("watched"), Unwatched("unwatched"), Watchlist("watchlist") }

    /** All-null: use keyword CatalogSearch on the raw query and never call [NLSearchApply]. */
    val isEmpty: Boolean get() = this == EMPTY

    /** Fields that filter the list (everything except [kind], which only picks the list). */
    val hasListFilters: Boolean get() = copy(kind = null) != EMPTY

    /** True when apply must run the matcher on title text and/or S#E# tokens. */
    val needsMatcher: Boolean get() = title != null || season != null || episode != null

    /** The CatalogSearch query for [title] plus S#E# tokens ("the thing s4e39"); empty when [needsMatcher] is false. */
    val matcherQuery: String
        get() {
            val se = when {
                season != null && episode != null -> "s${season}e$episode"
                season != null -> "season $season"
                episode != null -> "episode $episode"
                else -> null
            }
            return listOfNotNull(title, se).joinToString(" ")
        }

    /** Canonical JSON with the shared key names; null fields are omitted. */
    fun toJson(): JsonObject = buildJsonObject {
        title?.let { put("title", JsonPrimitive(it)) }
        kind?.let { put("kind", JsonPrimitive(it.raw)) }
        yearMin?.let { put("yearMin", JsonPrimitive(it)) }
        yearMax?.let { put("yearMax", JsonPrimitive(it)) }
        genre?.let { put("genre", JsonPrimitive(it)) }
        category?.let { put("category", JsonPrimitive(it)) }
        provider?.let { put("provider", JsonPrimitive(it)) }
        freeOnly?.let { put("freeOnly", JsonPrimitive(it)) }
        ratingMin?.let { put("ratingMin", JsonPrimitive(it)) }
        ratingMax?.let { put("ratingMax", JsonPrimitive(it)) }
        unrated?.let { put("unrated", JsonPrimitive(it)) }
        watched?.let { put("watched", JsonPrimitive(it.raw)) }
        season?.let { put("season", JsonPrimitive(it)) }
        episode?.let { put("episode", JsonPrimitive(it)) }
        onMyServices?.let { put("onMyServices", JsonPrimitive(it)) }
    }

    /** Clears one chip's fields (see [NLChip]). */
    fun removing(chip: NLChip.Field): NLSearchFilter = when (chip) {
        NLChip.Field.Title -> copy(title = null)
        NLChip.Field.Year -> copy(yearMin = null, yearMax = null)
        NLChip.Field.Genre -> copy(genre = null)
        NLChip.Field.Category -> copy(category = null)
        NLChip.Field.Provider -> copy(provider = null)
        NLChip.Field.Free -> copy(freeOnly = null)
        NLChip.Field.Rating -> copy(ratingMin = null, ratingMax = null, unrated = null)
        NLChip.Field.Watched -> copy(watched = null)
        NLChip.Field.Episode -> copy(season = null, episode = null)
        NLChip.Field.MyServices -> copy(onMyServices = null)
    }

    companion object {
        val EMPTY = NLSearchFilter()

        /** Restores [toJson] output saved by this app (already validated; no provider check needed). */
        fun fromSavedJson(obj: JsonObject): NLSearchFilter {
            fun p(k: String) = (obj[k] as? JsonPrimitive)?.takeUnless { it is kotlinx.serialization.json.JsonNull }
            fun s(k: String) = p(k)?.takeIf { it.isString }?.content
            fun i(k: String) = p(k)?.takeIf { !it.isString }?.content?.toDoubleOrNull()?.toInt()
            fun d(k: String) = p(k)?.takeIf { !it.isString }?.content?.toDoubleOrNull()
            fun b(k: String) = p(k)?.takeIf { !it.isString }?.content?.toBooleanStrictOrNull()
            return NLSearchFilter(
                title = s("title"), kind = Kind.entries.firstOrNull { it.raw == s("kind") },
                yearMin = i("yearMin"), yearMax = i("yearMax"), genre = s("genre"), category = s("category"),
                provider = s("provider"), freeOnly = b("freeOnly"), ratingMin = d("ratingMin"), ratingMax = d("ratingMax"),
                unrated = b("unrated"), watched = Watched.entries.firstOrNull { it.raw == s("watched") },
                season = i("season"), episode = i("episode"), onMyServices = b("onMyServices"),
            )
        }
    }
}

/** Shared vocab, from `movies.json` at iOS `bedf0a63` (unchanged by the 184-movie catalog). */
object NLSearchVocab {
    val GENRES = listOf("Drama", "Horror", "Sci-Fi", "Comedy", "Thriller", "Fantasy", "Action", "Romance", "Musical", "Western")

    data class Category(val name: String, val fullName: String, val id: String)

    val CATEGORIES = listOf(
        Category("Horror", "Horror & Terror", "cat-horror"),
        Category("Cult", "Cult & Bizarre", "cat-cult"),
        Category("Sci-Fi", "Sci-Fi & Fantasy", "cat-scifi"),
        Category("Prestige", "Prestige Drama", "cat-prestige"),
        Category("Action", "Action & Thrillers", "cat-action"),
        Category("Dark Comedy", "Dark Comedy", "cat-dark-comedy"),
        Category("Criterion", "Criterion & Classics", "cat-criterion"),
        Category("Escape", "War, Escape & Survival", "cat-escape"),
        Category("Love Hurts", "Love Hurts", "cat-love"),
        Category("Listeners", "Listener Picks", "cat-listener"),
    )

    /** Exact catalog genre for a model string, compared folded ("sci fi" → "Sci-Fi"); null if unknown. */
    fun genre(raw: String): String? {
        val f = CatalogSearch.fold(raw)
        return GENRES.firstOrNull { CatalogSearch.fold(it) == f }
    }

    /** Category by short name or full name, compared folded; null if unknown. */
    fun category(raw: String): Category? {
        val f = CatalogSearch.fold(raw)
        if (f.isEmpty()) return null
        return CATEGORIES.firstOrNull { CatalogSearch.fold(it.name) == f } ?: CATEGORIES.firstOrNull { CatalogSearch.fold(it.fullName) == f }
    }

    /** Category id for a validated short name, preferring the live catalog's name/fullName lookup. */
    fun categoryId(name: String, catalog: MovieCatalog?): String? {
        val f = CatalogSearch.fold(name)
        catalog?.categories?.firstOrNull { CatalogSearch.fold(it.name) == f || (it.fullName != null && CatalogSearch.fold(it.fullName) == f) }
            ?.let { return it.id }
        return category(name)?.id
    }

    /** Shortest folded query, and shortest folded query word, that may match a provider name. */
    const val MIN_PROVIDER_CHARS = 2

    /**
     * The one provider matcher, shared by [NLSearchValidator] and [NLSearchApply] (and aligned with iOS
     * `NLSearch.providerNameMatches`), so a provider the validator keeps always matches in apply. The folded
     * [name] must contain the whole folded [query], or every query word.
     * - A query that folds to fewer than [MIN_PROVIDER_CHARS] characters never matches ("x").
     * - The whole-string match counts for any longer query ("Channel 4" matches a name containing "channel 4").
     * - The every-word fallback applies only when every folded word has at least [MIN_PROVIDER_CHARS]
     *   characters; otherwise there is no word match ("Netflix a" only matches as a whole string).
     */
    fun providerMatches(name: String, query: String): Boolean {
        val q = CatalogSearch.fold(query)
        if (q.length < MIN_PROVIDER_CHARS) return false
        val folded = CatalogSearch.fold(name)
        if (folded.contains(q)) return true
        val words = q.split(' ').filter { it.isNotEmpty() }
        return words.all { it.length >= MIN_PROVIDER_CHARS } && words.all { folded.contains(it) }
    }

    /** Distinct stream + free provider names in the catalog (rent and buy are never searched). */
    fun providerNames(catalog: MovieCatalog?): List<String> =
        catalog?.movies.orEmpty().flatMap { m -> m.streaming?.let { it.stream + it.free }.orEmpty().map { it.name } }.distinct()
}
