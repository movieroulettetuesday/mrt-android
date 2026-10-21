package com.movieroulettetuesday.app.roulette

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SpinMotionAndHistoryTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun spinMotionIsMonotoneAndEndsAtOne() {
        assertEquals(6294, SpinMotion.spinDurationMs)
        assertEquals(0.0, SpinMotion.progress(0.0), 0.0)
        assertEquals(1.0, SpinMotion.progress(SpinMotion.SPIN_DURATION + 1), 0.0)
        var last = 0.0
        var t = 0.0
        while (t <= SpinMotion.SPIN_DURATION) {
            val p = SpinMotion.progress(t)
            assertTrue(p >= last - 1e-12)
            last = p
            t += 0.01
        }
        // Wind-up: the first 0.45 s covers less than its time share; braking ends near 1.
        assertTrue(SpinMotion.progress(0.45) < 0.45 / SpinMotion.SPIN_DURATION)
        assertTrue(SpinMotion.progress(SpinMotion.STOP_TIME) > 0.99)
        assertEquals(1f, SpinMotion.easing.transform(1f), 1e-4f)
    }

    @Test fun historyPersistsAndCompacts() {
        val f = File(tmp.root, SpinHistoryStore.FILE_NAME)
        val store = SpinHistoryStore(f, detailLimit = 3)
        repeat(5) { store.record(SpinResult(movieId = if (it < 2) "old" else "m$it", categoryId = "a")) }
        assertEquals(5, store.totalSpins)
        assertEquals(3, store.results.size)
        assertEquals(2, store.spinCount("old"))
        val reloaded = SpinHistoryStore(f, detailLimit = 3)
        assertEquals(5, reloaded.totalSpins)
        assertEquals(2, reloaded.spinCount("old"))
        f.writeText("garbage")
        assertEquals(0, SpinHistoryStore(f).totalSpins)
    }
}
