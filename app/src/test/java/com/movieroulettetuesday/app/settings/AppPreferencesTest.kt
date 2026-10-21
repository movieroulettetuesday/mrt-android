package com.movieroulettetuesday.app.settings

import com.movieroulettetuesday.app.data.InMemoryKeyValueStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppPreferencesTest {
    @Test
    fun defaultsAndIosKeys() {
        val store = InMemoryKeyValueStore()
        val p = AppPreferences(store)
        assertEquals(AppearancePreference.System, p.appearance.value)
        assertTrue(p.haptics.value)
        assertFalse(p.newEpisodeAlerts.value)
        assertFalse(p.autoShareRatings.value)
        p.setAppearance(AppearancePreference.Dark)
        p.setHaptics(false)
        p.setNewEpisodeAlerts(true)
        p.setAutoShareRatings(true)
        assertEquals("dark", store.getString("settings.appearance"))
        assertEquals(false, store.getBoolean("settings.haptics", true))
        assertEquals(true, store.getBoolean("settings.newEpisodeAlerts", false))
        assertEquals(true, store.getBoolean("settings.autoShareRatings", false))
        val reloaded = AppPreferences(store)
        assertEquals(AppearancePreference.Dark, reloaded.appearance.value)
        assertFalse(reloaded.haptics.value)
    }

    @Test
    fun appearanceRawValues() {
        assertEquals(AppearancePreference.System, AppearancePreference.fromStored("bogus"))
        assertNull(AppearancePreference.System.forcesDark)
        assertEquals(false, AppearancePreference.Light.forcesDark)
        assertEquals(true, AppearancePreference.Dark.forcesDark)
    }

    @Test
    fun privacyNoteMentionsCommunityOnlyWhenEnabled() {
        assertFalse(PrivacyCopy.note(false).contains("relay"))
        assertTrue(PrivacyCopy.note(true).contains("random ID created by this app"))
        assertTrue(PrivacyCopy.note(false).startsWith("This app collects no personal data."))
    }

    @Test
    fun followTheShowMatchesIosOrderAndIsHttps() {
        val links = FollowTheShow.links()
        assertEquals(listOf("apple", "spotify", "overcast", "buzzsprout", "youtube", "instagram", "x", "facebook"), links.map { it.id })
        assertTrue(links.all { it.url.startsWith("https://") })
        assertEquals("https://x.com/TheMRTPodcast", links.first { it.id == "x" }.url)
    }
}
