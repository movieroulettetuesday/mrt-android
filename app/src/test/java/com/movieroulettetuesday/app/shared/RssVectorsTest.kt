package com.movieroulettetuesday.app.shared

import com.movieroulettetuesday.app.episodes.EpisodeTotals
import com.movieroulettetuesday.app.episodes.PodcastEpisode
import com.movieroulettetuesday.app.episodes.PodcastFeed
import com.movieroulettetuesday.app.episodes.PodcastLinks
import com.movieroulettetuesday.app.episodes.RssFeedException
import com.movieroulettetuesday.app.episodes.RssFeedParser
import com.movieroulettetuesday.app.episodes.ShowNotes
import com.movieroulettetuesday.app.roulette.EpisodeLabel
import java.time.Instant
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** rss.json: feed parsing (incl. D11 acceptable outcomes), dates, show notes, links, D7 "Discussed on". */
@RunWith(RobolectricTestRunner::class) // XmlPullParser comes from the Android framework
class RssVectorsTest {
    private val v = SharedVectors.load("rss.json")

    private fun ms(iso: String?) = iso?.let { Instant.parse(it).toEpochMilli() }

    private fun matches(e: JsonObject, result: Result<PodcastFeed>, name: String): Boolean {
        if (e.bool("ok") == false) {
            val err = result.exceptionOrNull() as? RssFeedException ?: return false
            return when (e.str("error")) {
                "emptyDocument" -> err.message == RssFeedException.empty().message
                "malformed" -> err.message == RssFeedException.malformed().message
                else -> false
            }
        }
        val feed = result.getOrNull() ?: return false
        e.str("title")?.let { if (it != feed.title) return false }
        if (e.containsKey("artworkURL") && e.str("artworkURL") != feed.artworkURL) return false
        val eps = e["episodes"]?.objs() ?: return feed.episodes.isEmpty() || !e.containsKey("episodes")
        assertEquals(name, eps.size, feed.episodes.size)
        eps.zip(feed.episodes).forEach { (x, ep) -> assertEpisode("$name ${ep.guid}", x, ep) }
        return true
    }

    private fun assertEpisode(tag: String, x: JsonObject, ep: PodcastEpisode) {
        assertEquals(tag, x.str("guid"), ep.guid)
        assertEquals(tag, x.str("title"), ep.title)
        assertEquals(tag, ms(x.str("pubDate")), ep.pubDate)
        assertEquals(tag, x.str("link"), ep.link)
        assertEquals(tag, x.int("season"), ep.season)
        assertEquals(tag, x.int("episode"), ep.episode)
        assertEquals(tag, x.str("episodeType"), ep.episodeType)
        assertEquals(tag, x.str("summary"), ep.summary)
        assertEquals(tag, x.str("imageURL"), ep.imageURL)
        assertEquals(tag, x.str("fanMailURL"), ep.fanMailURL)
        assertEquals(tag, x.str("seasonEpisodeLabel"), ep.seasonEpisodeLabel)
    }

    @Test
    fun feeds() {
        for (f in v.arr("feeds").objs()) {
            val name = f.str("name")!!
            val result = runCatching { RssFeedParser.parse(f.str("input")!!) }
            val options = listOf(f.obj("expected")) + (f["acceptable"]?.objs() ?: emptyList())
            assertTrue("$name: $result not in $options", options.any { matches(it, result, name) })
        }
    }

    @Test
    fun parseDate() {
        for (c in v.arr("parseDate").objs()) assertEquals("'${c.str("input")}'", ms(c.str("expected")), RssFeedParser.parseDate(c.str("input")!!))
    }

    @Test
    fun showNotes() {
        for (c in v.arr("fanMailURL").objs()) assertEquals(c.str("input"), c.str("expected"), ShowNotes.fanMailURL(c.str("input")!!))
        for (c in v.arr("stripFanMail").objs()) assertEquals(c.str("input"), c.str("expected"), ShowNotes.strippingFanMail(c.str("input")!!))
    }

    /** Resolver identical to iOS (http kept); the https upgrade lives in the image loader only. */
    @Test
    fun artworkResolverMatchesIosAndLoaderUpgrades() {
        for (c in v.obj("androidCrossCheck").arr("artworkUsableURL").objs()) {
            assertEquals(c.str("input"), c.str("expected"), com.movieroulettetuesday.app.episodes.ArtworkResolver.usableURL(c.str("input")))
        }
        val loader = com.movieroulettetuesday.app.ui.ArtworkLoader
        assertEquals(
            listOf("https://a.example/x.jpg", "https://show.example/s.jpg"),
            loader.transportURLs(listOf("http://a.example/x.jpg", "https://a.example/x.jpg", "ftp://x/y", "https://show.example/s.jpg")),
        )
    }

    @Test
    fun links() {
        val l = v.obj("links")
        for (c in l.arr("appleSearch").objs()) assertEquals(c.str("url"), PodcastLinks.appleSearch(c.str("episodeTitle")!!))
        for (c in l.arr("buzzsproutEpisode").objs()) assertEquals(c.str("guid"), c.str("url"), PodcastLinks.buzzsproutEpisode(c.str("guid")!!))
        for (c in l.arr("spotifySearch").objs()) {
            assertEquals(c.str("input"), c.str("query"), PodcastLinks.spotifySearchQuery(c.str("input")!!))
            assertEquals(c.str("input"), c.str("url"), PodcastLinks.spotifySearch(c.str("input")!!))
        }
    }

    /** D7: the feed title and SxEy label win once the feed is loaded. */
    @Test
    fun matchingAndDiscussedOn() {
        val m = v.obj("matching")
        val catalog = SharedVectors.catalog(m["catalog"]!!)
        val feed = RssFeedParser.parse(v.arr("feeds").objs()[0].str("input")!!).episodes +
            PodcastEpisode(guid = "Buzzsprout-2", title = "Second", season = 1, episode = 2) // as tools/vector-gen
        assertEquals(m["feedGuids"]!!.strings(), feed.map { it.guid })
        val byGuid = PodcastEpisode.byGUID(feed)
        for (c in m.arr("cases").objs()) assertEquals("'${c.str("guid")}'", c.str("movieId"), catalog.movieForEpisodeGuid(c.str("guid")!!)?.id)
        for (c in m.arr("discussedText").objs()) {
            val movie = catalog.movie(c.str("movieId")!!) ?: catalog.movies[0].copy(id = c.str("movieId")!!, episodeIds = emptyList())
            val got = EpisodeLabel.discussedText(movie, if (c.bool("feedLoaded") == true) byGuid else emptyMap())
            // answers-from-ios.md Q9: identical labels collapse (iOS b673df1 still prints "X & X").
            val collapsed = mapOf(
                "Discussed on Double Feature & Double Feature" to "Discussed on Double Feature",
                "Discussed on Triple + 2 more" to "Discussed on Triple", // 3 ids, one distinct label
                "Discussed on Second (S1E2) + 2 more" to "Discussed on Second (S1E2) & Triple", // 3 ids, two distinct
            )
            val expected = collapsed[c.str("expected")] ?: c.str("expected")
            assertEquals("${movie.id} loaded=${c.bool("feedLoaded")}", expected, got)
        }
        val totals = m.obj("episodeTotals")
        val t = EpisodeTotals.of(feed, catalog)
        assertEquals(totals.int("feedEpisodes"), t.feedEpisodes)
        assertEquals(totals.int("feedEpisodesWithFilm"), t.feedEpisodesWithFilm)
        assertEquals(totals.int("catalogEpisodeCount"), catalog.movies.flatMap { it.episodeIds }.toSet().size)
    }
}
