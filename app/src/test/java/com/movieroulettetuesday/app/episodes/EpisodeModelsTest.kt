package com.movieroulettetuesday.app.episodes

import com.movieroulettetuesday.app.catalog.Movie
import com.movieroulettetuesday.app.episodes.EpisodeFixtures.date
import java.time.ZoneOffset
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodeModelsTest {
    private val utc = ZoneOffset.UTC
    private val episodes = listOf(
        PodcastEpisode("e5", "S3 new", pubDate = date(2026, 9), season = 3, episode = 2),
        PodcastEpisode("b1", "Bonus", pubDate = date(2026, 5)),
        PodcastEpisode("e4", "S3 first", pubDate = date(2026, 1), season = 3, episode = 1),
        PodcastEpisode("e3", "S2", pubDate = date(2025, 6), season = 2, episode = 1),
        PodcastEpisode("e1", "S1", pubDate = date(2024, 3), season = 1, episode = 1),
        PodcastEpisode("x", "Undated, no season"),
    )

    @Test
    fun bySeasonNewestFirstBonusLast() {
        val g = EpisodeGrouper.groups(episodes, EpisodeGrouping.Season, utc)
        assertEquals(listOf("season-3", "season-2", "season-1", "bonus"), g.map { it.id })
        assertEquals(listOf("Season 3", "Season 2", "Season 1", "Bonus"), g.map { it.title })
        assertEquals(listOf("e5", "e4"), g[0].episodes.map { it.guid })
        assertEquals(listOf("b1", "x"), g[3].episodes.map { it.guid })
    }

    @Test
    fun byYearAndAll() {
        val g = EpisodeGrouper.groups(episodes, EpisodeGrouping.Year, utc)
        assertEquals(listOf("year-2026", "year-2025", "year-2024", "undated"), g.map { it.id })
        assertEquals(listOf("e5", "b1", "e4"), g[0].episodes.map { it.guid })
        assertEquals(6, EpisodeGrouper.groups(episodes, EpisodeGrouping.All).single().episodes.size)
        assertTrue(EpisodeGrouper.groups(emptyList(), EpisodeGrouping.Season).isEmpty())
        assertTrue(EpisodeGrouper.groups(emptyList(), EpisodeGrouping.All).isEmpty())
        assertEquals(EpisodeGrouping.Season, EpisodeGrouping.fromKey("nonsense"))
        assertEquals(EpisodeGrouping.Year, EpisodeGrouping.fromKey("year"))
    }

    @Test
    fun expansionDefaultsAndPersistence() {
        var e = EpisodeGroupExpansion.parse("")
        assertTrue(e.isExpanded("season-3", isNewest = true))
        assertFalse(e.isExpanded("season-2", isNewest = false))
        e = e.toggled("season-3", true).toggled("season-1", false)
        assertEquals("season-1:1,season-3:0", e.serialized)
        val restored = EpisodeGroupExpansion.parse(e.serialized)
        assertFalse(restored.isExpanded("season-3", true))
        assertTrue(restored.isExpanded("season-1", false))
        assertEquals(mapOf("b" to false), EpisodeGroupExpansion.parse("junk,:1,a:1:2,b:0").states)
    }

    @Test
    fun newEpisodeBadge() {
        assertEquals(0, NewEpisodeBadge.count(episodes, null))
        assertEquals(2, NewEpisodeBadge.count(episodes, date(2026, 2)))
        assertEquals(date(2026, 9), NewEpisodeBadge.initialBaseline(episodes, now = 0))
        assertEquals(42L, NewEpisodeBadge.initialBaseline(listOf(PodcastEpisode("a", "a")), now = 42))
        assertEquals(date(2026, 9), NewEpisodeBadge.viewedTimestamp(episodes, now = date(2026, 8)))
    }

    @Test
    fun artworkResolver() {
        assertEquals("https://a.example/x.jpg", ArtworkResolver.usableURL(" https://a.example/x.jpg "))
        assertEquals("http://a.example/x.jpg", ArtworkResolver.usableURL("http://a.example/x.jpg")) // kept like iOS; loader upgrades
        assertNull(ArtworkResolver.usableURL("file:///etc/passwd"))
        assertNull(ArtworkResolver.usableURL("javascript:alert(1)"))
        assertNull(ArtworkResolver.usableURL("/relative.jpg"))
        assertNull(ArtworkResolver.usableURL(""))
        val movie = Movie("m", "M", 2000, "Drama", episodeIds = listOf("late", "early", "noart"), posterURL = "https://p/poster.jpg")
        val byGuid = mapOf(
            "late" to PodcastEpisode("late", "L", pubDate = date(2026, 1), imageURL = "https://i/late.jpg"),
            "early" to PodcastEpisode("early", "E", pubDate = date(2024, 1), imageURL = "https://i/early.jpg"),
            "noart" to PodcastEpisode("noart", "N", pubDate = date(2020, 1)),
        )
        assertEquals(listOf("https://i/early.jpg", "https://p/poster.jpg", "https://s/show.jpg"),
            ArtworkResolver.candidates(movie, byGuid, "https://s/show.jpg"))
        assertEquals(listOf("https://s/show.jpg"), ArtworkResolver.candidates(PodcastEpisode("g", "t"), "https://s/show.jpg"))
    }

    @Test
    fun cacheJsonToleratesOldShapes() {
        val json = Json { ignoreUnknownKeys = true }
        val ep = json.decodeFromString<PodcastEpisode>("""{"guid":"g","title":"t","summary":"s","future":1}""")
        assertNull(ep.fanMailURL)
        assertNull(ep.imageURL)
        val dupes = listOf(PodcastEpisode("a", "1"), PodcastEpisode("a", "2"), PodcastEpisode("b", "3"))
        assertEquals(listOf("1", "3"), PodcastEpisode.uniqued(dupes).map { it.title })
        assertEquals("1", PodcastEpisode.byGUID(dupes).getValue("a").title)
    }
}
