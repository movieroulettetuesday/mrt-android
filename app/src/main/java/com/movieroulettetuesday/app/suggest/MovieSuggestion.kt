package com.movieroulettetuesday.app.suggest

import java.net.URLEncoder

/** The pre-filled email for "Suggest a movie" (iOS `MovieSuggestion`). The user sends it from their mail app. */
data class MovieSuggestion(val title: String, val why: String = "", val category: String? = null) {
    val trimmedTitle: String get() = title.trim()
    val isValid: Boolean get() = trimmedTitle.isNotEmpty()
    val subject: String get() = "Movie suggestion: $trimmedTitle"

    val body: String
        get() {
            val lines = mutableListOf("Hi MRT Crew,", "", "I’d love to hear you cover:", "", "Movie: $trimmedTitle")
            why.trim().takeIf { it.isNotEmpty() }?.let { lines += "Why: $it" }
            category?.trim()?.takeIf { it.isNotEmpty() }?.let { lines += "Category: $it" }
            lines += listOf("", "Thanks for the show!", "", "Sent from the Movie Roulette Tuesday app")
            return lines.joinToString("\n")
        }

    /** `mailto:theMrtPodcast@gmail.com?subject=…&body=…` with %20 spaces and %2B for "+". */
    val mailtoUri: String get() = "mailto:$ADDRESS?subject=${enc(subject)}&body=${enc(body)}"

    companion object {
        const val ADDRESS = "theMrtPodcast@gmail.com"
        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    }
}

/** The in-app form (iOS `InAppSuggestion`): same limits; also the shape of the CloudKit `Suggestion` record. */
data class InAppSuggestion(val title: String, val category: String? = null, val note: String = "", val creditName: String = "") {
    enum class Problem { MissingTitle, TitleTooLong, NoteTooLong, NameTooLong }

    val trimmedTitle get() = title.trim()
    val trimmedNote get() = note.trim()
    val trimmedCreditName get() = creditName.trim()
    val trimmedCategory: String? get() = category?.trim()?.ifEmpty { null }

    val problems: List<Problem>
        get() = buildList {
            if (trimmedTitle.isEmpty()) add(Problem.MissingTitle)
            if (trimmedTitle.length > TITLE_LIMIT) add(Problem.TitleTooLong)
            if (trimmedNote.length > NOTE_LIMIT) add(Problem.NoteTooLong)
            if (trimmedCreditName.length > NAME_LIMIT) add(Problem.NameTooLong)
        }

    val isValid: Boolean get() = problems.isEmpty()
    val noteCharactersLeft: Int get() = NOTE_LIMIT - trimmedNote.length

    /** The same pick as an email; the credit name goes in as "From: …" exactly like iOS. */
    val asEmail: MovieSuggestion
        get() {
            var why = trimmedNote
            if (trimmedCreditName.isNotEmpty()) why += (if (why.isEmpty()) "" else "\n") + "From: $trimmedCreditName"
            return MovieSuggestion(title, why, trimmedCategory)
        }

    companion object {
        const val RECORD_TYPE = "Suggestion"
        const val TITLE_LIMIT = 120
        const val NOTE_LIMIT = 280
        const val NAME_LIMIT = 60
        fun capped(text: String, limit: Int) = if (text.length > limit) text.take(limit) else text
    }
}

/** One in-app suggestion per 10 minutes on this device (iOS `SuggestionRateLimit`). Epoch ms. */
data class SuggestionRateLimit(val lastSent: Long? = null, val intervalMs: Long = INTERVAL_MS) {
    fun secondsRemaining(now: Long): Int? {
        val last = lastSent ?: return null
        val elapsed = now - last
        if (elapsed < 0 || elapsed >= intervalMs) return null
        return maxOf(1, Math.ceil((intervalMs - elapsed) / 1000.0).toInt())
    }

    fun canSend(now: Long) = secondsRemaining(now) == null

    companion object {
        const val INTERVAL_MS = 600_000L
        const val STORAGE_KEY = "suggestions.lastSentAt"
        fun message(seconds: Int): String = "You just sent one. Try again in ${maxOf(1, Math.ceil(seconds / 60.0).toInt())} min."
    }
}
