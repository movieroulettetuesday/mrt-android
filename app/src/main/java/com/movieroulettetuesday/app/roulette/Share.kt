package com.movieroulettetuesday.app.roulette

import android.content.Context
import android.content.Intent
import com.movieroulettetuesday.app.catalog.Movie

/** Text share for a reveal (iOS RevealSheet ShareLink message + subject). The image card is not ported yet. */
object SpinShare {
    fun message(movie: Movie): String = "Tonight’s Movie Roulette Tuesday spin: ${movie.title} (${movie.year})"

    fun intent(movie: Movie): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, movie.title)
            putExtra(Intent.EXTRA_TEXT, message(movie))
        }
        return Intent.createChooser(send, null)
    }

    fun share(context: Context, movie: Movie) {
        context.startActivity(intent(movie).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** "Discussed on …" line (RouletteCore/EpisodeLabel.swift, catalog-only until the Episodes feed is ported). */
object EpisodeLabel {
    fun discussedText(movie: Movie): String {
        val ids = movie.episodeIds
        if (ids.isEmpty()) return "Not discussed on the show yet"
        fun label(id: String) = if (ids.contains(id)) movie.episodeTitle ?: id else id
        return when (ids.size) {
            1 -> "Discussed on ${label(ids[0])}"
            2 -> "Discussed on ${label(ids[0])} & ${label(ids[1])}"
            else -> "Discussed on ${label(ids[0])} + ${ids.size - 1} more"
        }
    }
}
