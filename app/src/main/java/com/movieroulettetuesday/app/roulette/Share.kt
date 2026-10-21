package com.movieroulettetuesday.app.roulette

import android.content.Context
import android.content.Intent
import com.movieroulettetuesday.app.catalog.Movie
import com.movieroulettetuesday.app.episodes.PodcastEpisode

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

/** "Discussed on …" line (RouletteCore/EpisodeLabel.swift, feed titles and SxEy once the feed is loaded). */
object EpisodeLabel {
    /** Resolve a Buzzsprout guid to a human title: feed title, else the catalog's episodeTitle, else the id. */
    const val SAMPLE_PREFIX = "sample-ep-"

    /** `sample-ep-6` -> 6 (placeholder ids in the bundled sample data). */
    fun sampleEpisodeNumber(id: String): Int? = if (id.startsWith(SAMPLE_PREFIX)) id.removePrefix(SAMPLE_PREFIX).toIntOrNull() else null

    fun title(id: String, movie: Movie? = null, episodesByGUID: Map<String, PodcastEpisode> = emptyMap()): String {
        episodesByGUID[id]?.let { return it.title }
        if (movie != null && movie.episodeTitle != null && id in movie.episodeIds) return movie.episodeTitle
        sampleEpisodeNumber(id)?.let { return "Episode $it" }
        return id
    }

    /** "Discussed on The Outsiders (S4E34)" etc. Port of `EpisodeLabel.discussedText`. */
    fun discussedText(movie: Movie, episodesByGUID: Map<String, PodcastEpisode> = emptyMap()): String {
        val ids = movie.episodeIds
        if (ids.isEmpty()) return "Not discussed on the show yet"
        val numbers = ids.mapNotNull(::sampleEpisodeNumber)
        if (numbers.size == ids.size) {
            return when (numbers.size) {
                1 -> "Discussed on Episode ${numbers[0]} (sample)"
                2 -> "Discussed on Episodes ${numbers[0]} & ${numbers[1]} (sample)"
                else -> "Discussed on Episode ${numbers[0]} + ${numbers.size - 1} more (sample)"
            }
        }
        fun label(id: String): String {
            val t = title(id, movie, episodesByGUID)
            return episodesByGUID[id]?.seasonEpisodeLabel?.let { "$t ($it)" } ?: t
        }
        // Identical labels collapse ("X & X" -> "X"; 3+ counts distinct labels), per MRT App's
        // answers-from-ios.md Q9 (iOS 1.0.1 candidate).
        val labels = ids.map(::label).distinct()
        return when (labels.size) {
            1 -> "Discussed on ${labels[0]}"
            2 -> "Discussed on ${labels[0]} & ${labels[1]}"
            else -> "Discussed on ${labels[0]} + ${labels.size - 1} more"
        }
    }
}
