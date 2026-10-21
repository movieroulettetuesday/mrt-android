package com.movieroulettetuesday.app.episodes

import java.time.LocalDate
import java.time.ZoneOffset

object EpisodeFixtures {
    fun resource(name: String): ByteArray =
        requireNotNull(EpisodeFixtures::class.java.classLoader!!.getResourceAsStream("episodes/$name")) { name }.use { it.readBytes() }

    fun date(y: Int, m: Int, d: Int = 15): Long = LocalDate.of(y, m, d).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()

    fun rss(vararg items: String, title: String = "Feed"): String =
        """<?xml version="1.0"?><rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd"><channel><title>$title</title>""" +
            items.joinToString("") + "</channel></rss>"

    fun item(guid: String, title: String, pubDate: String? = null, season: Int? = null, episode: Int? = null): String =
        "<item><title>$title</title><guid>$guid</guid>" +
            (pubDate?.let { "<pubDate>$it</pubDate>" } ?: "") +
            (season?.let { "<itunes:season>$it</itunes:season>" } ?: "") +
            (episode?.let { "<itunes:episode>$it</itunes:episode>" } ?: "") + "</item>"
}
