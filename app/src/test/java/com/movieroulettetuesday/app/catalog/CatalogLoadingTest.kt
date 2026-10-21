package com.movieroulettetuesday.app.catalog

import java.io.File
import java.io.IOException
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CatalogLoadingTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun cache(build: String = "1") = CatalogCache(File(tmp.root, CatalogCache.FILE_NAME), build)

    @Test fun rawUrlPointsAtRepoMoviesJson() {
        assertEquals(
            "https://raw.githubusercontent.com/movieroulettetuesday/movie-roulette-tuesday/main/MovieRouletteTuesday/Resources/movies.json",
            RemoteCatalog.URL,
        )
        assertEquals(8_000, RemoteCatalog.TIMEOUT_MS)
    }

    @Test fun remoteSuccessReplacesAndCaches() {
        val cache = cache()
        val data = CatalogFixtures.minimal(title = "Fresh")
        val got = CatalogRefresher(RemoteCatalogSource(fetch = { data }), cache).refresh(nowEpochMs = 42)
        assertEquals("Fresh", got.movies.single().title)
        assertArrayEquals(data, cache.loadData())
        assertEquals(42L, cache.fetchedAtEpochMs)
    }

    @Test fun badRemoteNeverReplacesCache() {
        val cache = cache()
        val good = CatalogFixtures.minimal(title = "Good")
        cache.save(good)
        val bads = listOf<() -> ByteArray>(
            { throw HttpStatusException(500) },
            { throw IOException("offline") },
            { "not json".toByteArray() },
            { CatalogFixtures.minimal(schema = 99) },
            { CatalogFixtures.minimal(color = "nope") },
        )
        for (bad in bads) {
            val r = runCatching { CatalogRefresher(RemoteCatalogSource(fetch = { bad() }), cache).refresh() }
            assertTrue(r.isFailure)
            assertArrayEquals(good, cache.loadData())
        }
    }

    @Test fun cacheFromAnotherBuildIsIgnored() {
        cache("1").save(CatalogFixtures.minimal(title = "Old build"))
        assertNull(cache("2").loadData())
        assertNull(cache("2").fetchedAtEpochMs)
        assertEquals("Old build", cache("1").loadCatalog()!!.movies.single().title)
    }

    @Test fun corruptCacheFallsBackToBundled() {
        val cache = cache()
        cache.save("{broken".toByteArray())
        val c = LocalCatalog.load(cache) { CatalogFixtures.bundledBytes }
        assertTrue(c.movies.size > 100)
    }

    @Test fun offlineUsesCacheThenBundle() {
        val cache = cache()
        assertTrue(LocalCatalog.load(cache) { CatalogFixtures.bundledBytes }.movies.size > 100)
        cache.save(CatalogFixtures.minimal(title = "Cached"))
        assertEquals("Cached", LocalCatalog.load(cache) { CatalogFixtures.bundledBytes }.movies.single().title)
        cache.clear()
        assertNull(cache.loadData())
    }

    @Test fun repositoryLoadsLocalThenRefreshesAndThrottles() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var now = 0L
        var fetches = 0
        var payload = CatalogFixtures.minimal(title = "Remote 1")
        val cache = cache()
        val repo = CatalogRepository(
            cache = cache,
            bundled = { CatalogFixtures.bundledBytes },
            refresher = CatalogRefresher(RemoteCatalogSource(fetch = { fetches++; payload }), cache),
            io = dispatcher,
            clock = { now },
        )
        assertEquals(CatalogRepository.State.Loading, repo.state.value)
        assertTrue(repo.load()!!.movies.size > 100)
        assertTrue(repo.refresh())
        assertEquals("Remote 1", repo.catalog!!.movies.single().title)
        // Within 15 s: skipped.
        payload = CatalogFixtures.minimal(title = "Remote 2")
        now = 10_000
        assertFalse(repo.refresh())
        assertEquals(1, fetches)
        // Later: applied. Same content again: not "changed".
        now = 30_000
        assertTrue(repo.refresh())
        now = 60_000
        assertFalse(repo.refresh())
        assertEquals("Remote 2", repo.catalog!!.movies.single().title)
        // A second load never replaces what is shown.
        repo.load()
        assertEquals("Remote 2", repo.catalog!!.movies.single().title)
    }

    @Test fun repositoryReportsFailureWhenNothingLoads() = runTest {
        val repo = CatalogRepository(
            cache = null,
            bundled = { throw IOException("missing asset") },
            refresher = null,
            io = StandardTestDispatcher(testScheduler),
        )
        assertNull(repo.load())
        assertEquals(CatalogRepository.State.Failed(CatalogRepository.LOAD_FAILED), repo.state.value)
        assertFalse(repo.refresh())
    }

    @Test fun httpGetRefusesCleartext() {
        assertTrue(runCatching { RemoteCatalogSource.httpGet("http://example.com/movies.json") }.isFailure)
    }
}
