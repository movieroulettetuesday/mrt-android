package com.movieroulettetuesday.app.suggestions

import com.movieroulettetuesday.app.catalog.Movie
import com.movieroulettetuesday.app.catalog.MovieCatalog
import com.movieroulettetuesday.app.movies.HalfStars
import com.movieroulettetuesday.app.movies.MyServices
import com.movieroulettetuesday.app.movies.PersonalRatings
import com.movieroulettetuesday.app.movies.WatchStatus
import kotlin.random.Random
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * The only thing the model may say about a movie (plan §2a.3). The copy is the app’s; the model
 * returns just [raw].
 */
enum class Vibe(val raw: String, val line: String) {
    Laughs("laughs", "Good for a laugh."),
    Scares("scares", "Good for a scare."),
    Thrills("thrills", "Edge-of-your-seat."),
    Weird("weird", "Gloriously weird."),
    Cult("cult", "A cult favourite."),
    FeelGood("feelGood", "An easy, feel-good watch."),
    Heartbreak("heartbreak", "Bring tissues."),
    Action("action", "Big, loud fun."),
    Classic("classic", "An old-school classic."),
    Dark("dark", "Dark and twisted."),
    ;

    companion object {
        fun fromRaw(raw: String): Vibe? = entries.firstOrNull { it.raw == raw }
    }
}

/** Which vibes fit a movie’s genre (plan §2a.3: e.g. `scares` only for Horror/Thriller). */
object Vibes {
    /** Before this year “An old-school classic.” is allowed. */
    const val CLASSIC_BEFORE_YEAR = 1980

    private val byGenre: Map<String, List<Vibe>> = mapOf(
        "Horror" to listOf(Vibe.Scares, Vibe.Dark, Vibe.Weird, Vibe.Cult),
        "Thriller" to listOf(Vibe.Thrills, Vibe.Scares, Vibe.Dark, Vibe.Cult),
        "Comedy" to listOf(Vibe.Laughs, Vibe.FeelGood, Vibe.Weird, Vibe.Cult),
        "Action" to listOf(Vibe.Action, Vibe.Thrills, Vibe.Cult),
        "Sci-Fi" to listOf(Vibe.Weird, Vibe.Thrills, Vibe.Action, Vibe.Dark, Vibe.Cult),
        "Fantasy" to listOf(Vibe.Weird, Vibe.FeelGood, Vibe.Action, Vibe.Cult),
        "Romance" to listOf(Vibe.FeelGood, Vibe.Heartbreak, Vibe.Laughs),
        "Musical" to listOf(Vibe.FeelGood, Vibe.Laughs, Vibe.Weird, Vibe.Cult),
        "Western" to listOf(Vibe.Action, Vibe.Dark),
        "Drama" to listOf(Vibe.Heartbreak, Vibe.Dark, Vibe.FeelGood, Vibe.Cult),
    )

    /** The genre’s own vibe when the model’s doesn’t fit. Null (no vibe line) where no single line is safe, e.g. Drama. */
    private val default: Map<String, Vibe> = mapOf(
        "Horror" to Vibe.Scares,
        "Thriller" to Vibe.Thrills,
        "Comedy" to Vibe.Laughs,
        "Action" to Vibe.Action,
        "Sci-Fi" to Vibe.Weird,
        "Fantasy" to Vibe.Weird,
        "Romance" to Vibe.FeelGood,
        "Musical" to Vibe.FeelGood,
        "Western" to Vibe.Action,
    )

    fun allowed(vibe: Vibe, movie: Movie): Boolean =
        if (vibe == Vibe.Classic) movie.year in 1..<CLASSIC_BEFORE_YEAR
        else vibe in byGenre[movie.genre].orEmpty()

    fun fromGenre(movie: Movie): Vibe? = default[movie.genre]

    fun validated(vibe: Vibe, movie: Movie): Vibe? = if (allowed(vibe, movie)) vibe else fromGenre(movie)
}

/** One fact the app knows for certain (plan §2a.4). [text] strings are shared with iOS (wsiw-cases.json). */
sealed interface Fact {
    val text: String

    data object OnWatchlist : Fact { override val text = "On your watchlist" }
    data object NotWatched : Fact { override val text = "Not watched yet" }
    data class Rated(val halfStars: Int) : Fact {
        override val text: String
            get() = HalfStars.label(halfStars).let { "You rated it $it ${if (it == "1") "star" else "stars"}" }
    }
    data class Streaming(val provider: String) : Fact { override val text = "Streaming on $provider" }
    data class Free(val provider: String) : Fact { override val text = "Free on $provider" }
    data class Category(val name: String) : Fact { override val text = "From the $name category" }
}

/**
 * What the card shows under WHY and Assistant reads: a [vibe] line (if any), else [FALLBACK_LINE]
 * for a no-model pick that really came from the watchlist or services, then the [facts].
 */
data class PickReason(val vibe: Vibe?, val facts: List<Fact>, val fromModel: Boolean) {
    val headline: String?
        get() = vibe?.line ?: if (!fromModel && facts.any { it == Fact.OnWatchlist || it is Fact.Streaming }) FALLBACK_LINE else null

    val factsLine: String get() = facts.joinToString("  ·  ") { it.text }

    /** “Try Christmas Evil (1980). Good for a scare. It’s on your watchlist and streaming on Netflix.” */
    fun spoken(movie: Movie): String = buildList {
        add("Try ${movie.title} (${movie.year}).")
        headline?.let(::add)
        facts.filterIsInstance<Fact.Rated>().firstOrNull()?.let { add("${it.text}.") }
        val clauses = buildList {
            if (Fact.OnWatchlist in facts) add("on your watchlist")
            facts.firstNotNullOfOrNull { f ->
                when (f) {
                    is Fact.Streaming -> "streaming on ${f.provider}"
                    is Fact.Free -> "free on ${f.provider}"
                    else -> null
                }
            }?.let(::add)
        }
        if (clauses.isNotEmpty()) add("It’s ${clauses.joinToString(" and ")}.")
    }.joinToString(" ")

    companion object {
        const val FALLBACK_LINE = "Picked from your watchlist and services."
    }
}

/** Facts from real state only (plan §2a.4). Pure; the shared `composer` cases drive it. */
object ReasonComposer {
    fun facts(movie: Movie, catalog: MovieCatalog, ratings: PersonalRatings, services: MyServices): List<Fact> = buildList {
        val status = ratings.status(movie.id)
        val halfStars = ratings.halfStars(movie.id)
        if (status == WatchStatus.WantToWatch) add(Fact.OnWatchlist)
        if (status != WatchStatus.Watched && halfStars == null) add(Fact.NotWatched)
        halfStars?.let { add(Fact.Rated(it)) }
        val s = movie.streaming
        val stream = s?.stream?.firstOrNull { services.contains(it.id) }
        val free = s?.free?.firstOrNull()
        when {
            stream != null -> add(Fact.Streaming(stream.name))
            free != null -> add(Fact.Free(free.name))
        }
        movie.categories.firstNotNullOfOrNull { catalog.category(it)?.displayName }?.let { add(Fact.Category(it)) }
    }

    fun picked(movie: Movie, vibe: Vibe?, catalog: MovieCatalog, ratings: PersonalRatings, services: MyServices) =
        PickReason(vibe, facts(movie, catalog, ratings, services), fromModel = true)

    fun fallback(movie: Movie, catalog: MovieCatalog, ratings: PersonalRatings, services: MyServices) =
        PickReason(null, facts(movie, catalog, ratings, services), fromModel = false)
}

/** App-side candidate set (plan §2a.1). */
object PickCandidates {
    const val CAP = 12
    /** Fewer than this many → relax: allow recent spins, then watched movies rated ≥ 4 stars. */
    const val MIN = 3
    const val REWATCH_MIN_HALF_STARS = 8

    const val WATCHLIST_POINTS = 3
    const val SERVICE_POINTS = 2
    const val FREE_POINTS = 1

    data class Candidate(val movie: Movie, val score: Int)

    /** Rating implies watched (PersonalRatings.withHalfStars), so any rating excludes too. */
    fun isWatched(movieId: String, ratings: PersonalRatings): Boolean =
        ratings.status(movieId) == WatchStatus.Watched || ratings.halfStars(movieId) != null

    fun score(movie: Movie, ratings: PersonalRatings, services: MyServices): Int {
        var points = 0
        if (ratings.status(movie.id) == WatchStatus.WantToWatch) points += WATCHLIST_POINTS
        if (movie.streaming?.stream.orEmpty().any { services.contains(it.id) }) points += SERVICE_POINTS
        if (movie.streaming?.free.orEmpty().isNotEmpty()) points += FREE_POINTS
        return points
    }

    /**
     * Unwatched, not recently spun movies, best score first, top [CAP]. Ties are ordered by a
     * shuffle seeded with [seed] (deterministic for a given seed). Empty means offer Spin.
     */
    fun select(catalog: MovieCatalog, ratings: PersonalRatings, recentIds: Set<String>, services: MyServices, seed: Long): List<Candidate> {
        val unwatched = catalog.movies.filter { !isWatched(it.id, ratings) }
        var pool = unwatched.filter { it.id !in recentIds }
        if (pool.size < MIN) pool = unwatched
        if (pool.size < MIN) {
            pool = pool + catalog.movies.filter { (ratings.halfStars(it.id) ?: 0) >= REWATCH_MIN_HALF_STARS }
        }
        return pool.shuffled(Random(seed))
            .map { Candidate(it, score(it, ratings, services)) }
            .sortedByDescending { it.score } // stable: equal scores keep the seeded order
            .take(CAP)
    }
}

/** The ~400-token prompt (plan §2a.2): instructions, then one numbered row per candidate with every field stated. */
object PickPrompt {
    val INSTRUCTIONS: String = """
        Pick one movie for tonight from the numbered list. Prefer movies on the watchlist or on their services.
        Reply with JSON only, nothing else: {"pick":<number of the movie>,"vibe":"<vibe>"}
        vibe must be one of: ${Vibe.entries.joinToString(", ") { it.raw }}. Choose one that suits the movie's genre.
    """.trimIndent()

    fun row(n: Int, movie: Movie, catalog: MovieCatalog, ratings: PersonalRatings, services: MyServices): String {
        val cats = movie.categories.mapNotNull { catalog.category(it)?.displayName }
        val mine = movie.streaming?.stream.orEmpty().filter { services.contains(it.id) }.map { it.name }.distinct()
        fun yesNo(b: Boolean) = if (b) "yes" else "no"
        return listOf(
            "$n. ${movie.title} (${movie.year})",
            movie.genre,
            "categories: ${cats.ifEmpty { listOf("none") }.joinToString(", ")}",
            "watchlist: ${yesNo(ratings.status(movie.id) == WatchStatus.WantToWatch)}",
            "watched: ${yesNo(PickCandidates.isWatched(movie.id, ratings))}",
            "rated: ${ratings.halfStars(movie.id)?.let { HalfStars.label(it) + " stars" } ?: "no"}",
            "on your services: ${mine.ifEmpty { listOf("no") }.joinToString(", ")}",
            "free: ${yesNo(movie.streaming?.free.orEmpty().isNotEmpty())}",
        ).joinToString(" · ")
    }

    fun build(candidates: List<PickCandidates.Candidate>, catalog: MovieCatalog, ratings: PersonalRatings, services: MyServices): String =
        buildString {
            appendLine(INSTRUCTIONS)
            candidates.forEachIndexed { i, c -> appendLine(row(i + 1, c.movie, catalog, ratings, services)) }
        }
}

/** Strict reader for `{"pick":<1…n>,"vibe":"<Vibe.raw>"}` (plan §2a.3, Android JSON path). */
object PickParser {
    data class ModelPick(val index: Int, val vibe: Vibe)

    private val json = Json

    /** Null unless the reply is exactly that object (an optional ```json fence aside). */
    fun parse(raw: String?, n: Int): ModelPick? {
        if (raw.isNullOrBlank()) return null
        var t = raw.trim()
        if (t.startsWith("```")) t = t.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val obj = runCatching { json.parseToJsonElement(t) }.getOrNull() as? JsonObject ?: return null
        if (obj.keys != setOf("pick", "vibe")) return null
        val pick = (obj["pick"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: return null
        if (pick !in 1..n) return null
        val vibeRaw = (obj["vibe"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        val vibe = Vibe.fromRaw(vibeRaw) ?: return null
        return ModelPick(pick - 1, vibe)
    }
}

/**
 * mrt-shared `wsiw/banned-phrases.json` (pinned in shared/; a unit test keeps [PATTERNS] equal to it)
 * plus every catalog provider name. Any match in model-authored text means it is not shown.
 */
object BannedPhrases {
    val PATTERNS: List<String> = listOf(
        "\\bwatch ?list\\b", "\\bwant to watch\\b", "\\bsaved\\b", "\\brat(ed|ing|ings)\\b", "\\bstars?\\b", "★",
        "\\bout of (5|five|10|ten)\\b", "\\b\\d(\\.5)?\\s*/\\s*(5|10)\\b", "\\bwatched\\b", "\\bseen\\b", "\\bhaven'?t (seen|watched)\\b",
        "\\byou (loved|liked|enjoyed)\\b", "\\bfavou?rite\\b", "\\bstream(ing|s)?\\b", "\\bfree\\b", "\\bavailable on\\b",
        "\\bawards?\\b", "\\boscars?\\b", "\\bcritics?\\b", "\\bacclaimed\\b", "\\bbox office\\b",
    )

    private val compiled: List<Regex> = PATTERNS.map { Regex(it, RegexOption.IGNORE_CASE) }

    fun providerNames(catalog: MovieCatalog): Set<String> =
        catalog.movies.flatMap { m -> m.streaming?.stream.orEmpty() + m.streaming?.free.orEmpty() }.map { it.name }.toSet()

    fun matches(text: String, providerNames: Collection<String>): Boolean {
        if (compiled.any { it.containsMatchIn(text) }) return true
        // Curly apostrophes too: “haven’t seen”.
        val straight = text.replace('’', '\'')
        if (straight != text && compiled.any { it.containsMatchIn(straight) }) return true
        return providerNames.any { name ->
            name.isNotBlank() && Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(name) + "(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE).containsMatchIn(text)
        }
    }
}
