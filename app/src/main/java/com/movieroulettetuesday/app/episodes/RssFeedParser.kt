package com.movieroulettetuesday.app.episodes

import android.util.Xml
import java.io.ByteArrayInputStream
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException

class RssFeedException(message: String) : Exception(message) {
    companion object {
        fun empty() = RssFeedException("The feed was empty.")
        fun malformed() = RssFeedException("The feed could not be parsed.")
    }
}

/**
 * Minimal RSS 2.0 + iTunes parser, a port of `RSSFeedParser.swift`: same fields, same "first
 * wins" / "fullest notes" rules, same guid fallback to link. Element names are matched by their
 * lower-cased local name ("itunes:season" -> "season").
 */
object RssFeedParser {
    fun parse(data: ByteArray): PodcastFeed {
        if (data.isEmpty()) throw RssFeedException.empty()
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        try {
            parser.setInput(ByteArrayInputStream(data), null)
            return Delegate().run(parser)
        } catch (e: XmlPullParserException) {
            throw RssFeedException.malformed()
        }
    }

    fun parse(text: String): PodcastFeed = parse(text.toByteArray())

    private val patterns = listOf(
        "EEE, dd MMM yyyy HH:mm:ss Z",
        "EEE, dd MMM yyyy HH:mm:ss zzz",
        "dd MMM yyyy HH:mm:ss Z",
        "dd MMM yyyy HH:mm:ss zzz",
        "EEE, dd MMM yyyy HH:mm Z",
        "EEE, dd MMM yyyy HH:mm zzz",
    )

    /** RFC 822 pubDate with the common variants; unparseable -> null. Epoch ms. */
    fun parseDate(raw: String): Long? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        for (p in patterns) {
            val f = SimpleDateFormat(p, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC"); isLenient = false }
            val pos = ParsePosition(0)
            val d = f.parse(t, pos)
            if (d != null && pos.index == t.length) return d.time
        }
        return null
    }

    fun fullestNotes(candidates: List<String>): String = candidates.maxByOrNull { it.length } ?: ""

    private fun localName(name: String): String = name.lowercase().substringAfterLast(':')

    private class Delegate {
        var channelTitle = ""
        var channelImage = ""
        var channelImageFallback = ""
        val episodes = mutableListOf<PodcastEpisode>()
        val path = ArrayList<String>()
        val text = StringBuilder()
        var inItem = false
        var title = ""; var guid = ""; var link = ""; var pubDate = ""; var description = ""
        var summary = ""; var season = ""; var episode = ""; var episodeType = ""; var image = ""; var html = ""

        fun run(p: XmlPullParser): PodcastFeed {
            var event = p.eventType
            var sawRoot = false
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> { sawRoot = true; start(p) }
                    XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF -> text.append(p.text ?: "")
                    XmlPullParser.END_TAG -> end(localName(p.name))
                }
                event = p.nextToken()
            }
            // Truncated documents (unclosed elements) are malformed, like XMLParser on iOS.
            if (!sawRoot || path.isNotEmpty()) throw RssFeedException.malformed()
            return PodcastFeed(
                title = channelTitle,
                episodes = PodcastEpisode.uniqued(episodes),
                artworkURL = ArtworkResolver.usableURL(channelImage) ?: ArtworkResolver.usableURL(channelImageFallback),
            )
        }

        private fun start(p: XmlPullParser) {
            val name = localName(p.name)
            path.add(name)
            text.setLength(0)
            if (name == "item") {
                inItem = true
                title = ""; guid = ""; link = ""; pubDate = ""; description = ""; season = ""; episode = ""
                episodeType = ""; image = ""; html = ""; summary = ""
            }
            if (name == "image") {
                val href = p.getAttributeValue(null, "href") ?: p.getAttributeValue(null, "HREF")
                if (href != null) {
                    val v = href.trim()
                    if (inItem) {
                        if (image.isEmpty()) image = v
                    } else if ("channel" in path && channelImage.isEmpty()) {
                        channelImage = v
                    }
                }
            }
        }

        private fun end(name: String) {
            val value = text.toString().trim()
            if (inItem) {
                when (name) {
                    "title" -> title = value
                    "guid" -> guid = value
                    "link" -> link = value
                    "pubdate" -> pubDate = value
                    "summary" -> if (value.isNotEmpty()) summary = value
                    "description" -> {
                        if (description.isEmpty()) description = value
                        if (html.isEmpty()) html = value
                    }
                    "encoded" -> if (html.isEmpty()) html = value
                    "season" -> season = value
                    "episode" -> if (value.toIntOrNull() != null) episode = value
                    "episodetype" -> episodeType = value
                    "item" -> {
                        val id = guid.ifEmpty { link }
                        if (id.isNotEmpty() && title.isNotEmpty()) {
                            episodes += PodcastEpisode(
                                guid = id,
                                title = title,
                                pubDate = parseDate(pubDate),
                                link = link.ifEmpty { null },
                                season = season.toIntOrNull(),
                                episode = episode.toIntOrNull(),
                                episodeType = episodeType.ifEmpty { null },
                                summary = fullestNotes(
                                    listOf(description, html, summary).map { ShowNotes.strippingFanMail(ShowNotes.stripHTML(it)) },
                                ),
                                imageURL = ArtworkResolver.usableURL(image),
                                fanMailURL = ShowNotes.fanMailURL(html),
                            )
                        }
                        inItem = false
                    }
                }
            } else if (name == "title" && "channel" in path && "item" !in path && "image" !in path) {
                if (channelTitle.isEmpty()) channelTitle = value
            } else if (name == "url" && "channel" in path && path.size >= 2 && path[path.size - 2] == "image") {
                if (channelImageFallback.isEmpty()) channelImageFallback = value
            }
            if (path.isNotEmpty()) path.removeAt(path.size - 1)
            text.setLength(0)
        }
    }
}
