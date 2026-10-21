package com.movieroulettetuesday.app.widget

import com.movieroulettetuesday.app.episodes.PodcastEpisode
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What the app hands the widget (iOS `WidgetSnapshot` in the App Group): the latest episode and the
 * last spin. Android keeps it in the app's own files dir (the widget runs in the app process).
 */
@Serializable
data class WidgetSnapshot(
    val latestEpisode: EpisodeInfo? = null,
    val lastSpin: SpinInfo? = null,
    val updatedAt: Long = 0,
) {
    @Serializable
    data class EpisodeInfo(val guid: String, val title: String, val season: Int? = null, val episode: Int? = null, val pubDate: Long? = null) {
        val seasonEpisodeLabel: String? get() = if (season != null && episode != null) "S${season}E$episode" else null

        companion object {
            fun of(e: PodcastEpisode) = EpisodeInfo(e.guid, e.title, e.season, e.episode, e.pubDate)
        }
    }

    @Serializable
    data class SpinInfo(val movieId: String, val title: String, val year: Int, val categoryName: String, val colorHex: String, val timestamp: Long)

    companion object {
        /** Newest by publish date; feed order breaks ties (first wins). */
        fun latest(episodes: List<PodcastEpisode>): PodcastEpisode? {
            var best: PodcastEpisode? = null
            for (e in episodes) {
                val b = best
                if (b == null || (e.pubDate ?: Long.MIN_VALUE) > (b.pubDate ?: Long.MIN_VALUE)) best = e
            }
            return best
        }
    }
}

/** `filesDir/widget-snapshot.json`; best-effort, never throws into the app. */
class WidgetSnapshotStore(val file: File) {
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): WidgetSnapshot? = runCatching { json.decodeFromString<WidgetSnapshot>(file.readText()) }.getOrNull()

    /** Applies [mutate] and saves; false (no write) when only `updatedAt` would change. */
    @Synchronized
    fun update(now: Long = System.currentTimeMillis(), mutate: (WidgetSnapshot) -> WidgetSnapshot): Boolean {
        val existing = load()
        val next = mutate(existing ?: WidgetSnapshot(updatedAt = now))
        if (existing != null && existing.latestEpisode == next.latestEpisode && existing.lastSpin == next.lastSpin) return false
        return runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(json.encodeToString(WidgetSnapshot.serializer(), next.copy(updatedAt = now)))
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
            true
        }.getOrDefault(false)
    }

    companion object {
        const val FILE_NAME = "widget-snapshot.json"
    }
}
