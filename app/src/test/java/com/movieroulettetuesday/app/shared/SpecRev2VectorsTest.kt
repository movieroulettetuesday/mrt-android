package com.movieroulettetuesday.app.shared

import com.movieroulettetuesday.app.catalog.CatalogJson
import com.movieroulettetuesday.app.catalog.CatalogRepository
import com.movieroulettetuesday.app.catalog.Movie
import com.movieroulettetuesday.app.data.InMemoryKeyValueStore
import com.movieroulettetuesday.app.episodes.ArtworkResolver
import com.movieroulettetuesday.app.episodes.EpisodeFeed
import com.movieroulettetuesday.app.roulette.EpisodeLabel
import com.movieroulettetuesday.app.episodes.EpisodeRepository
import com.movieroulettetuesday.app.episodes.PodcastEpisode
import com.movieroulettetuesday.app.roulette.RareOutcomePolicy
import com.movieroulettetuesday.app.roulette.RouletteFixtures
import com.movieroulettetuesday.app.roulette.RouletteViewModel
import com.movieroulettetuesday.app.roulette.SeededRandom
import com.movieroulettetuesday.app.roulette.SpinControls
import com.movieroulettetuesday.app.roulette.SpinHistoryStore
import com.movieroulettetuesday.app.suggest.InAppSuggestion
import com.movieroulettetuesday.app.widget.SpinOfTheDay
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Sections added in SPEC revision 2 (flows.json, rss/catalog/stats additions). Where a case has both
 * `iosB673df1…` and `expected…`, Android implements `expected` (the shared rule), except discussedFormats,
 * which follows iOS main (the Q9 collapse is not in iOS 1.0.1).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SpecRev2VectorsTest {
    private val rss = SharedVectors.load("rss.json")
    private val flows = SharedVectors.load("flows.json")

    @Test
    fun discussedFormatsMatchIosMain() {
        val d = rss.obj("discussedFormats")
        val feed = d.arr("feed").objs().map { PodcastEpisode(guid = it.str("guid")!!, title = it.str("title")!!, season = it.int("season"), episode = it.int("episode")) }
        val byGuid = PodcastEpisode.byGUID(feed)
        val cases = d.arr("cases").objs()
        assertEquals(15, cases.size)
        for (c in cases) {
            val movie = Movie(id = "m", title = "T", year = 2000, genre = "g", episodeIds = c["episodeIds"]!!.strings(), episodeTitle = c.str("episodeTitle"))
            val got = EpisodeLabel.discussedText(movie, if (c.bool("feedLoaded") == true) byGuid else emptyMap())
            // Q9 collapse is not in iOS 1.0.1 (MRT App, Oct 21): Android follows iOS main here, so the
            // cases whose `expected` collapses (8, 9, 10) are checked against `iosB673df1`.
            assertEquals(c.str("name"), c.str("iosB673df1") ?: c.str("expected"), got)
        }
        assertEquals(listOf(8, 9, 10), cases.indices.filter { cases[it].str("iosB673df1") != cases[it].str("expected") })
    }

    @Test
    fun feedUrlIsHardCodedAndCatalogRssUrlIsNeverFetched() = runTest {
        for (c in rss.obj("feedURLRule").arr("cases").objs()) {
            assertEquals(c.str("fetchURL"), EpisodeFeed.URL)
            // The repository takes no catalog at all; it fetches EpisodeFeed.URL whatever show.rssURL says.
            val urls = mutableListOf<String>()
            val repo = EpisodeRepository(null, InMemoryKeyValueStore(), { u -> urls += u; feedBody("buzzsprout-fixture").toByteArray() }, io = UnconfinedTestDispatcher(testScheduler))
            repo.load()
            assertEquals(listOf(c.str("fetchURL")), urls)
        }
    }

    @Test
    fun zeroEpisodeOrBrokenFeedIsAFailedRefresh() = runTest {
        val io = UnconfinedTestDispatcher(testScheduler)
        val dir: File = Files.createTempDirectory("rev2").toFile()
        val cases = rss.obj("refreshRule").arr("cases").objs()
        val bodies = listOf(listOf("empty-channel"), listOf("not-xml", "truncated rss"), listOf("buzzsprout-fixture"))
        assertEquals(bodies.size, cases.size)
        cases.forEachIndexed { i, c ->
            val e = c.obj("expected")
            for (name in bodies[i]) {
                val cache = File(dir, "c$i-${name.length}.json")
                // Seed the cache with a different 1-episode feed so "changed" is observable.
                val seed = "<rss><channel><title>T</title><item><title>Old</title><guid>old-1</guid></item></channel></rss>"
                EpisodeRepository(cache, InMemoryKeyValueStore(), { seed.toByteArray() }, io = io).load()
                val before = cache.readBytes()
                val repo = EpisodeRepository(cache, InMemoryKeyValueStore(), { feedBody(name).toByteArray() }, io = io)
                repo.load()
                val failed = repo.state.value.loadState is EpisodeRepository.LoadState.Failed || repo.state.value.lastError != null
                assertEquals(name, e.str("refresh") == "failed", failed)
                assertEquals(name, e.bool("cacheOverwritten"), !before.contentEquals(cache.readBytes()))
                assertEquals(name, e.bool("episodesListChanged"), repo.state.value.episodes.map { it.guid } != listOf("old-1"))
            }
        }
    }

    private fun feedBody(prefix: String): String = rss.arr("feeds").objs().first { it.str("name")!!.startsWith(prefix) }.str("input")!!

    @Test
    fun posterUrlDecodingOnlyEmptyFails() {
        for (c in SharedVectors.load("catalog.json").obj("posterURLDecoding").arr("cases").objs()) {
            val e = c.obj("expected")
            val decoded = runCatching { CatalogJson.decode(c.str("input")!!) }
            assertEquals(c.str("posterURL"), e.bool("decodes"), decoded.isSuccess)
            val movie = decoded.getOrNull()?.movies?.single() ?: continue
            // Encoding may differ from Foundation; usable vs not must match.
            assertEquals(c.str("posterURL"), e.str("artworkUsable") != null, ArtworkResolver.usableURL(movie.posterURL) != null)
        }
    }

    @Test
    fun suggestionCreditLineUsesExpectedNotIosBody() {
        for (c in SharedVectors.load("stats.json").obj("inAppSuggestion").obj("creditLine").arr("cases").objs()) {
            val s = InAppSuggestion(c.str("title")!!, null, c.str("note")!!, c.str("creditName")!!)
            assertEquals(c.str("expectedBody"), s.asEmail.body)
        }
    }

    @Test
    fun spinOfTheDayFallbackCategoryOnlyIfKnown() {
        val f = SharedVectors.load("stats.json").obj("spinOfTheDay").obj("fallbackCategory")
        val catalog = SharedVectors.catalog(f["catalog"]!!)
        for (c in f.arr("cases").objs()) {
            val pick = SpinOfTheDay.pick(c.str("dayKey")!!, catalog)!!
            assertEquals(c.str("dayKey"), c.str("movieId"), pick.movie.id)
            assertEquals(c.str("dayKey"), c.str("categoryId"), pick.category?.id)
        }
    }

    @Test
    fun spinControls() {
        val sc = flows.obj("spinControls")
        assertEquals(2, sc.int("stopEarlyRandomDraws")) // asserted against the VM in SpinStopAndSoundTest
        var checked = 0
        for (c in sc.arr("cases").objs()) {
            val raw = c.str("input")!!
            val input = when {
                raw.startsWith("hubButton") -> SpinControls.Input.HubButton
                raw.startsWith("wheelFaceTap") -> SpinControls.Input.WheelFaceTap
                raw.startsWith("magicTap") -> SpinControls.Input.MagicTap
                raw.startsWith("accessibilityActivate") -> SpinControls.Input.AccessibilityActivate
                else -> continue // "leave the screen mid-spin": RouletteViewModelTest.staleAnimationCallbackIsIgnoredAndWatchdogFinishes
            }
            val expected = c.str("expected")!!
            val want = when {
                expected.startsWith("spin") -> SpinControls.Action.Spin
                expected.startsWith("stopEarly") -> SpinControls.Action.StopEarly
                expected.startsWith("nothing") -> SpinControls.Action.Nothing
                else -> fail("unknown expected '$expected'") as Nothing
            }
            assertEquals(raw, want, SpinControls.resolve(input, c.bool("canSpin")!!, c.bool("canStop")!!))
            checked++
        }
        assertEquals(9, checked)
    }

    // region flows.catalogRefresh.apply (driven through the real RouletteViewModel)

    private val catalogFlow = MutableStateFlow<CatalogRepository.State>(CatalogRepository.State.Loaded(RouletteFixtures.catalog))
    private val smaller = RouletteFixtures.catalog.copy(categories = RouletteFixtures.catalog.categories.take(2))

    private fun TestScope.vm() = RouletteViewModel(
        catalogState = catalogFlow,
        history = SpinHistoryStore(null),
        scope = TestScope(UnconfinedTestDispatcher(testScheduler)).backgroundScope,
        random = SeededRandom(5),
        rarePolicy = RareOutcomePolicy(isEnabled = false),
    )

    @Test
    fun catalogRefreshApplyRules() = runTest {
        val events = flows.obj("catalogRefresh").arr("apply").objs().map { it.str("event")!! + " => " + it.str("expected")!! }
        assertEquals(13, events.size)

        // catalog nil -> apply now
        catalogFlow.value = CatalogRepository.State.Failed("x")
        var vm = vm()
        assertNull(vm.state.value.catalog)
        catalogFlow.value = CatalogRepository.State.Loaded(RouletteFixtures.catalog)
        assertEquals(RouletteFixtures.catalog, vm.state.value.catalog)

        // idle + different -> apply now; equal -> no-op (same instance kept)
        catalogFlow.value = CatalogRepository.State.Loaded(smaller)
        assertEquals(2, vm.state.value.geometry.count)
        val kept = vm.state.value.catalog
        catalogFlow.value = CatalogRepository.State.Loaded(smaller.copy())
        assertSame(kept, vm.state.value.catalog)

        // spinning -> pending; finishSpin does NOT apply; reveal dismissed -> applied
        catalogFlow.value = CatalogRepository.State.Loaded(RouletteFixtures.catalog)
        vm = vm()
        vm.spin()
        catalogFlow.value = CatalogRepository.State.Loaded(smaller)
        assertEquals(4, vm.state.value.geometry.count)
        vm.spinAnimationEnded(vm.state.value.spin!!.token)
        assertNotNull(vm.state.value.reveal)
        assertEquals(4, vm.state.value.geometry.count) // reveal open -> still pending
        vm.dismissReveal()
        assertEquals(2, vm.state.value.geometry.count)

        // pending applied BEFORE the next spin's target is drawn
        catalogFlow.value = CatalogRepository.State.Loaded(RouletteFixtures.catalog)
        vm = vm()
        vm.spin()
        vm.spinAnimationEnded(vm.state.value.spin!!.token)
        catalogFlow.value = CatalogRepository.State.Loaded(smaller) // reveal open -> pending
        assertEquals(4, vm.state.value.geometry.count)
        vm.spinAgain()
        assertEquals(2, vm.state.value.geometry.count)
        val run = vm.state.value.spin!!
        assertTrue(vm.state.value.geometry.segmentIndex(run.to) in 0..1)

        // fetch/decode failure -> nothing changes
        catalogFlow.value = CatalogRepository.State.Failed("offline")
        assertEquals(2, vm.state.value.geometry.count)
    }

    // endregion
}
