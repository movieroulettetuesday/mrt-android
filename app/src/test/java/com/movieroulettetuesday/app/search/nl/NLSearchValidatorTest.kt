package com.movieroulettetuesday.app.search.nl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The strict mapper with fake model replies: nothing outside the shared schema reaches apply. */
class NLSearchValidatorTest {
    private val names = NLFixtures.providerNames

    private fun filter(reply: String): NLSearchFilter {
        val p = NLSearchValidator.parse(reply, names)
        assertTrue("expected a filter for $reply, got $p", p is NLParse.Filter)
        return (p as NLParse.Filter).filter
    }

    @Test
    fun validJsonMapsFieldForField() {
        val f = filter(
            """{"title":"The Thing","kind":"movie","yearMin":1980,"yearMax":1989,"genre":"Horror","category":"Cult",
               "provider":"Shudder","freeOnly":true,"ratingMin":3.5,"ratingMax":5,"watched":"unwatched","season":4,
               "episode":39,"onMyServices":true}""",
        )
        assertEquals(
            NLSearchFilter("The Thing", NLSearchFilter.Kind.Movie, 1980, 1989, "Horror", "Cult", "Shudder", true, 3.5, 5.0,
                null, NLSearchFilter.Watched.Unwatched, 4, 39, true),
            f,
        )
    }

    @Test
    fun codeFenceAndProseAroundTheObjectAreTolerated() {
        assertEquals("Horror", filter("```json\n{\"genre\":\"Horror\"}\n```").genre)
        assertEquals("Horror", filter("Sure! Here is the filter: {\"genre\":\"Horror\"} Hope that helps.").genre)
        assertEquals("a } b", filter("""{"title":"a } b"}""").title)
    }

    @Test
    fun invalidJsonIsRejected() {
        for (bad in listOf(null, "", "   ", "Horror movies", "{", "{\"genre\":}", "[\"Horror\"]", "{genre: Horror}", "{\"genre\":\"Horror\",}")) {
            assertTrue("$bad", NLSearchValidator.parse(bad, names) is NLParse.Rejected)
        }
    }

    @Test
    fun emptyOrAllNullOrAllAnyIsTheKeywordPath() {
        assertEquals(NLParse.Empty, NLSearchValidator.parse("{}", names))
        assertEquals(NLParse.Empty, NLSearchValidator.parse("""{"title":null,"genre":null,"kind":"any","watched":"any"}""", names))
        assertEquals(NLParse.Empty, NLSearchValidator.parse("""{"title":"  ","freeOnly":false,"onMyServices":false}""", names))
        assertEquals(NLParse.Empty, NLSearchValidator.parse("""{"genre":"Animation"}""", names))
    }

    @Test
    fun extraFieldsAreIgnored() {
        val f = filter("""{"genre":"Horror","hosts":["Karl"],"mood":"spooky","sort":"newest"}""")
        assertEquals(NLSearchFilter(genre = "Horror"), f)
    }

    @Test
    fun unknownEnumValuesAreNulledNotPassedThrough() {
        val f = filter("""{"genre":"Animation","category":"Documentaries","kind":"tv show","watched":"sometimes","freeOnly":true}""")
        assertEquals(NLSearchFilter(freeOnly = true), f)
        for (g in listOf("Science Fiction", "Documentary", "Rom-com")) assertEquals(null, NLSearchVocab.genre(g))
    }

    @Test
    fun genreAndCategoryAreCaseAndPunctuationInsensitive() {
        assertEquals("Sci-Fi", filter("""{"genre":"sci fi"}""").genre)
        assertEquals("Sci-Fi", filter("""{"genre":"SCI-FI"}""").genre)
        assertEquals("Listeners", filter("""{"category":"Listener Picks"}""").category)
        assertEquals("Escape", filter("""{"category":"War, Escape & Survival"}""").category)
        assertEquals("Dark Comedy", filter("""{"category":"dark comedy"}""").category)
    }

    @Test
    fun wrongTypesAreNulled() {
        val f = filter("""{"yearMin":"1980","season":"4","freeOnly":"yes","ratingMin":"4","title":42,"genre":"Horror"}""")
        assertEquals(NLSearchFilter(genre = "Horror"), f)
    }

    @Test
    fun yearsOutsideTheWindowAreDroppedNotClamped() {
        assertEquals(NLParse.Empty, NLSearchValidator.parse("""{"yearMin":1850,"yearMax":2500}""", names))
        assertEquals(NLParse.Empty, NLSearchValidator.parse("""{"yearMin":1700,"yearMax":3000}""", names))
        assertEquals(NLSearchFilter(yearMax = 1985), filter("""{"yearMin":1700,"yearMax":1985}"""))
        assertEquals(NLSearchFilter(yearMin = 1980), filter("""{"yearMin":1980,"yearMax":99}"""))
        assertEquals(NLSearchFilter(yearMin = 1880, yearMax = 2100), filter("""{"yearMin":1880,"yearMax":2100}"""))
        assertEquals(NLSearchFilter(yearMin = 1980, yearMax = 1989), filter("""{"yearMin":1989,"yearMax":1980}"""))
        assertEquals(NLSearchFilter(genre = "Horror"), filter("""{"yearMin":1879,"yearMax":2101,"genre":"Horror"}"""))
        assertEquals(NLSearchFilter(genre = "Horror"), filter("""{"yearMin":80,"yearMax":89,"genre":"Horror"}"""))
        assertEquals(NLSearchFilter(genre = "Horror"), filter("""{"yearMin":1980.5,"genre":"Horror"}"""))
    }

    @Test
    fun ratingsAreRangeCheckedRawThenRoundedHalfAwayFromZero() {
        assertEquals(NLSearchFilter(ratingMin = 3.5), filter("""{"ratingMin":3.3}"""))
        assertEquals(NLSearchFilter(ratingMin = 3.5), filter("""{"ratingMin":3.25}"""))
        assertEquals(NLSearchFilter(ratingMin = 5.0), filter("""{"ratingMin":4.75}"""))
        assertEquals(NLSearchFilter(ratingMin = 4.0), filter("""{"ratingMin":4.2}"""))
        assertEquals(NLSearchFilter(ratingMin = 0.5, ratingMax = 5.0), filter("""{"ratingMin":0.5,"ratingMax":5}"""))
        assertEquals(NLSearchFilter(ratingMin = 3.0, ratingMax = 4.5), filter("""{"ratingMin":4.5,"ratingMax":3}"""))
        // Raw value outside 0.5–5 is dropped even when it would round into range (5.2 → 5, 0.4 → 0.5).
        assertEquals(NLSearchFilter(genre = "Horror"), filter("""{"genre":"Horror","ratingMax":5.2}"""))
        assertEquals(NLSearchFilter(genre = "Horror"), filter("""{"genre":"Horror","ratingMin":0.4}"""))
        assertEquals(NLSearchFilter(ratingMin = 3.0), filter("""{"ratingMin":3,"ratingMax":6}"""))
        assertEquals(NLParse.Empty, NLSearchValidator.parse("""{"ratingMin":8,"ratingMax":0,"season":0,"episode":-1}""", names))
        assertEquals(NLParse.Empty, NLSearchValidator.parse("""{"ratingMin":0.1}""", names))
    }

    @Test
    fun unratedRules() {
        assertEquals(NLSearchFilter(unrated = true), filter("""{"unrated":true,"ratingMin":4}"""))
        assertEquals(NLSearchFilter(ratingMin = 4.0, unrated = false), filter("""{"unrated":false,"ratingMin":4}"""))
        assertEquals(NLSearchFilter(unrated = false), filter("""{"unrated":false}"""))
    }

    @Test
    fun providersMustBeCatalogStreamOrFreeNames() {
        for (ok in listOf("Shudder", "Netflix", "Tubi", "Tubi TV", "Pluto", "Disney+", "Disney Plus", "Max", "HBO Max", "Kanopy")) {
            assertEquals(ok, ok, filter("""{"provider":"$ok"}""").provider)
        }
        // Not in any stream/free list: Blockbuster, HBO Go, and rent/buy-only names.
        for (bad in listOf("Blockbuster", "HBO Go", "Vudu", "x", "+")) {
            assertEquals(bad, NLParse.Empty, NLSearchValidator.parse("""{"provider":"$bad"}""", names))
        }
    }

    @Test
    fun unratedFalseIsKeptWithARatingRangeAndChipsDoNotChange() {
        val f = filter("""{"unrated":false,"ratingMin":4}""")
        assertEquals(false, f.unrated)
        assertEquals(4.0, f.ratingMin)
        assertEquals(NLSearchFilter(ratingMin = 4.0, ratingMax = 4.5, unrated = false), filter("""{"unrated":false,"ratingMin":4.5,"ratingMax":4}"""))
        assertEquals(listOf("Rated 4★ or more"), f.chips().map { it.label })
        assertEquals(NLSearchFilter(ratingMin = 4.0).chips(), f.chips())
        assertEquals(NLSearchFilter.EMPTY, f.removing(NLChip.Field.Rating))
        // unrated:true still nulls min and max.
        assertEquals(NLSearchFilter(unrated = true), filter("""{"unrated":true,"ratingMin":4,"ratingMax":5}"""))
    }

    @Test
    fun multiWordProviderMatchesOnEveryWord() {
        // Not a substring of any name, but every word is in "Shudder Amazon Channel" / "Shudder Apple TV channel".
        assertTrue(names.none { com.movieroulettetuesday.app.search.CatalogSearch.fold(it).contains("shudder channel") })
        assertEquals("Shudder Channel", filter("""{"provider":"Shudder Channel"}""").provider)
        assertEquals("Prime Amazon", filter("""{"provider":"Prime Amazon"}""").provider)
        assertTrue(NLSearchVocab.providerMatches("Shudder Amazon Channel", "Shudder Channel"))
        assertTrue(NLSearchVocab.providerMatches("HBO Max", "Max"))
        assertTrue(!NLSearchVocab.providerMatches("Shudder", "Shudder Channel"))
    }

    @Test
    fun oneCharacterQueryIsRejected() {
        for (q in listOf("x", "a", "+", "N.", " T ")) {
            assertEquals(q, NLParse.Empty, NLSearchValidator.parse("""{"provider":"$q"}""", names))
            assertTrue(q, names.none { NLSearchVocab.providerMatches(it, q) })
            assertTrue(q, !NLSearchVocab.providerMatches("x a n t", q))
        }
        assertEquals(NLParse.Empty, NLSearchValidator.parse("""{"provider":""}""", names))
    }

    @Test
    fun wholeStringMatchCountsEvenWithAOneCharacterWord() {
        // "Channel 4" has a 1-character word, but the whole folded string is in the name.
        assertTrue(NLSearchVocab.providerMatches("Channel 4 Plus", "Channel 4"))
        assertTrue(NLSearchVocab.providerMatches("UK Channel 4", "channel-4"))
        assertEquals("Channel 4", (NLSearchValidator.parse("""{"provider":"Channel 4"}""", names + "Channel 4 Plus") as NLParse.Filter).filter.provider)
        // No whole-string hit and a 1-character word: no word fallback.
        assertTrue(!NLSearchVocab.providerMatches("Channel 5 4", "Channel 4"))
    }

    @Test
    fun oneCharacterWordFailsTheWordFallback() {
        // "netflix" and "a" are both in "Netflix Standard with Ads", but "a" is too short for the word fallback.
        assertTrue(!NLSearchVocab.providerMatches("Netflix Standard with Ads", "Netflix a"))
        assertTrue(names.none { com.movieroulettetuesday.app.search.CatalogSearch.fold(it).contains("netflix a") })
        for (q in listOf("Netflix a", "Shudder x", "a Hulu")) {
            assertEquals(q, NLParse.Empty, NLSearchValidator.parse("""{"provider":"$q"}""", names))
        }
        // It still matches as a whole string when a name contains "netflix a".
        assertTrue(NLSearchVocab.providerMatches("Netflix Asia", "Netflix a"))
        assertEquals("Netflix a", (NLSearchValidator.parse("""{"provider":"Netflix a"}""", names + "Netflix Asia") as NLParse.Filter).filter.provider)
        // Words of 2+ characters still use the fallback.
        assertTrue(NLSearchVocab.providerMatches("Netflix Standard with Ads", "Netflix Ads"))
    }

    @Test
    fun savedJsonRoundTrips() {
        val f = NLSearchFilter("Alien", NLSearchFilter.Kind.Episode, 1979, 1979, "Sci-Fi", "Sci-Fi", "Tubi", true, 0.5, 5.0,
            null, NLSearchFilter.Watched.Watchlist, 2, 10, true)
        assertEquals(f, NLSearchFilter.fromSavedJson(f.toJson()))
        assertEquals(NLSearchFilter(unrated = false), NLSearchFilter.fromSavedJson(NLSearchFilter(unrated = false).toJson()))
    }

    @Test
    fun chipsOnePerSetFieldAndRemovalClearsIt() {
        val f = NLSearchFilter(title = "The Thing", kind = NLSearchFilter.Kind.Movie, yearMin = 1980, yearMax = 1989, genre = "Horror",
            freeOnly = true, ratingMin = 4.0, watched = NLSearchFilter.Watched.Unwatched, season = 4, episode = 39, onMyServices = true,
            provider = "Shudder", category = "Cult")
        assertEquals(
            listOf("Title: The Thing", "S4E39", "1980–1989", "Horror", "Cult", "On Shudder", "Free", "Rated 4★ or more", "Unwatched", "On my services"),
            f.chips().map { it.label },
        )
        var g = f
        for (chip in f.chips()) g = g.removing(chip.field)
        assertEquals(NLSearchFilter(kind = NLSearchFilter.Kind.Movie), g)
        assertEquals(listOf("Unrated"), NLSearchFilter(unrated = true).chips().map { it.label })
        assertEquals(listOf("Rated 3–4.5★"), NLSearchFilter(ratingMin = 3.0, ratingMax = 4.5).chips().map { it.label })
        assertEquals(listOf("1990 or later"), NLSearchFilter(yearMin = 1990).chips().map { it.label })
    }

    @Test
    fun promptCarriesTheVocabAndTheQuestion() {
        val p = NLSearchPrompt.build("free 80s horror\nI haven't watched")
        for (g in NLSearchVocab.GENRES) assertTrue(g, p.contains(g))
        for (c in NLSearchVocab.CATEGORIES) assertTrue(c.name, p.contains(c.name))
        assertEquals("free 80s horror I haven't watched", FakeNLModel.questionOf(p))
        assertTrue(NLSearchPrompt.build("x".repeat(500)).contains("x".repeat(NLSearchPrompt.MAX_QUESTION) + "\nJSON:"))
    }
}
