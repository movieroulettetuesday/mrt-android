package com.movieroulettetuesday.app.settings

import com.movieroulettetuesday.app.data.KeyValueStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Port of `AppearancePreference` (same stored raw values). */
enum class AppearancePreference(val raw: String, val label: String) {
    System("system", "System"), Light("light", "Light"), Dark("dark", "Dark");

    /** null = follow the system. */
    val forcesDark: Boolean? get() = when (this) { System -> null; Light -> false; Dark -> true }

    companion object {
        const val STORAGE_KEY = "settings.appearance"
        fun fromStored(raw: String?): AppearancePreference = entries.firstOrNull { it.raw == raw } ?: System
    }
}

/** App settings (iOS `AppSettings` + @AppStorage), same keys where they exist on iOS. */
class AppPreferences(private val store: KeyValueStore) {
    private val _appearance = MutableStateFlow(AppearancePreference.fromStored(store.getString(AppearancePreference.STORAGE_KEY)))
    val appearance: StateFlow<AppearancePreference> = _appearance.asStateFlow()

    private val _haptics = MutableStateFlow(store.getBoolean(HAPTICS, true))
    val haptics: StateFlow<Boolean> = _haptics.asStateFlow()

    private val _newEpisodeAlerts = MutableStateFlow(store.getBoolean(NEW_EPISODE_ALERTS, false))
    val newEpisodeAlerts: StateFlow<Boolean> = _newEpisodeAlerts.asStateFlow()

    private val _autoShareRatings = MutableStateFlow(store.getBoolean(AUTO_SHARE_RATINGS, false))
    val autoShareRatings: StateFlow<Boolean> = _autoShareRatings.asStateFlow()

    fun setAppearance(value: AppearancePreference) { store.putString(AppearancePreference.STORAGE_KEY, value.raw); _appearance.value = value }
    fun setHaptics(on: Boolean) { store.putBoolean(HAPTICS, on); _haptics.value = on }
    fun setNewEpisodeAlerts(on: Boolean) { store.putBoolean(NEW_EPISODE_ALERTS, on); _newEpisodeAlerts.value = on }
    fun setAutoShareRatings(on: Boolean) { store.putBoolean(AUTO_SHARE_RATINGS, on); _autoShareRatings.value = on }

    companion object {
        const val HAPTICS = "settings.haptics"
        const val NEW_EPISODE_ALERTS = "settings.newEpisodeAlerts"
        const val AUTO_SHARE_RATINGS = "settings.autoShareRatings"
    }
}

object PrivacyCopy {
    private const val BASE = "This app collects no personal data. Your ratings, watchlist, and spin history stay on your device."
    private const val FETCHES = "Listen links, movie list updates, the optional new-episode check, and Apple’s episode lookup only fetch the show’s public pages, feed, and movie list; nothing about you is sent."
    private const val EMAIL = "If you suggest a movie by email, you send it yourself from your mail app."
    private const val COMMUNITY = "Poll votes, listener ratings, and movie suggestions you choose to send are stored anonymously in the show’s iCloud database through the show’s relay server, with a random ID created by this app instead of a name or account; a suggestion contains only what you type, such as an optional credit name."

    /** Android wording (see PRIVACY_NOTES.md); the community sentence appears only when voting is enabled in the build. */
    fun note(communityEnabled: Boolean): String =
        listOfNotNull(BASE, COMMUNITY.takeIf { communityEnabled }, FETCHES, EMAIL).joinToString(" ")
}
