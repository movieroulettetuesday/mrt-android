package com.movieroulettetuesday.app.shared

import com.movieroulettetuesday.app.roulette.SpinMotion
import com.movieroulettetuesday.app.roulette.SpinStopPlan
import com.movieroulettetuesday.app.roulette.SpinTicks
import com.movieroulettetuesday.app.roulette.SwiftCompatRandom
import com.movieroulettetuesday.app.roulette.TimingCurve
import com.movieroulettetuesday.app.roulette.WheelGeometry
import com.movieroulettetuesday.app.roulette.WheelMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** wheel.json: geometry, pointer mapping, targets, click-locked motion, stop plan, curves (D4). */
class WheelVectorsTest {
    private val w = SharedVectors.load("wheel.json")

    private fun geometryFor(c: kotlinx.serialization.json.JsonObject): WheelGeometry =
        c["weights"]?.let { ws -> WheelGeometry.fromWeights(ws.jsonArrayOrEmpty().map { num(it) }) }
            ?: WheelGeometry.uniform(c.int("segmentCount")!!)

    private fun kotlinx.serialization.json.JsonElement.jsonArrayOrEmpty() = (this as? kotlinx.serialization.json.JsonArray) ?: kotlinx.serialization.json.JsonArray(emptyList())

    @Test
    fun geometriesAndPointer() {
        for (g in w.arr("geometries").objs()) {
            val geom = geometryFor(g)
            assertEquals(g.str("name"), g.bool("isUniform"), geom.isUniform)
            for (p in g.arr("probes").objs()) {
                val rot = p.dbl("rotation")!!
                assertClose("${g.str("name")} pointer $rot", p.dbl("pointerAngle")!!, WheelMath.pointerAngle(rot), 1e-6)
                assertEquals("${g.str("name")} index $rot", p.int("segmentIndex"), geom.segmentIndex(rot))
            }
        }
        for (p in w.arr("pointer").objs()) {
            val rot = p.dbl("rotation")!!
            assertClose("pointer $rot", p.dbl("pointerAngle")!!, WheelMath.pointerAngle(rot), 1e-6)
            assertEquals(p.int("segmentIndex7"), WheelMath.segmentIndex(rot, 7))
            assertEquals(p.int("segmentIndex10"), WheelMath.segmentIndex(rot, 10))
        }
    }

    @Test
    fun boundaryRotations() {
        for (c in w.arr("boundaryRotations").objs()) {
            val got = SpinTicks.boundaryRotations(c.dbl("from")!!, c.dbl("to")!!, WheelGeometry.uniform(c.int("segmentCount")!!))
            assertEquals(c["expected"]!!.doubles(), got)
        }
    }

    @Test
    fun seededRandomTargets() {
        for (c in w.arr("randomTargets").objs()) {
            val turns = c["fullTurns"]!!.doubles().map { it.toInt() }
            val geom = geometryFor(c)
            val target = geom.randomTargetRotation(c.dbl("from")!!, SwiftCompatRandom(c.seed()), turns[0]..turns[1], c.dbl("edgePadding")!!)
            assertClose("seed ${c.seed()} target", c.dbl("target")!!, target, 1e-6)
            assertEquals(c.int("segmentIndex"), geom.segmentIndex(target))
        }
    }

    @Test
    fun baseCurve() {
        val sm = w.obj("spinMotion")
        assertClose("duration", sm.dbl("spinDuration")!!, SpinMotion.SPIN_DURATION, 1e-12)
        for (s in sm.arr("baseProgress").objs()) assertClose("p(${s.dbl("t")})", s.dbl("baseProgress")!!, SpinMotion.baseProgress(s.dbl("t")!!), 1e-9)
        for (s in sm.arr("baseTimeAtProgress").objs()) assertClose("t(${s.dbl("p")})", s.dbl("baseTime")!!, SpinMotion.baseTime(s.dbl("p")!!), 1e-9)
    }

    @Test
    fun clickLockedPlan() {
        val p = w.obj("spinMotion").obj("plan")
        val plan = SpinMotion.plan(p.dbl("from")!!, p.dbl("to")!!, WheelGeometry.uniform(p.int("segmentCount")!!))
        assertEquals(p.int("crossingCount"), plan.crossingTimes.size)
        p["crossingTimes"]!!.doubles().zip(plan.crossingTimes).forEachIndexed { i, (e, a) -> assertClose("crossing $i", e, a, 1e-9) }
        p["snapOffsets"]!!.doubles().zip(plan.snapOffsets).forEachIndexed { i, (e, a) -> assertClose("offset $i", e, a, 1e-9) }
        assertEquals(p.int("snappedCount"), plan.snapOffsets.count { it != 0.0 })
        for (s in p.arr("progressSamples").objs()) assertClose("progress(${s.dbl("t")})", s.dbl("progress")!!, plan.progress(s.dbl("t")!!), 1e-9)
        assertClose("duration", p.dbl("duration")!!, plan.duration, 1e-12)
    }

    @Test
    fun tapToStopPlan() {
        val sp = w.obj("spinMotion").obj("stopPlan")
        val geom = WheelGeometry.uniform(10)
        val start = sp.dbl("start")!!
        val oldTarget = sp.dbl("oldTarget")!!
        val motion = SpinMotion.plan(start, oldTarget, geom)
        val plan = SpinStopPlan.make(motion, start, oldTarget, sp.dbl("elapsed")!!, geom, SwiftCompatRandom(sp.seed()))!!
        assertClose("current", sp.dbl("currentAngle")!!, plan.currentAngle, 1e-6)
        assertClose("newTarget", sp.dbl("newTarget")!!, plan.newTarget, 1e-6)
        assertClose("end", sp.dbl("displayedAngleAtEnd")!!, plan.displayedAngle(plan.endTime), 1e-6)
        assertEquals(sp.int("landedIndex"), geom.segmentIndex(plan.newTarget))
        assertTrue(plan.newTarget - plan.currentAngle >= SpinStopPlan.MINIMUM_TRAVEL)
        assertClose("continuity", plan.currentAngle, plan.displayedAngle(plan.startTime), 1e-9)
        if (sp.bool("tooLateReturnsNil") == true) {
            assertNull(SpinStopPlan.make(motion, start, oldTarget, motion.duration - 0.5, geom, SwiftCompatRandom(1)))
        }
    }

    @Test
    fun timingCurves() {
        val tc = w.obj("timingCurves")
        val e = tc["easeOut"]!!.doubles()
        val s = tc["spin"]!!.doubles()
        assertEquals(TimingCurve(e[0], e[1], e[2], e[3]), TimingCurve.easeOut)
        assertEquals(TimingCurve(s[0], s[1], s[2], s[3]), TimingCurve.spin)
        for (x in tc.arr("samples").objs()) {
            val t = x.dbl("t")!!
            assertClose("easeOut($t)", x.dbl("easeOutProgress")!!, TimingCurve.easeOut.progress(t), 1e-9)
            assertClose("spin($t)", x.dbl("spinCurveProgress")!!, TimingCurve.spin.progress(t), 1e-9)
            assertClose("spinT($t)", x.dbl("spinCurveTimeAtProgress")!!, TimingCurve.spin.timeFraction(t), 1e-9)
        }
    }
}
