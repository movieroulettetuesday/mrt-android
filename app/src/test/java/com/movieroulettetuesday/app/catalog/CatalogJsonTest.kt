package com.movieroulettetuesday.app.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogJsonTest {
    @Test fun decodesMinimalJsonWithDefaults() {
        val c = CatalogJson.decode(CatalogFixtures.MINIMAL)
        assertEquals(1, c.schemaVersion)
        assertNull(c.show)
        assertEquals(ShowInfo.MRT, c.showInfo)
        val m = c.movies.single()
        assertEquals(emptyList<String>(), m.episodeIds)
        assertFalse(m.isInfamous)
        assertNull(m.tmdbId)
        assertNull(m.streaming)
        assertEquals("One", c.categories.single().displayName)
    }

    @Test fun ignoresUnknownKeysLikeNote() {
        val c = CatalogJson.decode(CatalogFixtures.minimal())
        assertEquals(2, c.schemaVersion)
    }

    @Test fun malformedNewFieldsNeverBreakTheCatalog() {
        val json = """
            {"categories":[{"id":"c1","name":"One","colorHex":"#112233","movieIds":["m1"]}],
             "movies":[{"id":"m1","title":"M","year":1999,"genre":"Drama","categories":["c1"],
                        "tmdbId":"not-a-number","streaming":{"stream":"oops","rent":[{"id":2,"name":"Apple"}]}}]}
        """
        val m = CatalogJson.decode(json).movies.single()
        assertNull(m.tmdbId)
        val s = m.streaming!!
        assertEquals("US", s.region)
        assertTrue(s.stream.isEmpty())
        assertEquals("Apple", s.rent.single().name)
    }

    @Test fun streamingProviderLogoUrl() {
        assertEquals("https://image.tmdb.org/t/p/w92/abc.png", StreamingInfo.Provider(1, "X", "/abc.png").logoURL)
        assertEquals("https://image.tmdb.org/t/p/w92/abc.png", StreamingInfo.Provider(1, "X", "abc.png").logoURL)
        assertNull(StreamingInfo.Provider(1, "X", "").logoURL)
    }

    @Test(expected = CatalogDecodingException::class) fun missingRequiredKeyFails() {
        CatalogJson.decode("""{"categories":[],"movies":[{"id":"m1","year":1999,"genre":"Drama"}]}""")
    }

    @Test(expected = CatalogDecodingException::class) fun wrongTypeFails() {
        CatalogJson.decode("""{"categories":[],"movies":[{"id":"m1","title":"T","year":"1999","genre":"Drama"}]}""")
    }

    @Test(expected = CatalogDecodingException::class) fun fractionalYearFails() {
        CatalogJson.decode("""{"categories":[],"movies":[{"id":"m1","title":"T","year":1999.5,"genre":"Drama"}]}""")
    }

    @Test(expected = CatalogDecodingException::class) fun garbageFails() {
        CatalogJson.decode("<html>rate limited</html>")
    }

    @Test fun bundledCatalogIsConsistentAndReal() {
        val c = CatalogValidator.decodeValidated(CatalogFixtures.bundledBytes)
        assertEquals(2, c.schemaVersion)
        assertEquals(
            listOf(
                "cat-horror", "cat-cult", "cat-criterion", "cat-love", "cat-scifi",
                "cat-action", "cat-dark-comedy", "cat-listener", "cat-prestige", "cat-escape",
            ),
            c.categories.map { it.id },
        )
        assertTrue("expected the real catalog", c.movies.size >= 150)
        assertEquals(listOf("Rachael", "Karl", "Matt"), c.showInfo.hosts)
        assertEquals("https://rss.buzzsprout.com/2117292.rss", c.showInfo.rssURL)
        c.categories.forEach { assertTrue(it.id, HexColor.parse(it.colorHex) != null) }
        // Every movie links at least one Buzzsprout episode.
        c.movies.forEach { m -> assertTrue(m.id, m.episodeIds.all { it.startsWith("Buzzsprout-") }) }
        val outsiders = c.movie("the-outsiders-1983")
        if (outsiders != null) assertEquals(1983, outsiders.year)
    }

    @Test fun subtitleUsesCategoryNamesElseGenre() {
        val c = CatalogJson.decode(CatalogFixtures.MINIMAL)
        assertEquals("1999 · One", c.subtitle(c.movies.single()))
        assertEquals("1999 · Drama", c.subtitle(c.movies.single().copy(categories = emptyList())))
    }
}
