package com.movieroulettetuesday.app.search.nl

import com.movieroulettetuesday.app.search.CatalogSearch

/**
 * Decides whether text in the existing Movies / Episodes search field is a request for the on-device
 * parse or plain keywords for CatalogSearch (no separate ask mode; Matthew's pick 1, 16 Nov 2026).
 * Same triggers as the iOS plan: a request word ("find", "what", "show me"), a decade or year range,
 * "free", watched / rating words, "on [service]", an S#E# or "season / episode" request, a list word
 * ("movies", "films", "episodes"), or five or more words. Short title-like text ("the thing",
 * "Shudder") stays keyword search.
 */
object NLQuestionDetector {
    private val leadPhrases = listOf(
        "find", "show", "what", "whats", "which", "give me", "list", "any", "anything", "something",
        "recommend", "suggest", "search", "i want", "looking for", "how about", "got any",
    )
    private val triggerWords = setOf(
        "free", "unwatched", "unrated", "unseen", "watchlist", "watched", "seen", "havent", "hasnt", "never",
        "rated", "rating", "ratings", "stars", "movies", "films", "episodes", "season", "decade",
    )
    private val decade = Regex("^(\\d{2}|\\d{4})s$")
    private val seasonEpisode = Regex("^(s\\d{1,3}e\\d{1,4}|\\d{1,3}x\\d{1,4})$")
    private val number = Regex("^\\d{1,4}$")

    fun looksLikeQuestion(raw: String): Boolean {
        val folded = CatalogSearch.fold(raw)
        val words = folded.split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return false
        if (words.size >= 5) return true
        if (leadPhrases.any { folded == it || folded.startsWith("$it ") }) return true
        if (folded.contains("my services")) return true
        if (words.any { it in triggerWords }) return true
        if (words.any { decade.matches(it) || seasonEpisode.matches(it) }) return true
        val years = words.count { it.length == 4 && number.matches(it) && it.toInt() in 1880..2100 }
        if (years >= 2) return true
        for ((i, w) in words.withIndex()) {
            val next = words.getOrNull(i + 1) ?: continue
            if (w == "on" && i > 0) return true
            if ((w == "episode" || w == "ep") && number.matches(next)) return true
            if ((w == "before" || w == "after" || w == "since") && next.length == 4 && number.matches(next)) return true
        }
        return false
    }
}
