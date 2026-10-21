package com.movieroulettetuesday.app.roulette

import com.movieroulettetuesday.app.catalog.CatalogRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RouletteViewModelTest {
    private val catalogFlow = MutableStateFlow<CatalogRepository.State>(CatalogRepository.State.Loaded(RouletteFixtures.catalog))

    private fun TestScope.vm(
        rare: RareOutcomePolicy = RareOutcomePolicy(isEnabled = false),
        seed: Long = 5,
        history: SpinHistoryStore = SpinHistoryStore(null),
    ) = RouletteViewModel(
        catalogState = catalogFlow,
        history = history,
        scope = TestScope(UnconfinedTestDispatcher(testScheduler)).backgroundScope,
        random = SeededRandom(seed),
        rarePolicy = rare,
    )

    @Test fun spinLandsOnCategoryThenPicksAMovieFromIt() = runTest {
        val history = SpinHistoryStore(null)
        val vm = vm(history = history)
        assertTrue(vm.state.value.canSpin)
        vm.spin()
        val run = vm.state.value.spin!!
        assertTrue(run.to - run.from >= 5 * 360 - 3)
        assertFalse(vm.state.value.canSpin)
        vm.spin() // ignored while spinning
        assertEquals(run, vm.state.value.spin)
        vm.spinAnimationEnded(run.token)
        val s = vm.state.value
        assertNull(s.spin)
        val landed = RouletteFixtures.catalog.categories[s.geometry.segmentIndex(run.to)]
        assertEquals(landed, s.lastCategory)
        val reveal = s.reveal!!
        assertEquals(landed, reveal.category)
        assertTrue(reveal.movie.id in landed.movieIds)
        assertEquals(1, reveal.spinCount)
        assertEquals(1, history.totalSpins)
        assertFalse(s.canSpin) // reveal is up
    }

    @Test fun staleAnimationCallbackIsIgnoredAndWatchdogFinishes() = runTest {
        val vm = vm()
        vm.spin()
        val run = vm.state.value.spin!!
        vm.spinAnimationEnded(run.token + 1)
        assertNotNull(vm.state.value.spin)
        advanceTimeBy(run.durationMs + RouletteViewModel.WATCHDOG_GRACE_MS + 10)
        runCurrent()
        assertNull(vm.state.value.spin)
        assertNotNull(vm.state.value.reveal)
    }

    @Test fun spinAgainDismissesAndStartsANewSpin() = runTest {
        val vm = vm()
        vm.spin()
        vm.spinAnimationEnded(vm.state.value.spin!!.token)
        vm.spinAgain()
        assertNull(vm.state.value.reveal)
        assertNotNull(vm.state.value.spin)
        // Next spin starts where the last one settled (normalised to [0, 360)).
        val r = vm.state.value.spin!!.from
        assertTrue(r >= 0 && r < 360)
    }

    @Test fun forcedRespinShowsBannerThenSpinsAgainOnce() = runTest {
        val always = RareOutcomePolicy(forcedRespinChance = 1.0, infamousJackpotChance = 0.0)
        val vm = vm(rare = always)
        vm.spin()
        vm.spinAnimationEnded(vm.state.value.spin!!.token)
        assertEquals("RE-SPIN FORCED!", vm.state.value.rareBanner)
        assertTrue(vm.state.value.isAwaitingForcedRespin)
        assertNull(vm.state.value.reveal)
        advanceTimeBy(RouletteViewModel.FORCED_RESPIN_DELAY_MS + 10)
        runCurrent()
        val run = vm.state.value.spin!!
        assertNull(vm.state.value.rareBanner)
        vm.spinAnimationEnded(run.token)
        // A forced re-spin never chains: this time the result is revealed.
        assertNotNull(vm.state.value.reveal)
    }

    @Test fun infamousJackpot() = runTest {
        catalogFlow.value = CatalogRepository.State.Loaded(RouletteFixtures.withInfamous())
        val jackpot = RareOutcomePolicy(forcedRespinChance = 0.0, infamousJackpotChance = 1.0)
        val vm = vm(rare = jackpot)
        vm.spin()
        vm.spinAnimationEnded(vm.state.value.spin!!.token)
        val reveal = vm.state.value.reveal!!
        assertTrue(reveal.isJackpot)
        assertEquals("d-2", reveal.movie.id)
        assertEquals("Infamous Jackpot!", vm.state.value.notice)
    }

    @Test fun refreshedCatalogIsDeferredUntilIdle() = runTest {
        val vm = vm()
        vm.spin()
        val smaller = RouletteFixtures.catalog.copy(categories = RouletteFixtures.catalog.categories.take(2))
        catalogFlow.value = CatalogRepository.State.Loaded(smaller)
        assertEquals(4, vm.state.value.catalog!!.categories.size)
        vm.spinAnimationEnded(vm.state.value.spin!!.token)
        assertEquals(4, vm.state.value.catalog!!.categories.size) // reveal still up
        vm.dismissReveal()
        assertEquals(2, vm.state.value.catalog!!.categories.size)
        assertEquals(2, vm.state.value.geometry.count)
    }

    @Test fun cannotSpinWithoutCatalog() = runTest {
        catalogFlow.value = CatalogRepository.State.Failed("nope")
        val vm = vm()
        assertFalse(vm.state.value.canSpin)
        vm.spin()
        assertNull(vm.state.value.spin)
        assertEquals(CatalogRepository.State.Failed("nope"), vm.state.value.load)
    }

    @Test fun shareTextMatchesIos() {
        val m = RouletteFixtures.catalog.movie("a-1")!!
        assertEquals("Tonight’s Movie Roulette Tuesday spin: Movie a 1 (2001)", SpinShare.message(m))
        assertEquals("Discussed on Ep a", EpisodeLabel.discussedText(m))
        assertEquals("Not discussed on the show yet", EpisodeLabel.discussedText(m.copy(episodeIds = emptyList())))
    }
}
