package com.movieroulettetuesday.app.episodes

import com.movieroulettetuesday.app.data.InMemoryKeyValueStore
import com.movieroulettetuesday.app.episodes.EpisodeFixtures.item
import com.movieroulettetuesday.app.episodes.EpisodeFixtures.rss
import java.io.File
import java.net.SocketTimeoutException
import java.nio.file.Files
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class EpisodeRepositoryTest {
    private val dir: File = Files.createTempDirectory("eps").toFile()
    private val feedV1 = rss(
        item("g2", "Second", "Tue, 13 Oct 2026 00:00:00 -0400", 4, 35),
        item("g1", "First", "Tue, 06 Oct 2026 00:00:00 -0400", 4, 34),
    )
    private val feedV2 = rss(
        item("g3", "Third", "Tue, 20 Oct 2026 00:00:00 -0400", 4, 36),
        item("g2", "Second", "Tue, 13 Oct 2026 00:00:00 -0400", 4, 35),
        item("g1", "First", "Tue, 06 Oct 2026 00:00:00 -0400", 4, 34),
    )

    private class FakeFeed(var body: String?) {
        var calls = 0
        val fetch: (String) -> ByteArray = { calls++; body?.toByteArray() ?: throw SocketTimeoutException("slow") }
    }

    @Test
    fun loadsNetworkWritesCacheAndSetsBadgeBaseline() = runTest {
        val feed = FakeFeed(feedV1)
        var now = 1_000L
        val settings = InMemoryKeyValueStore()
        var widgetEpisodes: List<PodcastEpisode>? = null
        val repo = EpisodeRepository(File(dir, "c.json"), settings, feed.fetch,
            io = UnconfinedTestDispatcher(testScheduler), clock = { now }, onEpisodesApplied = { widgetEpisodes = it })
        repo.load()
        val s = repo.state.value
        assertEquals(listOf("g2", "g1"), s.episodes.map { it.guid })
        assertEquals(EpisodeRepository.LoadState.Loaded(fromCache = false), s.loadState)
        assertEquals("First launch: back catalog is not new", 0, s.newEpisodeCount)
        assertTrue(File(dir, "c.json").isFile)
        assertEquals(2, widgetEpisodes?.size)

        // Within 5 minutes the network result is reused; force always fetches.
        repo.load()
        assertEquals(1, feed.calls)
        feed.body = feedV2
        now += 60_000
        repo.load(forceNetwork = true)
        assertEquals(2, feed.calls)
        assertEquals(1, repo.state.value.newEpisodeCount)
        repo.markViewed()
        assertEquals(0, repo.state.value.newEpisodeCount)
        assertEquals(repo.state.value.lastViewedAt, settings.getLong(EpisodeRepository.LAST_VIEWED_KEY))
    }

    @Test
    fun offlineShowsCacheWithSoftError() = runTest {
        val cache = File(dir, "c2.json")
        val io = UnconfinedTestDispatcher(testScheduler)
        EpisodeRepository(cache, InMemoryKeyValueStore(), FakeFeed(feedV1).fetch, io = io).load()

        val offline = EpisodeRepository(cache, InMemoryKeyValueStore(), FakeFeed(null).fetch, io = io)
        offline.load()
        val s = offline.state.value
        assertEquals(2, s.episodes.size)
        assertEquals(EpisodeRepository.LoadState.Loaded(fromCache = true), s.loadState)
        assertEquals("Loading episodes took too long. Pull down to try again.", s.lastError)
    }

    @Test
    fun noCacheAndFailureIsFailedState() = runTest {
        val repo = EpisodeRepository(File(dir, "none.json"), InMemoryKeyValueStore(), { throw RssFeedException.malformed() },
            io = UnconfinedTestDispatcher(testScheduler))
        repo.load()
        val ls = repo.state.value.loadState
        assertTrue(ls is EpisodeRepository.LoadState.Failed)
        assertEquals("Couldn’t read the episode list. Pull down to try again.", (ls as EpisodeRepository.LoadState.Failed).message)
        repo.markViewed() // no-op without episodes
        assertNull(repo.state.value.lastViewedAt)
    }

    @Test
    fun corruptCacheIsIgnoredAndBrokenFeedNeverReplacesCache() = runTest {
        val cache = File(dir, "c3.json").apply { writeText("{not json") }
        val io = UnconfinedTestDispatcher(testScheduler)
        val repo = EpisodeRepository(cache, InMemoryKeyValueStore(), FakeFeed("<rss><channel><item>").fetch, io = io)
        repo.load()
        assertTrue(repo.state.value.loadState is EpisodeRepository.LoadState.Failed)
        assertEquals("{not json", cache.readText())
    }

    @Test
    fun appleLinksThrottleAndCache() = runTest {
        var calls = 0
        val body = """{"results":[{"wrapperType":"podcastEpisode","collectionId":1666123849,"trackId":7,"episodeGuid":"g1"}]}"""
        var now = 0L
        val file = File(dir, "apple.json")
        val links = AppleEpisodeLinks(file, { calls++; body.toByteArray() }, UnconfinedTestDispatcher(testScheduler), { now })
        links.ensure("g1")
        links.ensure("g1")
        assertEquals(1, calls)
        links.ensure("unknown")
        assertEquals("throttled for an hour", 1, calls)
        now += AppleEpisodeLinks.MIN_REFRESH_MS
        links.ensure("unknown")
        assertEquals(2, calls)
        val reloaded = AppleEpisodeLinks(file, null, UnconfinedTestDispatcher(testScheduler))
        reloaded.ensure("g1")
        assertEquals(mapOf("g1" to 7L), reloaded.trackIds.value)
    }
}
