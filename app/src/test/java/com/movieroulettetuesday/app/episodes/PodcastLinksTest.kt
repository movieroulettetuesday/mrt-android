package com.movieroulettetuesday.app.episodes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** Ports of ShowLinksTests.swift (real titles from the Buzzsprout feed). */
class PodcastLinksTest {
    @Test
    fun spotifyQueryRealTitles() {
        val cases = listOf(
            "The Outsiders" to "The Outsiders Movie Roulette Tuesday",
            "Palmer Rockey's SCARLET WARNING 666" to "Palmer Rockey's SCARLET WARNING 666 Movie Roulette Tuesday",
            "Romeo + Juliet" to "Romeo Juliet Movie Roulette Tuesday",
            "Rock & Rule" to "Rock Rule Movie Roulette Tuesday",
            "Mad Max: Fury Road" to "Mad Max Fury Road Movie Roulette Tuesday",
            "O Brother, Where Art Thou?" to "O Brother Where Art Thou Movie Roulette Tuesday",
            "Kon-Tiki" to "Kon-Tiki Movie Roulette Tuesday",
            "Cry-Baby" to "Cry-Baby Movie Roulette Tuesday",
            "π" to "π Movie Roulette Tuesday",
            "Season 3: Year In Review" to "Season 3 Year In Review Movie Roulette Tuesday",
            "What is Movie Roulette Tuesday?" to "What is Movie Roulette Tuesday",
        )
        cases.forEach { (title, expected) -> assertEquals(title, expected, PodcastLinks.spotifySearchQuery(title)) }
    }

    @Test
    fun spotifyQueryStripsPrefixesAndEmoji() {
        val cases = listOf(
            "S4 · E34 – The Outsiders" to "The Outsiders Movie Roulette Tuesday",
            "S4E34: The Outsiders 🎬" to "The Outsiders Movie Roulette Tuesday",
            "Ep. 11 - Romeo + Juliet" to "Romeo Juliet Movie Roulette Tuesday",
            "Episode 34: The Outsiders" to "The Outsiders Movie Roulette Tuesday",
            "#29 | Crash" to "Crash Movie Roulette Tuesday",
            "  Palmer Rockey\u2019s SCARLET WARNING 666 🔥 " to "Palmer Rockey's SCARLET WARNING 666 Movie Roulette Tuesday",
            "12 Angry Men" to "12 Angry Men Movie Roulette Tuesday",
            "2001: A Space Odyssey" to "2001 A Space Odyssey Movie Roulette Tuesday",
            "E.T. the Extra-Terrestrial" to "E T the Extra-Terrestrial Movie Roulette Tuesday",
            "Episode 12" to "Movie Roulette Tuesday",
            "Buzzsprout-19913482" to "Movie Roulette Tuesday",
            "" to "Movie Roulette Tuesday",
        )
        cases.forEach { (title, expected) -> assertEquals(title, expected, PodcastLinks.spotifySearchQuery(title)) }
    }

    @Test
    fun spotifySearchUrlEncodedOnce() {
        val cases = listOf(
            "Romeo + Juliet" to "Romeo%20Juliet%20Movie%20Roulette%20Tuesday",
            "Palmer Rockey's SCARLET WARNING 666" to "Palmer%20Rockey%27s%20SCARLET%20WARNING%20666%20Movie%20Roulette%20Tuesday",
            "Kon-Tiki" to "Kon-Tiki%20Movie%20Roulette%20Tuesday",
            "π" to "%CF%80%20Movie%20Roulette%20Tuesday",
        )
        for ((title, path) in cases) {
            val url = PodcastLinks.spotifySearch(title)!!
            assertEquals("https://open.spotify.com/search/$path", url)
            assertFalse(url.contains("%25"))
            assertFalse(url.contains("+"))
        }
    }

    @Test
    fun showAndEpisodeLinks() {
        assertEquals("https://overcast.fm/itunes1666123849", PodcastLinks.overcastShow())
        assertEquals("https://www.buzzsprout.com/2117292", PodcastLinks.buzzsproutShow())
        assertEquals("https://www.buzzsprout.com/2117292/episodes/19913482", PodcastLinks.buzzsproutEpisode("Buzzsprout-19913482"))
        assertNull(PodcastLinks.buzzsproutEpisode("https://example.com/ep"))
        assertNull(PodcastLinks.buzzsproutEpisode("Buzzsprout-abc"))
        assertEquals("https://itunes.apple.com/lookup?id=1666123849&entity=podcastEpisode&limit=200", AppleEpisodeLookup.lookupURL())
    }

    @Test
    fun appleLookupParseAndListenCard() {
        val json = """{"resultCount":4,"results":[
            {"wrapperType":"track","kind":"podcast","collectionId":1666123849,"trackId":1666123849},
            {"wrapperType":"podcastEpisode","kind":"podcast-episode","collectionId":1666123849,"trackId":1000731234567,"episodeGuid":"Buzzsprout-19913482"},
            {"wrapperType":"podcastEpisode","collectionId":999,"trackId":5,"episodeGuid":"Buzzsprout-other-show"},
            {"wrapperType":"podcastEpisode","collectionId":1666123849,"trackId":0,"episodeGuid":"Buzzsprout-zero"}]}"""
        val map = AppleEpisodeLookup.parse(json.toByteArray())
        assertEquals(mapOf("Buzzsprout-19913482" to 1000731234567L), map)

        val ep = PodcastEpisode("Buzzsprout-19913482", "The Outsiders")
        val links = ListenLinks.forEpisode(ep, map)
        assertEquals(listOf("listen-apple", "listen-spotify", "listen-overcast", "listen-buzzsprout"), links.map { it.id })
        assertEquals("https://podcasts.apple.com/us/podcast/id1666123849?i=1000731234567", links[0].url)
        assertEquals("This episode", links[0].detail)
        assertEquals("https://www.buzzsprout.com/2117292/episodes/19913482", links[3].url)

        val unmatched = ListenLinks.forEpisode(PodcastEpisode("x", "Laura"), emptyMap())
        assertEquals("Show page (episode not listed yet)", unmatched[0].detail)
        assertEquals("https://www.buzzsprout.com/2117292", unmatched[3].url)
    }
}
