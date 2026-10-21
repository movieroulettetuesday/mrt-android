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
import org.junit.Assert.assertTrue
import org.junit.Test

/** D4 tap-to-stop + clip feedback, D5 forced re-spin notice. */
@OptIn(ExperimentalCoroutinesApi::class)
class SpinStopAndSoundTest {
    private class Recorder : SpinFeedback {
        val events = mutableListOf<String>()
        override fun startClip() { events += "start" }
        override fun stopClip(fadeSeconds: Double) { events += "stop:$fadeSeconds" }
    }

    private var clock = 100.0

    private fun TestScope.vm(feedback: SpinFeedback, sound: Boolean = true, rare: RareOutcomePolicy = RareOutcomePolicy(isEnabled = false), seed: Long = 9) = RouletteViewModel(
        catalogState = MutableStateFlow<CatalogRepository.State>(CatalogRepository.State.Loaded(RouletteFixtures.catalog)),
        history = SpinHistoryStore(null),
        scope = TestScope(UnconfinedTestDispatcher(testScheduler)).backgroundScope,
        random = SwiftCompatRandom(seed),
        rarePolicy = rare,
        feedback = feedback,
        soundEnabled = { sound },
        now = { clock },
    )

    @Test fun tapToStopReplansToANearTargetAndFadesTheClip() = runTest {
        val rec = Recorder()
        val vm = vm(rec)
        vm.spin()
        val run = vm.state.value.spin!!
        assertTrue(vm.state.value.canStop)
        assertEquals("start", rec.events.last())
        clock += 2.0
        vm.stopEarly()
        val stopped = vm.state.value.spin!!
        val plan = assertNotNull(stopped.stop).let { stopped.stop!! }
        assertEquals("stop:${SpinStopPlan.DURATION}", rec.events.last())
        assertTrue(plan.newTarget < run.to) // shorter than the original spin
        assertTrue(plan.newTarget - plan.currentAngle >= SpinStopPlan.MINIMUM_TRAVEL)
        assertEquals(plan.currentAngle, stopped.angleAt(plan.startTime), 1e-9)
        vm.stopEarly() // second tap ignored
        assertEquals(stopped, vm.state.value.spin)
        vm.spinAnimationEnded(stopped.token)
        val s = vm.state.value
        assertEquals(RouletteFixtures.catalog.categories[s.geometry.segmentIndex(plan.newTarget)], s.lastCategory)
        assertEquals(plan.newTarget, s.rotation, 1e-9)
    }

    @Test fun tapToStopTooLateIsIgnored() = runTest {
        val vm = vm(Recorder())
        vm.spin()
        val run = vm.state.value.spin!!
        clock += run.motion.duration - 0.2
        vm.stopEarly()
        assertEquals(null, vm.state.value.spin!!.stop)
    }

    @Test fun soundOffStartsNoClip() = runTest {
        val rec = Recorder()
        val vm = vm(rec, sound = false)
        vm.spin()
        assertFalse("start" in rec.events)
    }

    @Test fun forcedRespinShowsTheIosNoticeOnTheAutomaticSpin() = runTest {
        val vm = vm(Recorder(), rare = RareOutcomePolicy(forcedRespinChance = 1.0, infamousJackpotChance = 0.0))
        vm.spin()
        vm.spinAnimationEnded(vm.state.value.spin!!.token)
        assertEquals("Re-spin forced! The wheel demands a do-over.", vm.state.value.notice)
        advanceTimeBy(RouletteViewModel.FORCED_RESPIN_DELAY_MS + 1)
        runCurrent()
        assertNotNull(vm.state.value.spin)
        assertEquals("Re-spin forced! Here we go again…", vm.state.value.notice)
        vm.spinAnimationEnded(vm.state.value.spin!!.token)
        assertNotNull(vm.state.value.reveal) // no second forced re-spin in a row
    }
}
