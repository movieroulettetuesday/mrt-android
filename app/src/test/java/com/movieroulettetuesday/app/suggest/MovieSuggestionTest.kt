package com.movieroulettetuesday.app.suggest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MovieSuggestionTest {
    @Test
    fun emailBodyMatchesIos() {
        val m = MovieSuggestion("  Tremors (1990) ", "Graboids!", "Horror")
        assertEquals("Movie suggestion: Tremors (1990)", m.subject)
        assertEquals(
            "Hi MRT Crew,\n\nI’d love to hear you cover:\n\nMovie: Tremors (1990)\nWhy: Graboids!\nCategory: Horror\n\n" +
                "Thanks for the show!\n\nSent from the Movie Roulette Tuesday app",
            m.body,
        )
        assertFalse(MovieSuggestion("   ").isValid)
    }

    @Test
    fun mailtoMatchesIosEncodingAndEncodesPlus() {
        val uri = MovieSuggestion("C++ & Me").mailtoUri
        assertTrue(uri.startsWith("mailto:theMrtPodcast@gmail.com?subject=Movie%20suggestion:%20C%2B%2B%20%26%20Me&body="))
        assertFalse(uri.contains("+"))
        assertFalse(uri.contains(" "))
    }

    @Test
    fun inAppAsEmailCarriesCreditName() {
        val s = InAppSuggestion("Tremors", "Horror", "Worms", "Laura from Ohio")
        assertTrue(s.asEmail.body.contains("Why: Worms\nFrom: Laura from Ohio\nCategory: Horror"))
        // No note: "From:" stands alone, never iOS's "Why: From: …" (answers-from-ios.md E17).
        val noNote = InAppSuggestion("Tremors", null, "  ", "Laura").asEmail.body
        assertTrue(noNote, noNote.contains("Movie: Tremors\nFrom: Laura\n"))
        assertFalse(noNote, noNote.contains("Why:"))
        assertEquals("Laura", InAppSuggestion("Tremors", creditName = " Laura ").asEmail.credit)
        assertEquals("", InAppSuggestion("Tremors").asEmail.why)
        assertEquals("Horror", s.asEmail.category)
    }

    @Test
    fun inAppLimits() {
        assertEquals(listOf(InAppSuggestion.Problem.MissingTitle), InAppSuggestion(" ").problems)
        val long = InAppSuggestion("x".repeat(121), note = "n".repeat(281), creditName = "c".repeat(61))
        assertEquals(
            listOf(InAppSuggestion.Problem.TitleTooLong, InAppSuggestion.Problem.NoteTooLong, InAppSuggestion.Problem.NameTooLong),
            long.problems,
        )
        assertEquals(120, InAppSuggestion.capped("x".repeat(200), 120).length)
        assertEquals(270, InAppSuggestion("t", note = "0123456789").noteCharactersLeft)
    }

    @Test
    fun rateLimitTenMinutes() {
        assertNull(SuggestionRateLimit().secondsRemaining(1_000))
        val l = SuggestionRateLimit(lastSent = 0)
        assertEquals(600, l.secondsRemaining(0))
        assertEquals(1, l.secondsRemaining(599_999))
        assertNull(l.secondsRemaining(600_000))
        assertNull(l.secondsRemaining(-5)) // clock moved back: don't lock the user out
        assertEquals("You just sent one. Try again in 10 min.", SuggestionRateLimit.message(600))
        assertEquals("You just sent one. Try again in 1 min.", SuggestionRateLimit.message(5))
    }
}
