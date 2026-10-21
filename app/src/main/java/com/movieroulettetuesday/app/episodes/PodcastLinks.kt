package com.movieroulettetuesday.app.episodes

import com.movieroulettetuesday.app.catalog.ShowInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Port of `PodcastLinks` (EpisodeLabel.swift / ShowLinks.swift). */
object PodcastLinks {
    const val SPOTIFY_SHOW_NAME = "Movie Roulette Tuesday"

    fun appleShow(show: ShowInfo = ShowInfo.MRT): String? = show.applePodcastsURL
    fun spotifyShow(show: ShowInfo = ShowInfo.MRT): String? = show.spotifyURL

    fun overcastShow(show: ShowInfo = ShowInfo.MRT): String? =
        show.applePodcastID?.takeIf { it.isNotEmpty() }?.let { "https://overcast.fm/itunes$it" }

    fun buzzsproutShow(show: ShowInfo = ShowInfo.MRT): String? =
        show.buzzsproutShowID?.takeIf { it.isNotEmpty() }?.let { "https://www.buzzsprout.com/$it" }

    /** Buzzsprout episode page when the guid is `Buzzsprout-<numericId>`. */
    fun buzzsproutEpisode(guid: String, show: ShowInfo = ShowInfo.MRT): String? {
        val showId = show.buzzsproutShowID ?: return null
        if (!guid.startsWith("Buzzsprout-")) return null
        val numeric = guid.removePrefix("Buzzsprout-").toIntOrNull() ?: return null
        return "https://www.buzzsprout.com/$showId/episodes/$numeric"
    }

    /** `https://open.spotify.com/search/<query>`, RFC 3986 unreserved chars only. */
    fun spotifySearch(episodeTitle: String, show: ShowInfo = ShowInfo.MRT): String? {
        val q = spotifySearchQuery(episodeTitle)
        val encoded = percentEncodeUnreserved(q)
        return if (encoded.isEmpty()) show.spotifyURL else "https://open.spotify.com/search/$encoded"
    }

    fun spotifySearchQuery(episodeTitle: String): String {
        val title = cleanEpisodeTitle(episodeTitle)
        if (title.contains(SPOTIFY_SHOW_NAME, ignoreCase = true)) return title
        return if (title.isEmpty()) SPOTIFY_SHOW_NAME else "$title $SPOTIFY_SHOW_NAME"
    }

    private val rawGuid = Regex("""^Buzzsprout-\d+$""")
    private val numberPrefix = Regex(
        """^(?i)\s*(?:s(?:eason)?\s*\d+\s*[·•.:,|\-–—]?\s*)?(?:e(?:p(?:isode)?)?\.?\s*#?\s*\d+|#\s*\d+)\b\s*[·•.:|)\-–—]*\s*""",
    )

    fun cleanEpisodeTitle(raw: String): String {
        var t = raw.replace('\u2019', '\'').replace('\u2018', '\'').trim()
        if (rawGuid.matches(t)) return ""
        while (true) {
            val m = numberPrefix.find(t) ?: break
            if (m.value.isEmpty()) break
            t = t.removeRange(m.range)
        }
        val cps = t.codePoints().toArray()
        val out = StringBuilder()
        fun alnum(cp: Int) = Character.isLetter(cp) || Character.isDigit(cp)
        cps.forEachIndexed { i, c ->
            val prev = if (i > 0) cps[i - 1] else ' '.code
            val next = if (i + 1 < cps.size) cps[i + 1] else ' '.code
            when {
                alnum(c) -> out.appendCodePoint(c)
                (c == '\''.code || c == '-'.code) && alnum(prev) && alnum(next) -> out.appendCodePoint(c)
                else -> out.append(' ')
            }
        }
        return out.split(' ').filter { it.isNotEmpty() }.joinToString(" ")
    }

    fun percentEncodeUnreserved(s: String): String {
        val sb = StringBuilder()
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            val ch = c.toChar()
            if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '-' || ch == '.' || ch == '_' || ch == '~') {
                sb.append(ch)
            } else {
                sb.append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 0xF])
            }
        }
        return sb.toString()
    }
}

/** Port of `AppleEpisodeLookup`: public, keyless iTunes Lookup (only the show's Apple ID is sent). */
object AppleEpisodeLookup {
    fun lookupURL(show: ShowInfo = ShowInfo.MRT, limit: Int = 200): String? =
        show.applePodcastID?.takeIf { it.isNotEmpty() }
            ?.let { "https://itunes.apple.com/lookup?id=$it&entity=podcastEpisode&limit=$limit" }

    fun episodeURL(trackId: Long, show: ShowInfo = ShowInfo.MRT): String? {
        val id = show.applePodcastID?.takeIf { it.isNotEmpty() } ?: return null
        if (trackId <= 0) return null
        return "https://podcasts.apple.com/us/podcast/id$id?i=$trackId"
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** GUID -> Apple track id, only for this show's episodes. */
    fun parse(data: ByteArray, show: ShowInfo = ShowInfo.MRT): Map<String, Long> {
        val root = json.parseToJsonElement(data.decodeToString()).jsonObject
        val showId = show.applePodcastID?.toLongOrNull()
        val out = HashMap<String, Long>()
        for (el in root["results"]?.jsonArray ?: return out) {
            val o = runCatching { el.jsonObject }.getOrNull() ?: continue
            fun str(k: String) = runCatching { o[k]?.jsonPrimitive?.content }.getOrNull()
            fun num(k: String) = runCatching { o[k]?.jsonPrimitive?.longOrNull }.getOrNull()
            val isEpisode = str("wrapperType") == "podcastEpisode" || str("kind") == "podcast-episode"
            val guid = str("episodeGuid")?.trim().orEmpty()
            val track = num("trackId") ?: continue
            if (!isEpisode || guid.isEmpty() || track <= 0) continue
            val collection = num("collectionId")
            if (showId != null && collection != null && collection != showId) continue
            out[guid] = track
        }
        return out
    }

    data class Link(val url: String, val isExactEpisode: Boolean)

    fun link(guid: String, map: Map<String, Long>, show: ShowInfo = ShowInfo.MRT): Link? {
        map[guid]?.let { t -> episodeURL(t, show)?.let { return Link(it, true) } }
        return PodcastLinks.appleShow(show)?.let { Link(it, false) }
    }
}

/** One row of the Listen card. */
data class ListenLink(val id: String, val title: String, val detail: String, val url: String, val tileHex: String)

object ListenLinks {
    fun forEpisode(episode: PodcastEpisode, appleMap: Map<String, Long>, show: ShowInfo = ShowInfo.MRT): List<ListenLink> {
        val out = mutableListOf<ListenLink>()
        AppleEpisodeLookup.link(episode.guid, appleMap, show)?.let {
            out += ListenLink(
                "listen-apple", "Apple Podcasts",
                if (it.isExactEpisode) "This episode" else "Show page (episode not listed yet)", it.url, "#9B4DE0",
            )
        }
        (PodcastLinks.spotifySearch(episode.title, show) ?: PodcastLinks.spotifyShow(show))?.let {
            out += ListenLink("listen-spotify", "Spotify", "Search for this episode", it, "#1DB954")
        }
        PodcastLinks.overcastShow(show)?.let { out += ListenLink("listen-overcast", "Overcast", "Show page", it, "#FC7E0F") }
        (PodcastLinks.buzzsproutEpisode(episode.guid, show) ?: PodcastLinks.buzzsproutShow(show))?.let {
            out += ListenLink("listen-buzzsprout", "Buzzsprout", "Episode page", it, "#3A9CA6")
        }
        return out
    }
}
