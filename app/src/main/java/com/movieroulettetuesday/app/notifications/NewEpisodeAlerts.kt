package com.movieroulettetuesday.app.notifications

import com.movieroulettetuesday.app.data.KeyValueStore
import com.movieroulettetuesday.app.episodes.PodcastEpisode
import com.movieroulettetuesday.app.settings.AppPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What the background check remembers between runs (iOS `NewEpisodeWatermark`; dates as epoch ms). */
@Serializable
data class NewEpisodeWatermark(val latestDate: Long? = null, val seenGUIDs: List<String> = emptyList()) {
    companion object { const val GUID_LIMIT = 50 }
}

/** Port of iOS `NewEpisodeDiff`. */
object NewEpisodeDiff {
    private val newestFirst = compareByDescending<PodcastEpisode> { it.pubDate ?: Long.MIN_VALUE }

    fun watermark(episodes: List<PodcastEpisode>): NewEpisodeWatermark {
        val sorted = episodes.sortedWith(newestFirst)
        return NewEpisodeWatermark(sorted.firstNotNullOfOrNull { it.pubDate }, sorted.take(NewEpisodeWatermark.GUID_LIMIT).map { it.guid })
    }

    /** Episodes after the watermark it hasn't seen, newest first. No watermark yet → nothing is new. */
    fun newEpisodes(episodes: List<PodcastEpisode>, mark: NewEpisodeWatermark?): List<PodcastEpisode> {
        mark ?: return emptyList()
        val seen = mark.seenGUIDs.toSet()
        return episodes.filter { ep ->
            when {
                ep.guid in seen -> false
                mark.latestDate == null -> true
                ep.pubDate == null -> false
                else -> ep.pubDate > mark.latestDate
            }
        }.sortedWith(newestFirst)
    }

    data class Text(val title: String, val body: String)

    fun notificationText(new: List<PodcastEpisode>): Text? {
        val first = new.firstOrNull() ?: return null
        return if (new.size == 1) Text("New episode of Movie Roulette Tuesday", first.title)
        else Text("${new.size} new episodes of Movie Roulette Tuesday", "Latest: ${first.title}")
    }
}

/** Watermark persistence (iOS `NewEpisodeAlerts` load/save/reset/markSeen), same keys. */
class NewEpisodeWatermarkStore(private val store: KeyValueStore) {
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): NewEpisodeWatermark? =
        store.getString(WATERMARK_KEY)?.let { runCatching { json.decodeFromString<NewEpisodeWatermark>(it) }.getOrNull() }

    fun save(mark: NewEpisodeWatermark) = store.putString(WATERMARK_KEY, json.encodeToString(mark))

    /** Forget the marker; the next check only sets a new baseline (no alert). */
    fun reset() = store.putString(WATERMARK_KEY, null)

    /** Episodes the user can already see in the app never alert later. */
    @Synchronized
    fun markSeen(episodes: List<PodcastEpisode>) {
        if (episodes.isEmpty()) return
        var mark = NewEpisodeDiff.watermark(episodes)
        load()?.let { old ->
            val oldDate = old.latestDate
            val newDate = mark.latestDate
            if (oldDate != null && newDate != null && oldDate > newDate) mark = mark.copy(latestDate = oldDate)
            mark = mark.copy(seenGUIDs = (mark.seenGUIDs + old.seenGUIDs.filter { it !in mark.seenGUIDs }).take(NewEpisodeWatermark.GUID_LIMIT))
        }
        save(mark)
    }

    companion object {
        const val WATERMARK_KEY = "newEpisodeAlerts.watermark"
    }
}

/** Shows one local notification. */
fun interface EpisodeNotifier {
    fun notify(text: NewEpisodeDiff.Text, guid: String)
}

/** iOS `NewEpisodeAlerts.checkNow`: fetch, diff, mark seen, notify about the newest. Returns success. */
class NewEpisodeChecker(
    private val prefs: AppPreferences,
    private val watermarks: NewEpisodeWatermarkStore,
    private val fetch: suspend () -> List<PodcastEpisode>,
    private val notifier: EpisodeNotifier,
) {
    suspend fun checkNow(): Boolean {
        if (!prefs.newEpisodeAlerts.value) return true
        val episodes = try { fetch() } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; return false }
        val mark = watermarks.load()
        val new = NewEpisodeDiff.newEpisodes(episodes, mark)
        if (mark == null || new.isNotEmpty()) watermarks.markSeen(episodes)
        val text = NewEpisodeDiff.notificationText(new)
        if (text != null) notifier.notify(text, new.first().guid)
        return true
    }
}

/** Settings toggle logic (iOS SettingsSheet), kept pure so it's testable without a permission dialog. */
object NewEpisodeAlertToggle {
    const val DENIED_NOTE = "Notifications are off for Movie Roulette Tuesday. Allow them in the Settings app (Notifications), then turn this on again."

    /** After the permission answer. Returns the note to show (null when on). */
    fun onPermissionResult(
        granted: Boolean,
        known: List<PodcastEpisode>,
        prefs: AppPreferences,
        watermarks: NewEpisodeWatermarkStore,
        schedule: () -> Unit,
    ): String? {
        if (!granted) {
            prefs.setNewEpisodeAlerts(false)
            return DENIED_NOTE
        }
        prefs.setNewEpisodeAlerts(true)
        if (known.isEmpty()) watermarks.reset()
        watermarks.markSeen(known)
        schedule()
        return null
    }

    fun turnOff(prefs: AppPreferences, cancel: () -> Unit) {
        prefs.setNewEpisodeAlerts(false)
        cancel()
    }
}
