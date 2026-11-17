package com.movieroulettetuesday.app.search.nl

/**
 * The Prompt API request. Nano cannot constrain values (the structured-output API is alpha), so the
 * prompt lists the shared vocab and rules and asks for one JSON object; [NLSearchValidator] enforces it.
 */
object NLSearchPrompt {
    fun build(question: String): String = buildString {
        append("You turn a search request for a movie podcast app into one JSON object. Reply with JSON only.\n")
        append("Keys (omit or use null when not asked for): title (title words only, never genre/year/service words), ")
        append("kind (\"movie\" only if the request says movie(s) or film(s); \"episode\" only if it says episode(s) or is a season/episode request like S4E39; otherwise omit), ")
        append("yearMin, yearMax (inclusive years; \"80s\" means 1980 to 1989), genre, category, provider (short streaming service name, e.g. Shudder, Netflix, Tubi, HBO Max), ")
        append("freeOnly (true for free), ratingMin, ratingMax (the user's own stars, 0.5 to 5), unrated (true for movies the user has not rated), ")
        append("watched (\"watched\", \"unwatched\" for not watched or not seen, \"watchlist\"), season, episode, onMyServices (true for \"on my services\").\n")
        append("genre must be one of: ").append(NLSearchVocab.GENRES.joinToString(", ")).append(". Otherwise omit genre.\n")
        append("category must be one of: ")
        append(NLSearchVocab.CATEGORIES.joinToString(", ") { if (it.fullName == it.name) it.name else "${it.name} (${it.fullName})" })
        append(". Use the short name. Otherwise omit category.\n")
        append("Never guess a genre, year or kind from a title. A bare title or service name is {}.\n")
        append("Examples:\n")
        append("free 80s horror I haven't watched -> {\"genre\":\"Horror\",\"yearMin\":1980,\"yearMax\":1989,\"freeOnly\":true,\"watched\":\"unwatched\"}\n")
        append("movies rated 4 or higher on my services -> {\"kind\":\"movie\",\"ratingMin\":4,\"onMyServices\":true}\n")
        append("S4E39 The Devil's Rain -> {\"kind\":\"episode\",\"season\":4,\"episode\":39,\"title\":\"The Devil's Rain\"}\n")
        append("find me The Thing -> {\"title\":\"The Thing\"}\n")
        append("Request: ").append(question.replace('\n', ' ').take(MAX_QUESTION)).append("\nJSON:")
    }

    const val MAX_QUESTION = 200
}
