package com.movieroulettetuesday.app.shared

import com.movieroulettetuesday.app.catalog.StableHash
import com.movieroulettetuesday.app.episodes.EpisodeGroupExpansion
import com.movieroulettetuesday.app.episodes.EpisodeGrouper
import com.movieroulettetuesday.app.episodes.EpisodeGrouping
import com.movieroulettetuesday.app.episodes.NewEpisodeBadge
import com.movieroulettetuesday.app.episodes.PodcastEpisode
import com.movieroulettetuesday.app.movies.HalfStars
import com.movieroulettetuesday.app.movies.PersonalRatings
import com.movieroulettetuesday.app.movies.PersonalRatingsSummary
import com.movieroulettetuesday.app.movies.WatchStatus
import com.movieroulettetuesday.app.notifications.NewEpisodeDiff
import com.movieroulettetuesday.app.roulette.SpinHistoryStore
import com.movieroulettetuesday.app.roulette.SpinResult
import com.movieroulettetuesday.app.stats.CountedId
import com.movieroulettetuesday.app.stats.StatsSnapshot
import com.movieroulettetuesday.app.suggest.InAppSuggestion
import com.movieroulettetuesday.app.suggest.MovieSuggestion
import com.movieroulettetuesday.app.suggest.SuggestionRateLimit
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/** stats.json: Stats snapshot, half stars, history compaction, suggestions, badges, alerts, hashing. */
class StatsVectorsTest {
    private val v = SharedVectors.load("stats.json")
    private fun ms(iso: String?) = iso?.let { Instant.parse(it).toEpochMilli() }
    private fun counts(o: JsonObject) = o.mapValues { num(it.value)!!.toInt() }

    /** Equal-count runs are compared as sets (iOS sorts a Dictionary; SPEC Q6). */
    private fun assertBreakdown(tag: String, expected: List<CountedId>, actual: List<CountedId>) {
        assertEquals("$tag counts", expected.map { it.count }, actual.map { it.count })
        expected.groupBy { it.count }.forEach { (n, group) -> assertEquals("$tag count $n", group.map { it.id }.toSet(), actual.filter { it.count == n }.map { it.id }.toSet()) }
    }

    @Test
    fun snapshots() {
        val catalog = SharedVectors.catalog(v["catalogForGenres"]!!)
        for (c in v.arr("snapshots").objs()) {
            val name = c.str("name")!!
            val a = c.obj("archive")
            val log = SpinHistoryStore.Log(
                results = c["history"]!!.objs().map { SpinResult(movieId = it.str("movieId")!!, categoryId = it.str("categoryId")!!, timestampEpochMs = ms(it.str("timestamp"))!!) },
                archivedTotal = a.int("totalSpins")!!,
                archivedMovieCounts = counts(a.obj("movieCounts")),
                archivedCategoryCounts = counts(a.obj("categoryCounts")),
            )
            val s = StatsSnapshot.of(log, catalog, recentLimit = 3) // as tools/vector-gen
            val e = c.obj("expected")
            assertEquals(name, e.int("totalSpins"), s.totalSpins)
            assertEquals(name, e.int("distinctMovies"), s.distinctMovies)
            fun counted(o: JsonObject?, key: String = "id") = o?.let { CountedId(it.str(key)!!, it.int("count")!!) }
            assertEquals(name, counted(e["mostSpunMovie"] as? JsonObject)?.count, s.mostSpunMovie?.count)
            assertEquals(name, counted(e["mostSpunCategory"] as? JsonObject)?.count, s.mostSpunCategory?.count)
            assertBreakdown("$name categories", e["categoryBreakdown"]!!.objs().map { counted(it)!! }, s.categoryBreakdown)
            assertBreakdown("$name genres", e["genreBreakdown"]!!.objs().map { counted(it, "genre")!! }, s.genreBreakdown)
            assertEquals(name, e["recentMovieIds"]!!.strings(), s.recent.map { it.movieId })
        }
    }

    @Test
    fun halfStars() {
        val h = v.obj("halfStars")
        for (c in h.arr("adjusted").objs()) assertEquals("$c", c.int("expected"), HalfStars.adjusted(c.int("halfStars"), c.bool("increment")!!))
        for (c in h.arr("clamped").objs()) assertEquals("$c", c.int("expected"), HalfStars.clamped(c.int("input")))
        for (c in h.arr("fromLocation").objs()) assertEquals("$c", c.int("expected"), HalfStars.fromLocation(c.dbl("x")!!, c.dbl("width")!!))
        for (c in h.arr("label").objs()) assertEquals("$c", c.str("expected"), HalfStars.label(c.int("halfStars")!!))
        for (c in h.arr("resolved").objs()) assertEquals("$c", c.int("expected"), HalfStars.resolved(c.int("halfStars"), c.int("legacyStars")))
        for (c in h.arr("roundedStars").objs()) assertEquals("$c", c.int("expected"), HalfStars.roundedStars(c.int("halfStars")!!))
        for (c in h.arr("roundedToHalf").objs()) assertClose("$c", c.dbl("expected")!!, HalfStars.roundedToHalf(c.dbl("stars")!!))
    }

    @Test
    fun personalRatingsSummary() {
        val p = v.obj("personalRatingsSummary")
        var r = PersonalRatings()
        for ((id, s) in p.obj("input").obj("status")) r = r.withStatus(WatchStatus.fromRaw(s.jsonPrimitive.content), id, 0)
        for ((id, n) in p.obj("input").obj("halfStars")) r = r.withHalfStars(num(n)!!.toInt(), id, 0)
        val got = PersonalRatingsSummary.of(r, SharedVectors.catalog(kotlinx.serialization.json.JsonPrimitive("fixture")), topLimit = 3)
        val e = p.obj("expected")
        assertEquals(e.int("ratedCount"), got.ratedCount)
        assertClose("avg", e.dbl("averageStars")!!, got.averageStars!!)
        assertEquals(e["topRated"]!!.objs().map { Triple(it.str("movieId"), it.str("title"), it.int("halfStars")) }, got.topRated.map { Triple(it.movieId, it.title, it.halfStars) })
        assertEquals(e.int("watchlistCount"), got.watchlistCount)
        assertEquals(e.int("watchedCount"), got.watchedCount)
    }

    @Test
    fun historyCompaction() {
        val c = v.obj("historyCompaction")
        val store = SpinHistoryStore(null, detailLimit = c.int("detailLimit")!!)
        c["appendedMovieIds"]!!.strings().forEach { store.record(SpinResult(movieId = it, categoryId = "a")) }
        val e = c.obj("expected")
        val log = store.log.value
        assertEquals(e["detailedMovieIds"]!!.strings(), log.results.map { it.movieId })
        assertEquals(e.int("totalSpins"), store.totalSpins)
        assertEquals(e.int("spinCountOld"), store.spinCount("old"))
        assertEquals(e.obj("archive").int("totalSpins"), log.archivedTotal)
        assertEquals(counts(e.obj("archive").obj("movieCounts")), log.archivedMovieCounts)
        assertEquals(counts(e.obj("archive").obj("categoryCounts")), log.archivedCategoryCounts)
    }

    @Test
    fun emailSuggestion() {
        val s = v.obj("emailSuggestion")
        assertEquals(s.str("address"), MovieSuggestion.ADDRESS)
        for (c in s.arr("cases").objs()) {
            val m = MovieSuggestion(c.str("title")!!, c.str("why") ?: "", c.str("category"))
            assertEquals(c.bool("isValid"), m.isValid)
            assertEquals(c.str("subject"), m.subject)
            assertEquals(c.str("body"), m.body)
            assertEquals(c.str("mailto"), m.mailtoUri)
        }
    }

    @Test
    fun inAppSuggestion() {
        val s = v.obj("inAppSuggestion")
        val limits = s.obj("limits")
        assertEquals(limits.int("title"), InAppSuggestion.TITLE_LIMIT)
        assertEquals(limits.int("note"), InAppSuggestion.NOTE_LIMIT)
        assertEquals(limits.int("creditName"), InAppSuggestion.NAME_LIMIT)
        for (c in s.arr("cases").objs()) {
            val x = InAppSuggestion(c.str("title")!!, c.str("category"), c.str("note")!!, c.str("creditName")!!)
            val e = c.obj("expected")
            assertEquals(e.bool("isValid"), x.isValid)
            assertEquals(e.int("noteCharactersLeft"), x.noteCharactersLeft)
            assertEquals(e["problems"]!!.strings(), x.problems.map { it.name.replaceFirstChar(Char::lowercase) })
            assertEquals(e.obj("asEmail").str("subject"), x.asEmail.subject)
            assertEquals(e.obj("asEmail").str("body"), x.asEmail.body)
            val rf = e.obj("recordFields")
            assertEquals(rf.str("title"), x.trimmedTitle)
            assertEquals(rf.str("note"), x.trimmedNote)
            assertEquals(rf.str("creditName"), x.trimmedCreditName)
            assertEquals(rf.str("category"), x.trimmedCategory ?: "")
        }
    }

    @Test
    fun suggestionRateLimit() {
        val r = v.obj("suggestionRateLimit")
        assertEquals(r.int("intervalSeconds")!! * 1000L, SuggestionRateLimit.INTERVAL_MS)
        val base = 1_000_000_000L
        for (c in r.arr("cases").objs()) {
            val limit = SuggestionRateLimit(c.dbl("lastSentOffset")?.let { base + (it * 1000).toLong() })
            val now = base + (c.dbl("nowOffset")!! * 1000).toLong()
            assertEquals("$c", c.bool("canSend"), limit.canSend(now))
            assertEquals("$c", c.int("secondsRemaining"), limit.secondsRemaining(now))
            assertEquals("$c", c.str("message"), limit.secondsRemaining(now)?.let(SuggestionRateLimit::message))
        }
    }

    // Same episodes as tools/vector-gen (badgeEps).
    private val badgeEps = listOf("2026-10-06T04:00:00Z", "2026-09-29T04:00:00Z", "2026-09-22T04:00:00Z")
        .mapIndexed { i, d -> PodcastEpisode(guid = "e$i", title = "E$i", pubDate = ms(d)) } + PodcastEpisode(guid = "undated", title = "U")

    @Test
    fun episodeBadge() {
        val b = v.obj("episodeBadge")
        assertEquals(b["episodes"]!!.objs().map { it.str("guid") to ms(it.str("pubDate")) }, badgeEps.map { it.guid to it.pubDate })
        for (c in b.arr("count").objs()) assertEquals("$c", c.int("expected"), NewEpisodeBadge.count(badgeEps, ms(c.str("lastViewed"))))
        val t0 = ms("2026-10-01T12:00:00Z")!!
        assertEquals(ms(b.str("initialBaseline")), NewEpisodeBadge.initialBaseline(badgeEps, t0))
        for (c in b.arr("viewedTimestamp").objs()) assertEquals(ms(c.str("expected")), NewEpisodeBadge.viewedTimestamp(badgeEps, ms(c.str("now"))!!))
    }

    @Test
    fun newEpisodeAlerts() {
        val n = v.obj("newEpisodeAlerts")
        val old = badgeEps.drop(1)
        assertEquals(n["baselineFeedGuids"]!!.strings(), old.map { it.guid })
        val mark = NewEpisodeDiff.watermark(old)
        assertEquals(ms(n.obj("watermark").str("latestDate")), mark.latestDate)
        assertEquals(n.obj("watermark")["seenGUIDs"]!!.strings(), mark.seenGUIDs)
        val feeds = listOf(badgeEps, listOf(PodcastEpisode(guid = "n2", title = "Newer", pubDate = ms("2026-10-13T04:00:00Z"))) + badgeEps, badgeEps)
        n.arr("cases").objs().zip(feeds).forEachIndexed { i, (c, feed) ->
            assertEquals(c["feedGuids"]!!.strings(), feed.map { it.guid })
            val m = if (c.containsKey("watermark")) null else mark
            val new = NewEpisodeDiff.newEpisodes(feed, m)
            assertEquals("case $i", c["new"]!!.strings(), new.map { it.guid })
            val text = NewEpisodeDiff.notificationText(new)
            val et = c["text"] as? JsonObject
            assertEquals("case $i", et?.let { it.str("title") to it.str("body") }, text?.let { it.title to it.body })
        }
    }

    @Test
    fun episodeGrouping() {
        val g = v.obj("episodeGrouping")
        val eps = g["episodes"]!!.objs().map { PodcastEpisode(guid = it.str("guid")!!, title = it.str("guid")!!, pubDate = ms(it.str("pubDate")), season = it.int("season")) }
        for ((key, by) in listOf("all" to EpisodeGrouping.All, "season" to EpisodeGrouping.Season, "year" to EpisodeGrouping.Year)) {
            val got = EpisodeGrouper.groups(eps, by, ZoneOffset.UTC)
            assertEquals(key, g[key]!!.objs().map { Triple(it.str("id"), it.str("title"), it["guids"]!!.strings()) }, got.map { Triple(it.id, it.title, it.episodes.map { e -> e.guid }) })
        }
        val exp = EpisodeGroupExpansion.parse(g.str("expansionSerialized")!!)
        assertEquals(g.str("expansionSerialized"), exp.serialized)
    }

    @Test
    fun stableHash() {
        for (c in v.obj("spinOfTheDay").arr("stableHash").objs()) {
            assertEquals(c.str("input"), c.str("fnv1a64")!!.toULong(), StableHash.fnv1a64(c.str("input")!!))
        }
    }
}
