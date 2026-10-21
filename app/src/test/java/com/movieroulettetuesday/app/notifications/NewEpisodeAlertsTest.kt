package com.movieroulettetuesday.app.notifications

import com.movieroulettetuesday.app.data.InMemoryKeyValueStore
import com.movieroulettetuesday.app.episodes.PodcastEpisode
import com.movieroulettetuesday.app.settings.AppPreferences
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NewEpisodeAlertsTest {
    private fun ep(guid: String, date: Long?) = PodcastEpisode(guid = guid, title = "Ep $guid", pubDate = date)

    @Test
    fun diffMatchesIos() {
        val feed = listOf(ep("a", 100), ep("b", 200), ep("c", null))
        val mark = NewEpisodeDiff.watermark(feed)
        assertEquals(200L, mark.latestDate)
        assertEquals(listOf("b", "a", "c"), mark.seenGUIDs)
        assertEquals(emptyList<PodcastEpisode>(), NewEpisodeDiff.newEpisodes(feed, null)) // first run: baseline only
        val later = feed + ep("d", 300) + ep("e", 400) + ep("old", 50) + ep("undated", null)
        assertEquals(listOf("e", "d"), NewEpisodeDiff.newEpisodes(later, mark).map { it.guid })
        // No date in the mark: anything unseen is new.
        assertEquals(listOf("x"), NewEpisodeDiff.newEpisodes(listOf(ep("x", 1), ep("a", 2)), NewEpisodeWatermark(null, listOf("a"))).map { it.guid })
    }

    @Test
    fun notificationText() {
        assertNull(NewEpisodeDiff.notificationText(emptyList()))
        assertEquals(NewEpisodeDiff.Text("New episode of Movie Roulette Tuesday", "Ep d"), NewEpisodeDiff.notificationText(listOf(ep("d", 1))))
        assertEquals(NewEpisodeDiff.Text("2 new episodes of Movie Roulette Tuesday", "Latest: Ep e"),
            NewEpisodeDiff.notificationText(listOf(ep("e", 2), ep("d", 1))))
    }

    @Test
    fun markSeenMergesAndCaps() {
        val store = NewEpisodeWatermarkStore(InMemoryKeyValueStore())
        store.markSeen((1..40L).map { ep("old$it", it) })
        store.markSeen(listOf(ep("new", 30))) // older date than stored: keeps 40
        val mark = store.load()!!
        assertEquals(40L, mark.latestDate)
        assertEquals("new", mark.seenGUIDs.first())
        assertEquals(41, mark.seenGUIDs.size)
        store.markSeen((1..60L).map { ep("more$it", 100 + it) })
        assertEquals(NewEpisodeWatermark.GUID_LIMIT, store.load()!!.seenGUIDs.size)
        store.reset()
        assertNull(store.load())
    }

    @Test
    fun checkerBaselinesThenNotifiesNewest() = runTest {
        val kv = InMemoryKeyValueStore()
        val prefs = AppPreferences(kv).apply { setNewEpisodeAlerts(true) }
        val marks = NewEpisodeWatermarkStore(kv)
        var feed = listOf(ep("a", 100))
        val posted = mutableListOf<Pair<NewEpisodeDiff.Text, String>>()
        val checker = NewEpisodeChecker(prefs, marks, { feed }, { t, g -> posted += t to g })
        assertTrue(checker.checkNow())
        assertTrue(posted.isEmpty())
        feed = feed + ep("b", 200)
        assertTrue(checker.checkNow())
        assertEquals("b", posted.single().second)
        assertTrue(checker.checkNow()) // already seen
        assertEquals(1, posted.size)
        assertFalse(NewEpisodeChecker(prefs, marks, { error("offline") }, { _, _ -> }).checkNow())
        prefs.setNewEpisodeAlerts(false)
        feed = feed + ep("c", 300)
        assertTrue(checker.checkNow())
        assertEquals(1, posted.size)
    }

    @Test
    fun toggleDeniedAndGranted() {
        val kv = InMemoryKeyValueStore()
        val prefs = AppPreferences(kv)
        val marks = NewEpisodeWatermarkStore(kv)
        var scheduled = 0
        assertEquals(NewEpisodeAlertToggle.DENIED_NOTE, NewEpisodeAlertToggle.onPermissionResult(false, emptyList(), prefs, marks) { scheduled++ })
        assertFalse(prefs.newEpisodeAlerts.value)
        assertEquals(0, scheduled)
        marks.save(NewEpisodeWatermark(5, listOf("stale")))
        assertNull(NewEpisodeAlertToggle.onPermissionResult(true, emptyList(), prefs, marks) { scheduled++ })
        assertTrue(prefs.newEpisodeAlerts.value)
        assertNull(marks.load()) // nothing known: next check sets the baseline
        NewEpisodeAlertToggle.onPermissionResult(true, listOf(ep("a", 1)), prefs, marks) { scheduled++ }
        assertEquals(listOf("a"), marks.load()!!.seenGUIDs)
        assertEquals(2, scheduled)
    }
}
