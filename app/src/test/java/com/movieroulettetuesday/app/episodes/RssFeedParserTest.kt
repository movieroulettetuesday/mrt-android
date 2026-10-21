package com.movieroulettetuesday.app.episodes

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Ports of RSSFeedParserTests.swift plus Android-specific cases (XmlPullParser, HTTPS upgrade). */
@RunWith(AndroidJUnit4::class)
class RssFeedParserTest {
    @Test
    fun parsesIosSnippetFixture() {
        val feed = RssFeedParser.parse(EpisodeFixtures.resource("feed-snippet.xml"))
        assertTrue(feed.title.isNotEmpty())
        assertEquals(2, feed.episodes.size)
        val first = feed.episodes[0]
        assertEquals("The Outsiders", first.title)
        assertEquals("Buzzsprout-19913482", first.guid)
        assertEquals(4, first.season)
        assertEquals(34, first.episode)
        assertEquals("full", first.episodeType)
        assertNotNull(first.pubDate)
        assertFalse(first.summary.contains("<p>"))
        assertTrue("full notes, not the 500-char summary", first.summary.length > 500)
        assertFalse(first.summary.endsWith("..."))
        assertTrue(first.summary.startsWith("Hi everyone"))
        val second = feed.episodes[1]
        assertEquals("The Ugly Stepsister", second.title)
        assertEquals("Buzzsprout-19877460", second.guid)
        assertEquals(33, second.episode)
        for (ep in feed.episodes) {
            assertFalse(ep.summary.lowercase().startsWith("send us fan mail"))
            assertEquals("https://www.buzzsprout.com/2117292/fan_mail/new", ep.fanMailURL)
        }
    }

    @Test
    fun parsesLiveFeedSampleWithEdgeCases() {
        val feed = RssFeedParser.parse(EpisodeFixtures.resource("feed-sample.rss"))
        assertEquals("Movie Roulette Tuesday: The Podcast", feed.title)
        assertEquals("https://storage.buzzsprout.com/0ivqancdb8l9ww2mlieeag1x8bdr?.jpg", feed.artworkURL)
        // 3 real items + bonus + link-guid item; duplicate guid and title-less item dropped.
        assertEquals(
            listOf("Buzzsprout-19980357", "Buzzsprout-19950553", "Buzzsprout-19913482", "Buzzsprout-11111111",
                "https://www.buzzsprout.com/2117292/episodes/22222222"),
            feed.episodes.map { it.guid },
        )
        val piranha = feed.episodes[0]
        assertEquals("Piranha II:  The Spawning", piranha.title)
        assertEquals(Instant.parse("2026-10-20T04:00:00Z").toEpochMilli(), piranha.pubDate)
        assertEquals("S4E36", piranha.seasonEpisodeLabel)
        assertEquals("https://storage.buzzsprout.com/ts10xban322i29sx0oznh8gwl95h?.jpg", piranha.imageURL)
        assertTrue(piranha.summary.startsWith("Before there was Avatar"))

        val bonus = feed.episodes[3]
        assertEquals("Bonus: Listener Mailbag & Q&A", bonus.title)
        assertNull(bonus.season)
        assertEquals("bonus", bonus.episodeType)
        assertEquals("http://storage.buzzsprout.com/bonus.jpg", bonus.imageURL) // kept like iOS; ArtworkLoader upgrades the transport
        assertTrue("longest notes win", bonus.summary.startsWith("A much longer summary"))
        assertEquals(Instant.parse("2023-02-03T08:00:00Z").toEpochMilli(), bonus.pubDate)

        val linkOnly = feed.episodes[4]
        assertNull(linkOnly.pubDate)
        assertNull("non-numeric itunes:episode ignored", linkOnly.episode)
    }

    @Test
    fun emptyAndBrokenInput() {
        assertThrows(RssFeedException::class.java) { RssFeedParser.parse(ByteArray(0)) }
        assertThrows(RssFeedException::class.java) { RssFeedParser.parse("<rss><channel><item>") }
        val empty = RssFeedParser.parse("""<?xml version="1.0"?><rss version="2.0"><channel><title>Empty</title></channel></rss>""")
        assertEquals("Empty", empty.title)
        assertTrue(empty.episodes.isEmpty())
    }

    @Test
    fun dateVariants() {
        val expected = Instant.parse("2026-10-06T04:00:00Z").toEpochMilli()
        assertEquals(expected, RssFeedParser.parseDate("Tue, 06 Oct 2026 00:00:00 -0400"))
        assertEquals(expected, RssFeedParser.parseDate("06 Oct 2026 04:00:00 GMT"))
        assertEquals(expected, RssFeedParser.parseDate(" Tue, 06 Oct 2026 04:00 +0000 "))
        assertEquals(expected, RssFeedParser.parseDate("Tue, 06 Oct 2026 00:00:00 EDT"))
        assertNull(RssFeedParser.parseDate(""))
        assertNull(RssFeedParser.parseDate("yesterday"))
    }

    @Test
    fun showNotesHelpers() {
        assertEquals("Hi there", ShowNotes.strippingFanMail("Send us Fan Mail Hi there"))
        assertEquals("Hi", ShowNotes.strippingFanMail("send us fanmail: Hi"))
        assertEquals("We read fan mail today", ShowNotes.strippingFanMail("We read fan mail today"))
        assertEquals("", ShowNotes.strippingFanMail("Send us Fan Mail"))
        assertNull(ShowNotes.fanMailURL("<p>No link</p>"))
        assertNull(ShowNotes.fanMailURL("""<a href="https://example.com/x">Other</a>"""))
        assertEquals("https://www.buzzsprout.com/1/fan_mail/new",
            ShowNotes.fanMailURL("""<p><a target="_blank" href="https://www.buzzsprout.com/1/fan_mail/new">Send us Fan Mail</a></p>"""))
        assertNull("javascript never offered", ShowNotes.fanMailURL("""<a href="javascript:alert(1)">fan mail</a>"""))
        assertEquals("Hello", PodcastEpisode("g", "t", summary = "Send us Fan Mail Hello").displaySummary)
        assertEquals("a & b <c>", ShowNotes.stripHTML("<p>a &amp; b</p> &lt;c&gt;"))
    }
}
