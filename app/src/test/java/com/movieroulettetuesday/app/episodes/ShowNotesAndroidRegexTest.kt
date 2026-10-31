package com.movieroulettetuesday.app.episodes

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.regex.Pattern
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `Pattern.UNICODE_CHARACTER_CLASS` throws on Android (`IllegalArgumentException: UNICODE_CHARACTER_CLASS
 * flag not supported`) during `ShowNotes` init, so every feed parse fails. The desktop JVM accepts the
 * flag, which is why the other Robolectric tests missed it. This recompiles the patterns `ShowNotes`
 * actually uses and, on a JVM that accepts the flag, asserts the bit is off.
 */
@RunWith(AndroidJUnit4::class)
class ShowNotesAndroidRegexTest {
    @Test
    fun productionPatternsCompileWithoutUnicodeCharacterClass() {
        val patterns = listOf(ShowNotes.fanMailPrefix, ShowNotes.spaces).map { it.toPattern() }
        val androidEngine = androidRejectsUnicodeCharacterClass()
        for (pattern in patterns) {
            if (androidEngine) {
                // Real Android regex: this throws if the flag is set.
                Pattern.compile(pattern.pattern(), pattern.flags())
            } else {
                assertEquals(
                    "desktop JVM accepts UNICODE_CHARACTER_CLASS; Android's Pattern.compile rejects it (${pattern.pattern()})",
                    0,
                    pattern.flags() and Pattern.UNICODE_CHARACTER_CLASS,
                )
                Pattern.compile(pattern.pattern(), pattern.flags())
            }
            assertFalse(pattern.pattern().contains("(?U)"))
        }
    }

    @Test
    fun unicodeSpacesMatchLikeIos() {
        assertEquals("Hi", ShowNotes.strippingFanMail("Send us Fan Mail\u00A0Hi"))
        assertEquals("Hi", ShowNotes.strippingFanMail("Send us Fan Mail\u2003\u202FHi"))
        assertEquals("Hi", ShowNotes.strippingFanMail("Send us Fan Mail\u3000Hi"))
        assertEquals("Hi", ShowNotes.strippingFanMail("Send us Fan Mail\u1680Hi"))
        assertEquals("Hi", ShowNotes.strippingFanMail("Send us Fan Mail\u0085Hi"))
        assertEquals("", ShowNotes.strippingFanMail("Send us Fan Mail\u00A0"))
        assertEquals("We read fan mail today", ShowNotes.strippingFanMail("We read fan mail today"))
        assertEquals("Hello", ShowNotes.strippingFanMail("  SEND US FAN MAIL — | Hello  "))
        assertEquals("a b c", ShowNotes.stripHTML("a\u00A0b\u3000c"))
        assertEquals("a b", ShowNotes.stripHTML("<p>a\u202Fb</p>"))
        assertEquals("famous”. It’s", ShowNotes.stripHTML("famous\u201D.\u00A0 It\u2019s"))
    }

    @Test
    fun buzzsproutFixtureParsesWithArtwork() {
        val feed = RssFeedParser.parse(EpisodeFixtures.resource("feed-snippet.xml"))
        assertEquals("Movie Roulette Tuesday: The Podcast", feed.title)
        assertEquals("https://storage.buzzsprout.com/0ivqancdb8l9ww2mlieeag1x8bdr?.jpg", feed.artworkURL)
        val first = feed.episodes[0]
        assertEquals("https://storage.buzzsprout.com/owoiwvlyear05oze07ippg1m06yb?.jpg", first.imageURL)
        assertEquals("https://www.buzzsprout.com/2117292/fan_mail/new", first.fanMailURL)
        assertTrue(first.summary.startsWith("Hi everyone"))
        assertFalse(first.summary.contains('\u00A0'))
        assertTrue(first.summary.contains("famous\u201D. It\u2019s"))
    }

    private fun androidRejectsUnicodeCharacterClass(): Boolean = try {
        Pattern.compile("a", Pattern.UNICODE_CHARACTER_CLASS)
        false
    } catch (e: IllegalArgumentException) {
        assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("UNICODE_CHARACTER_CLASS"))
        true
    }
}
