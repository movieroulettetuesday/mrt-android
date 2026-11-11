package com.movieroulettetuesday.app.actions

import com.movieroulettetuesday.app.catalog.Movie
import com.movieroulettetuesday.app.catalog.MovieCatalog
import com.movieroulettetuesday.app.catalog.StreamingInfo
import com.movieroulettetuesday.app.episodes.ListenLink
import com.movieroulettetuesday.app.episodes.ListenLinks
import com.movieroulettetuesday.app.episodes.PodcastEpisode
import com.movieroulettetuesday.app.episodes.EpisodeDates
import com.movieroulettetuesday.app.movies.MyServices
import com.movieroulettetuesday.app.movies.PersonalRatings
import com.movieroulettetuesday.app.movies.PersonalRatingsStore
import com.movieroulettetuesday.app.movies.ProviderLaunch
import com.movieroulettetuesday.app.movies.StreamingCatalog
import com.movieroulettetuesday.app.movies.WatchStatus
import com.movieroulettetuesday.app.movies.WhereToWatchDisplay
import com.movieroulettetuesday.app.widget.WidgetSnapshot
import java.time.ZoneId
import java.util.Locale

/**
 * Pure-ish handlers for 1.1 App Actions. Navigation and speech are returned as an [ActionPlan];
 * watchlist writes go through [PersonalRatingsStore.setStatus] and never invent a rating.
 */
data class ActionPlan(
    val speech: String,
    val openMovieId: String? = null,
    val openEpisodeGuid: String? = null,
    val openWatchlist: Boolean = false,
    val openEpisodesTab: Boolean = false,
    val openRoulette: Boolean = false,
    val focusWhereToWatch: Boolean = false,
    val startSpin: Boolean = false,
    val startSuggest: Boolean = false,
    val offerSpin: Boolean = false,
    val providerPlan: ProviderLaunch.Plan? = null,
    val listenLinks: List<ListenLink> = emptyList(),
)

class ActionCoordinator(
    private val catalog: () -> MovieCatalog?,
    private val ratings: PersonalRatingsStore,
    private val episodes: () -> List<PodcastEpisode>,
    private val snapshot: () -> WidgetSnapshot?,
    private val services: () -> MyServices,
    private val appleMap: () -> Map<String, Long> = { emptyMap() },
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    fun plan(link: DeepLink): ActionPlan = when (link) {
        DeepLink.Spin -> ActionPlan(
            speech = "",
            openRoulette = true,
            startSpin = true,
        )
        is DeepLink.OpenMovie -> openMovie(link.id)
        is DeepLink.OpenEpisode -> openEpisode(link.guid)
        DeepLink.LatestEpisode -> latestEpisode()
        DeepLink.Watchlist -> showWatchlist()
        is DeepLink.AddToWatchlist -> addToWatchlist(link.id)
        is DeepLink.MarkWatched -> markWatched(link.id)
        is DeepLink.WhereToWatch -> whereToWatch(link.id)
        is DeepLink.OpenOnProvider -> openOnProvider(link.id, link.provider)
        DeepLink.Suggest -> ActionPlan(
            speech = "",
            openRoulette = true,
            startSuggest = true,
        )
        is DeepLink.FindMovie -> findMovie(link.title, link.year)
    }

    fun openMovie(id: String): ActionPlan {
        val movie = catalog()?.movie(id)
        return if (movie == null) {
            // Still open the page: the existing movie route shows "Movie Not Found" if it stays unknown.
            ActionPlan(speech = ActionSpeech.UNKNOWN_MOVIE, openMovieId = id)
        } else {
            ActionPlan(speech = ActionSpeech.titleAndYear(movie), openMovieId = movie.id)
        }
    }

    fun openEpisode(guid: String): ActionPlan {
        val ep = resolveEpisode(guid)
        val speech = if (ep != null) ActionSpeech.episode(ep, zone()) else ActionSpeech.UNKNOWN_EPISODE
        return ActionPlan(speech = speech, openEpisodeGuid = guid, listenLinks = ep?.let { listenLinks(it) }.orEmpty())
    }

    fun latestEpisode(): ActionPlan {
        val ep = latestCachedEpisode()
            ?: return ActionPlan(speech = ActionSpeech.EMPTY_LATEST, openEpisodesTab = true)
        return ActionPlan(
            speech = ActionSpeech.episode(ep, zone()),
            openEpisodeGuid = ep.guid,
            listenLinks = listenLinks(ep),
        )
    }

    fun showWatchlist(): ActionPlan {
        val titles = watchlistTitles(ratings.ratings.value, catalog())
        val speech = if (titles.isEmpty()) ActionSpeech.EMPTY_WATCHLIST else ActionSpeech.watchlist(titles)
        return ActionPlan(speech = speech, openWatchlist = true)
    }

    fun addToWatchlist(id: String): ActionPlan {
        val movie = catalog()?.movie(id) ?: return ActionPlan(speech = ActionSpeech.UNKNOWN_MOVIE)
        val before = ratings.ratings.value
        val hadRating = before.halfStars(id)
        if (before.status(id) == WatchStatus.WantToWatch) {
            return ActionPlan(speech = ActionSpeech.alreadyOnWatchlist(movie), openMovieId = movie.id)
        }
        ratings.setStatus(WatchStatus.WantToWatch, id)
        val after = ratings.ratings.value
        check(after.status(id) == WatchStatus.WantToWatch)
        check(after.halfStars(id) == hadRating) { "add to watchlist must not invent or change a rating" }
        return ActionPlan(speech = ActionSpeech.addedToWatchlist(movie), openMovieId = movie.id)
    }

    fun markWatched(id: String): ActionPlan {
        val movie = catalog()?.movie(id) ?: return ActionPlan(speech = ActionSpeech.UNKNOWN_MOVIE)
        val before = ratings.ratings.value
        val hadRating = before.halfStars(id)
        ratings.setStatus(WatchStatus.Watched, id)
        val after = ratings.ratings.value
        check(after.status(id) == WatchStatus.Watched)
        check(after.halfStars(id) == hadRating) { "mark watched must not invent or change a rating" }
        return ActionPlan(speech = ActionSpeech.markedWatched(movie), openMovieId = movie.id)
    }

    fun whereToWatch(id: String): ActionPlan {
        val movie = catalog()?.movie(id) ?: return ActionPlan(speech = ActionSpeech.UNKNOWN_MOVIE)
        return ActionPlan(
            speech = ActionSpeech.whereToWatch(movie, services()),
            openMovieId = movie.id,
            focusWhereToWatch = true,
        )
    }

    fun openOnProvider(id: String, providerName: String): ActionPlan {
        val movie = catalog()?.movie(id) ?: return ActionPlan(speech = ActionSpeech.UNKNOWN_MOVIE)
        val provider = resolveProvider(movie, providerName)
            ?: return ActionPlan(
                speech = ActionSpeech.unknownProvider(movie, providerName),
                openMovieId = movie.id,
                focusWhereToWatch = true,
            )
        return ActionPlan(
            speech = ActionSpeech.openingOn(provider.name),
            openMovieId = movie.id,
            focusWhereToWatch = true,
            providerPlan = ProviderLaunch.plan(provider, movie),
        )
    }

    fun findMovie(title: String, year: Int?): ActionPlan {
        val cat = catalog() ?: return ActionPlan(speech = ActionSpeech.CATALOG_UNAVAILABLE)
        val matches = resolveMovies(cat, title, year)
        return when (matches.size) {
            0 -> ActionPlan(speech = ActionSpeech.noMovieNamed(title, year))
            1 -> openMovie(matches[0].id)
            else -> ActionPlan(speech = ActionSpeech.disambiguate(matches))
        }
    }

    fun latestCachedEpisode(): PodcastEpisode? {
        snapshot()?.latestEpisode?.let { info ->
            return episodes().firstOrNull { it.guid == info.guid }
                ?: PodcastEpisode(guid = info.guid, title = info.title, season = info.season, episode = info.episode, pubDate = info.pubDate)
        }
        return WidgetSnapshot.latest(episodes())
    }

    fun listenLinks(episode: PodcastEpisode): List<ListenLink> =
        ListenLinks.forEpisode(episode, appleMap(), catalog()?.showInfo ?: com.movieroulettetuesday.app.catalog.ShowInfo.MRT)

    companion object {
        fun watchlistTitles(ratings: PersonalRatings, catalog: MovieCatalog?): List<String> {
            val ids = ratings.watchlist
            return ids.map { id ->
                catalog?.movie(id)?.let { ActionSpeech.titleAndYear(it) } ?: id
            }.sortedWith(String.CASE_INSENSITIVE_ORDER)
        }

        fun resolveProvider(movie: Movie, name: String): StreamingInfo.Provider? {
            val s = movie.streaming ?: return null
            val all = StreamingCatalog.collapsingParamountPlusDuplicates(s.stream + s.free + StreamingCatalog.rentOrBuy(s))
            val q = name.trim().lowercase(Locale.ROOT)
            if (q.isEmpty()) return null
            return all.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
                ?: all.firstOrNull { it.name.lowercase(Locale.ROOT).contains(q) }
                ?: all.firstOrNull { q.contains(it.name.lowercase(Locale.ROOT)) }
        }

        fun resolveMovies(catalog: MovieCatalog, title: String, year: Int?): List<Movie> {
            val q = title.trim()
            if (q.isEmpty()) return emptyList()
            catalog.movie(q)?.let { return listOf(it) }
            fun yearOk(m: Movie) = year == null || m.year == year
            val exact = catalog.movies.filter { it.title.equals(q, ignoreCase = true) && yearOk(it) }
            if (exact.isNotEmpty()) return exact.sortedBy { it.year }
            val contains = catalog.movies.filter { it.title.contains(q, ignoreCase = true) && yearOk(it) }
            return contains.sortedBy { it.year }
        }

        fun providerGroups(movie: Movie, services: MyServices): ProviderGroups {
            val s = movie.streaming
            if (s == null || s.isEmpty) return ProviderGroups()
            val streamFree = StreamingCatalog.collapsingParamountPlusDuplicates(s.stream + s.free)
            val mine = streamFree.filter { services.contains(it.id) }
            val others = streamFree.filter { p -> mine.none { it.id == p.id } }
            val rent = StreamingCatalog.collapsingParamountPlusDuplicates(StreamingCatalog.rentOrBuy(s))
                .filter { r -> streamFree.none { it.id == r.id } }
            return ProviderGroups(mine, others, rent)
        }
    }

    data class ProviderGroups(
        val myServices: List<StreamingInfo.Provider> = emptyList(),
        val otherStream: List<StreamingInfo.Provider> = emptyList(),
        val rentOrBuy: List<StreamingInfo.Provider> = emptyList(),
    )

    private fun resolveEpisode(guid: String): PodcastEpisode? =
        episodes().firstOrNull { it.guid == guid }
            ?: snapshot()?.latestEpisode?.takeIf { it.guid == guid }?.let {
                PodcastEpisode(guid = it.guid, title = it.title, season = it.season, episode = it.episode, pubDate = it.pubDate)
            }
}

/** Spoken lines for 1.1 actions. In-app TTS (or a notification fallback) reads these after the screen is shown. */
object ActionSpeech {
    const val UNKNOWN_MOVIE = "That movie isn’t in the catalog."
    const val UNKNOWN_EPISODE = "That episode isn’t cached yet."
    const val EMPTY_LATEST = "The latest episode isn’t cached yet. Open Movie Roulette to refresh the episode list."
    const val EMPTY_WATCHLIST = "Your watchlist is empty."
    const val CATALOG_UNAVAILABLE = "The movie catalog isn’t loaded yet."
    const val SUGGESTIONS_UNAVAILABLE = "Suggestions aren’t available on this device. You can spin the wheel instead."
    const val SUGGESTIONS_NOT_READY = "Suggestions aren’t ready yet. You can spin the wheel instead."
    const val SUGGESTIONS_FAILED = "I couldn’t pick a movie. You can spin the wheel instead."

    fun titleAndYear(movie: Movie): String = "${movie.title} (${movie.year})"

    fun episode(ep: PodcastEpisode, zone: ZoneId): String {
        val date = ep.pubDate?.let { EpisodeDates.short(it, zone) }
        val label = ep.seasonEpisodeLabel
        val name = if (label != null) "${ep.title} ($label)" else ep.title
        return if (date != null) "The latest episode is $name, from $date." else "The latest episode is $name."
    }

    fun watchlist(titles: List<String>): String = "Your watchlist: ${join(titles)}."

    fun alreadyOnWatchlist(movie: Movie): String = "${movie.title} is already on your watchlist."

    fun addedToWatchlist(movie: Movie): String = "Added ${movie.title} to your watchlist."

    fun markedWatched(movie: Movie): String = "Marked ${movie.title} as watched."

    fun openingOn(providerName: String): String = "Opening on $providerName."

    fun unknownProvider(movie: Movie, name: String): String =
        "I couldn’t find $name for ${movie.title}."

    fun noMovieNamed(title: String, year: Int?): String =
        if (year != null) "I couldn’t find $title ($year) in the catalog."
        else "I couldn’t find $title in the catalog."

    fun disambiguate(movies: List<Movie>): String {
        val options = join(movies.map { titleAndYear(it) })
        return "There are several matching movies. Did you mean $options?"
    }

    fun whereToWatch(movie: Movie, services: MyServices): String {
        val display = WhereToWatchDisplay.of(movie)
        if (!display.showsProviders) return "${movie.title} has no streaming sources listed."
        val g = ActionCoordinator.providerGroups(movie, services)
        val parts = mutableListOf<String>()
        when {
            g.myServices.isNotEmpty() -> {
                val svc = if (g.myServices.size == 1) "one of your services" else "your services"
                parts += "${movie.title} is on ${join(g.myServices.map { it.name })}, $svc"
                if (g.otherStream.isNotEmpty()) parts += "It’s also on ${join(g.otherStream.map { it.name })}"
            }
            g.otherStream.isNotEmpty() -> parts += "${movie.title} is on ${join(g.otherStream.map { it.name })}"
            else -> parts += movie.title
        }
        if (g.rentOrBuy.isNotEmpty()) {
            parts += "You can rent or buy it on ${join(g.rentOrBuy.map { it.name })}"
        }
        return parts.joinToString(". ") + "."
    }

    fun suggestion(movie: Movie, why: String): String {
        val trimmed = why.trim()
        val reason = if (trimmed.isEmpty()) "" else " $trimmed"
        return "How about ${titleAndYear(movie)}.$reason"
    }

    fun join(names: List<String>): String = when (names.size) {
        0 -> ""
        1 -> names[0]
        2 -> "${names[0]} and ${names[1]}"
        else -> names.dropLast(1).joinToString(", ") + ", and ${names.last()}"
    }
}
