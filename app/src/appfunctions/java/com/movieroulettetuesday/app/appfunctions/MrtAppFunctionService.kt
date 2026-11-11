package com.movieroulettetuesday.app.appfunctions

import com.movieroulettetuesday.app.MrtApplication
import com.movieroulettetuesday.app.actions.ActionCoordinator
import com.movieroulettetuesday.app.actions.ActionPlan
import com.movieroulettetuesday.app.actions.DeepLink

/**
 * Intended Android 16 App Functions entry point. THIS FILE IS NOT COMPILED by the default
 * (flag-off) build: see `app/src/appfunctions/README.md`. The annotations below are the 1.0.0-rc01
 * shape (`@AppFunctionServiceEntryPoint`, `@AppFunctionDeclaration`) and would be wired with
 * `androidx.appfunctions` only when `mrt.appFunctions=true` and compileSdk is 37+.
 *
 * Each function is a thin wrapper around [ActionCoordinator] / [DeepLink], then asks
 * [MrtApplication] to run the resulting plan on the already-open (or just-opened) activity.
 *
 * Do not import androidx.appfunctions from main sources.
 */
@Suppress("unused")
abstract class MrtAppFunctionService {

    /**
     * Open Movie Roulette and play the wheel. One pick, history recorded once.
     */
    suspend fun spin(): ActionPlan = dispatch(DeepLink.Spin)

    /**
     * Open a catalog movie by its stable id (for example `laura-1944`).
     * @param movieId Catalog movie id, copied exactly.
     */
    suspend fun openMovie(movieId: String): ActionPlan = dispatch(DeepLink.OpenMovie(movieId))

    /**
     * Open the in-app page for the latest cached episode of Movie Roulette Tuesday.
     */
    suspend fun latestEpisode(): ActionPlan = dispatch(DeepLink.LatestEpisode)

    /**
     * Show the Want-to-watch list and speak the titles.
     */
    suspend fun showWatchlist(): ActionPlan = dispatch(DeepLink.Watchlist)

    /**
     * Add a catalog movie to the watchlist. Does not invent a rating.
     * @param movieId Catalog movie id, copied exactly.
     */
    suspend fun addToWatchlist(movieId: String): ActionPlan = dispatch(DeepLink.AddToWatchlist(movieId))

    /**
     * Mark a catalog movie as watched. Replaces Want to watch. Does not invent a rating.
     * @param movieId Catalog movie id, copied exactly.
     */
    suspend fun markWatched(movieId: String): ActionPlan = dispatch(DeepLink.MarkWatched(movieId))

    /**
     * Speak where a movie can be watched, favoring My Services, and open that section.
     * @param movieId Catalog movie id, copied exactly.
     */
    suspend fun whereToWatch(movieId: String): ActionPlan = dispatch(DeepLink.WhereToWatch(movieId))

    /**
     * Open a movie on a named streaming provider (installed app, otherwise https / TMDB).
     * @param movieId Catalog movie id, copied exactly.
     * @param provider Provider name, for example Netflix.
     */
    suspend fun openOnProvider(movieId: String, provider: String): ActionPlan =
        dispatch(DeepLink.OpenOnProvider(movieId, provider))

    /**
     * On-device “What should I watch?”. Offers Spin when the model is unavailable. Does not spin
     * automatically.
     */
    suspend fun whatShouldIWatch(): ActionPlan = dispatch(DeepLink.Suggest)

    private fun dispatch(link: DeepLink): ActionPlan {
        // When compiled, this service runs in the app process and can reach MrtApplication.
        val app = application()
        app.dispatch(link)
        return app.coordinator().plan(link)
    }

    /** Resolved against the real Application when this source set is compiled. */
    abstract fun application(): MrtApplication
}
