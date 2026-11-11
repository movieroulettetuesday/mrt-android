package com.movieroulettetuesday.app.actions

import android.content.Context
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.movieroulettetuesday.app.R
import com.movieroulettetuesday.app.catalog.Movie

/** Dynamic launcher shortcuts for recent catalog movies. Static shortcuts live in `res/xml/shortcuts.xml`. */
object MrtShortcuts {
    const val MAX_DYNAMIC = 3
    private const val MOVIE_PREFIX = "movie:"

    fun setRecentMovies(context: Context, movies: List<Movie>) {
        val unique = movies.distinctBy { it.id }.take(MAX_DYNAMIC)
        val shortcuts = unique.mapIndexed { i, movie ->
            ShortcutInfoCompat.Builder(context, MOVIE_PREFIX + movie.id)
                .setShortLabel(movie.title.take(20))
                .setLongLabel("${movie.title} (${movie.year})")
                .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_movie))
                .setIntent(DeepLink.OpenMovie(movie.id).intent(context))
                .setRank(i)
                .build()
        }
        runCatching { ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts) }
    }

    /** Puts [movie] at the front of the dynamic list, dropping the oldest if needed. */
    fun rememberMovie(context: Context, movie: Movie) {
        runCatching { ShortcutManagerCompat.reportShortcutUsed(context, MOVIE_PREFIX + movie.id) }
        val existing = runCatching { ShortcutManagerCompat.getDynamicShortcuts(context) }.getOrElse { emptyList() }
            .filter { it.id.startsWith(MOVIE_PREFIX) && it.id != MOVIE_PREFIX + movie.id }
        val ids = listOf(movie) + existing.mapNotNull { s ->
            val id = s.id.removePrefix(MOVIE_PREFIX)
            // Title/year from the existing shortcut are enough to rebuild; use the label.
            val label = s.longLabel?.toString() ?: s.shortLabel.toString()
            val year = Regex("""\((\d{4})\)$""").find(label)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val title = label.replace(Regex("""\s*\(\d{4}\)$"""), "")
            Movie(id = id, title = title, year = year, genre = "")
        }
        setRecentMovies(context, ids)
    }

    fun removeStale(context: Context, knownIds: Set<String>) {
        val stale = runCatching { ShortcutManagerCompat.getDynamicShortcuts(context) }.getOrElse { emptyList() }
            .map { it.id }
            .filter { it.startsWith(MOVIE_PREFIX) && it.removePrefix(MOVIE_PREFIX) !in knownIds }
        if (stale.isNotEmpty()) runCatching { ShortcutManagerCompat.removeDynamicShortcuts(context, stale) }
    }
}
