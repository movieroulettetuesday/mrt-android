package com.movieroulettetuesday.app.episodes

import com.movieroulettetuesday.app.catalog.Movie
import com.movieroulettetuesday.app.catalog.MovieCatalog
import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.Serializable

/** One RSS item. Port of `PodcastEpisode` (Models.swift). Dates are epoch milliseconds. */
@Serializable
data class PodcastEpisode(
    val guid: String,
    val title: String,
    val pubDate: Long? = null,
    val link: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeType: String? = null,
    val summary: String = "",
    val imageURL: String? = null,
    val fanMailURL: String? = null,
) {
    /** Show notes without Buzzsprout's leading "Send us Fan Mail" line. */
    val displaySummary: String get() = ShowNotes.strippingFanMail(summary)

    /** "S4E34" when season and episode are known. */
    val seasonEpisodeLabel: String?
        get() = if (season != null && episode != null) "S${season}E$episode" else null

    companion object {
        /** Drops repeated guids, keeping the first. */
        fun uniqued(episodes: List<PodcastEpisode>): List<PodcastEpisode> {
            val seen = HashSet<String>()
            return episodes.filter { seen.add(it.guid) }
        }

        fun byGUID(episodes: List<PodcastEpisode>): Map<String, PodcastEpisode> {
            val out = LinkedHashMap<String, PodcastEpisode>()
            episodes.forEach { out.putIfAbsent(it.guid, it) }
            return out
        }
    }
}

data class PodcastFeed(
    val title: String = "",
    val episodes: List<PodcastEpisode> = emptyList(),
    val artworkURL: String? = null,
)

/** On-disk feed cache (iOS `CachedEpisodeFeed`). */
@Serializable
data class CachedEpisodeFeed(
    val fetchedAt: Long,
    val title: String,
    val episodes: List<PodcastEpisode>,
    val artworkURL: String? = null,
)

object ShowNotes {
    // UNICODE_CHARACTER_CLASS: `\s` covers U+00A0 etc., like NSRegularExpression on iOS.
    private val fanMailPrefix = java.util.regex.Pattern.compile(
        """^send us fan ?mail[\s.:!\-–—|]*""",
        java.util.regex.Pattern.CASE_INSENSITIVE or java.util.regex.Pattern.UNICODE_CASE or java.util.regex.Pattern.UNICODE_CHARACTER_CLASS,
    ).toRegex()
    private val anchor = Regex("""<a\b[^>]*href\s*=\s*["']([^"']+)["'][^>]*>\s*([^<]*)</a>""", RegexOption.IGNORE_CASE)
    private val fanMailLabel = Regex("""fan ?mail""", RegexOption.IGNORE_CASE)

    fun strippingFanMail(text: String): String {
        val trimmed = text.trim()
        val m = fanMailPrefix.find(trimmed) ?: return trimmed
        return trimmed.substring(m.range.last + 1).trim()
    }

    /** The "Send us Fan Mail" href in show-notes HTML (https only on Android), else null. */
    fun fanMailURL(html: String): String? {
        for (m in anchor.findAll(html)) {
            val href = m.groupValues[1].replace("&amp;", "&")
            val label = m.groupValues[2]
            val isFanMail = href.lowercase().contains("fan_mail") || fanMailLabel.containsMatchIn(label)
            if (!isFanMail) continue
            ArtworkResolver.usableURL(href)?.let { return it }
        }
        return null
    }

    private val tag = Regex("<[^>]+>")
    private val spaces = java.util.regex.Pattern.compile("""\s+""", java.util.regex.Pattern.UNICODE_CHARACTER_CLASS).toRegex()
    private val entities = listOf(
        "&amp;" to "&", "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"",
        "&#39;" to "'", "&apos;" to "'", "&nbsp;" to " ",
    )

    /** Cheap unescape and tag strip, like iOS `stripHTML`. */
    fun stripHTML(html: String): String {
        var s = tag.replace(html, " ")
        for ((a, b) in entities) s = s.replace(a, b)
        return spaces.replace(s, " ").trim()
    }
}

/**
 * Port of `ArtworkResolver`, identical to iOS: http and https URLs are kept as written (same
 * candidate lists and cache keys on both platforms), anything else is dropped. The https upgrade
 * Android needs (it never loads cleartext) happens in the image loader, see [ArtworkLoader.transportURLs].
 */
object ArtworkResolver {
    fun usableURL(raw: String?): String? {
        val t = raw?.trim().orEmpty()
        if (t.isEmpty()) return null
        val uri = runCatching { java.net.URI(t) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (uri.host.isNullOrEmpty()) return null
        return when (scheme) {
            "https", "http" -> t
            else -> null
        }
    }

    /** The movie's earliest-published episode that has artwork. */
    fun episodeArtwork(movie: Movie, episodesByGUID: Map<String, PodcastEpisode>): String? {
        var best: Triple<String, Long, Int>? = null
        movie.episodeIds.forEachIndexed { index, guid ->
            val ep = episodesByGUID[guid] ?: return@forEachIndexed
            val url = usableURL(ep.imageURL) ?: return@forEachIndexed
            val date = ep.pubDate ?: Long.MAX_VALUE
            val cur = best
            if (cur == null || date < cur.second || (date == cur.second && index < cur.third)) best = Triple(url, date, index)
        }
        return best?.first
    }

    fun candidates(movie: Movie, episodesByGUID: Map<String, PodcastEpisode>, showArtwork: String?): List<String> =
        listOfNotNull(episodeArtwork(movie, episodesByGUID), usableURL(movie.posterURL), usableURL(showArtwork)).distinct()

    fun candidates(episode: PodcastEpisode, showArtwork: String?): List<String> =
        listOfNotNull(usableURL(episode.imageURL), usableURL(showArtwork)).distinct()
}

object NewEpisodeBadge {
    fun count(episodes: List<PodcastEpisode>, lastViewed: Long?): Int {
        lastViewed ?: return 0
        return episodes.count { (it.pubDate ?: Long.MIN_VALUE) > lastViewed }
    }

    fun initialBaseline(episodes: List<PodcastEpisode>, now: Long): Long = episodes.mapNotNull { it.pubDate }.maxOrNull() ?: now

    fun viewedTimestamp(episodes: List<PodcastEpisode>, now: Long): Long = maxOf(now, episodes.mapNotNull { it.pubDate }.maxOrNull() ?: now)
}

enum class EpisodeGrouping(val label: String) {
    Season("Season"), Year("Year"), All("All");

    val key: String get() = name.lowercase()

    companion object {
        fun fromKey(key: String?): EpisodeGrouping = entries.firstOrNull { it.key == key } ?: Season
    }
}

data class EpisodeGroup(val id: String, val title: String, val episodes: List<PodcastEpisode>)

/** Newest group first; no season -> "Bonus" (last); no date -> "Undated" (last). */
object EpisodeGrouper {
    const val BONUS_ID = "bonus"

    fun groups(episodes: List<PodcastEpisode>, by: EpisodeGrouping, zone: ZoneId = ZoneId.systemDefault()): List<EpisodeGroup> =
        when (by) {
            EpisodeGrouping.All -> if (episodes.isEmpty()) emptyList() else listOf(EpisodeGroup("all", "All Episodes", episodes))
            EpisodeGrouping.Season -> {
                val bySeason = LinkedHashMap<Int, MutableList<PodcastEpisode>>()
                val bonus = mutableListOf<PodcastEpisode>()
                episodes.forEach { ep -> ep.season?.let { bySeason.getOrPut(it) { mutableListOf() }.add(ep) } ?: bonus.add(ep) }
                bySeason.keys.sortedDescending().map { EpisodeGroup("season-$it", "Season $it", bySeason.getValue(it)) } +
                    if (bonus.isEmpty()) emptyList() else listOf(EpisodeGroup(BONUS_ID, "Bonus", bonus))
            }
            EpisodeGrouping.Year -> {
                val byYear = LinkedHashMap<Int, MutableList<PodcastEpisode>>()
                val undated = mutableListOf<PodcastEpisode>()
                episodes.forEach { ep ->
                    val d = ep.pubDate
                    if (d != null) byYear.getOrPut(Instant.ofEpochMilli(d).atZone(zone).year) { mutableListOf() }.add(ep) else undated.add(ep)
                }
                byYear.keys.sortedDescending().map { EpisodeGroup("year-$it", it.toString(), byYear.getValue(it)) } +
                    if (undated.isEmpty()) emptyList() else listOf(EpisodeGroup("undated", "Undated", undated))
            }
        }
}

/** Remembered open/closed headers serialized as "season-4:1,season-3:0" (same format as iOS). */
data class EpisodeGroupExpansion(val states: Map<String, Boolean> = emptyMap()) {
    val serialized: String get() = states.keys.sorted().joinToString(",") { "$it:${if (states.getValue(it)) 1 else 0}" }

    fun isExpanded(id: String, isNewest: Boolean): Boolean = states[id] ?: isNewest

    fun toggled(id: String, isNewest: Boolean): EpisodeGroupExpansion = copy(states = states + (id to !isExpanded(id, isNewest)))

    companion object {
        fun parse(serialized: String): EpisodeGroupExpansion {
            val out = HashMap<String, Boolean>()
            for (part in serialized.split(",")) {
                val kv = part.split(":")
                if (kv.size != 2 || kv[0].isEmpty()) continue
                out[kv[0]] = kv[1] == "1"
            }
            return EpisodeGroupExpansion(out)
        }
    }
}

data class EpisodeTotals(val feedEpisodes: Int, val feedEpisodesWithFilm: Int) {
    companion object {
        fun of(episodes: List<PodcastEpisode>, catalog: MovieCatalog?): EpisodeTotals =
            EpisodeTotals(episodes.size, catalog?.let { c -> episodes.count { c.movieForEpisodeGuid(it.guid) != null } } ?: 0)
    }
}
