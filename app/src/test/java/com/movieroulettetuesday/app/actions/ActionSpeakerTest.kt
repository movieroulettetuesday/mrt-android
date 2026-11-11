package com.movieroulettetuesday.app.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionSpeakerTest {
    private data class Sink(var spoken: String? = null, var notified: Pair<String, String>? = null)

    private fun speaker(talkBack: Boolean, tts: Boolean, sink: Sink) = ActionSpeaker(
        talkBackOn = { talkBack },
        ttsReady = { tts },
        speak = { sink.spoken = it },
        notify = { title, text -> sink.notified = title to text },
    )

    @Test
    fun speaksWhenTtsReadyAndTalkBackOff() {
        val sink = Sink()
        speaker(talkBack = false, tts = true, sink).deliver("Hello", screenVisible = true)
        assertEquals("Hello", sink.spoken)
        assertEquals(null, sink.notified)
    }

    @Test
    fun skipsTtsWhenTalkBackIsReadingTheScreen() {
        val sink = Sink()
        speaker(talkBack = true, tts = true, sink).deliver("Hello", screenVisible = true)
        assertEquals(null, sink.spoken)
        assertEquals(null, sink.notified)
    }

    @Test
    fun notifiesWhenTalkBackIsOnButNoScreenIsVisible() {
        val sink = Sink()
        speaker(talkBack = true, tts = true, sink).deliver("Empty cache", screenVisible = false)
        assertEquals(null, sink.spoken)
        assertEquals("Movie Roulette" to "Empty cache", sink.notified)
    }

    @Test
    fun notifiesWhenTtsIsMissing() {
        val sink = Sink()
        speaker(talkBack = false, tts = false, sink).deliver("Your watchlist is empty.", screenVisible = true)
        assertEquals(null, sink.spoken)
        assertEquals("Movie Roulette" to "Your watchlist is empty.", sink.notified)
    }

    @Test
    fun ignoresBlankText() {
        val sink = Sink()
        speaker(talkBack = false, tts = true, sink).deliver("  ", screenVisible = true)
        assertEquals(null, sink.spoken)
        assertEquals(null, sink.notified)
    }
}
