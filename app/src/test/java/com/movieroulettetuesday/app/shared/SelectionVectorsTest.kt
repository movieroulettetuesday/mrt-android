package com.movieroulettetuesday.app.shared

import com.movieroulettetuesday.app.catalog.CatalogRepository
import com.movieroulettetuesday.app.catalog.MovieCatalog
import com.movieroulettetuesday.app.roulette.FlaggedInfamousSelector
import com.movieroulettetuesday.app.roulette.MovieSelector
import com.movieroulettetuesday.app.roulette.RareOutcome
import com.movieroulettetuesday.app.roulette.RareOutcomePolicy
import com.movieroulettetuesday.app.roulette.RareOutcomeRoller
import com.movieroulettetuesday.app.roulette.RatingWeightedSelector
import com.movieroulettetuesday.app.roulette.RouletteViewModel
import com.movieroulettetuesday.app.roulette.SpinHistoryStore
import com.movieroulettetuesday.app.roulette.SpinResult
import com.movieroulettetuesday.app.roulette.SwiftCompatRandom
import com.movieroulettetuesday.app.roulette.WheelGeometry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** selection.json: recency, weights, candidates, pick intervals, seeded picks, rare outcomes, full spins. */
@OptIn(ExperimentalCoroutinesApi::class)
class SelectionVectorsTest {
    private val v = SharedVectors.load("selection.json")
    private val selector = MovieSelector()

    private fun history(ids: List<String>) = ids.map { SpinResult(movieId = it, categoryId = "x") }

    @Test
    fun recencyMultiplier() {
        for (c in v.arr("recencyMultiplier").objs()) {
            assertClose("spinsAgo ${c.int("spinsAgo")}", c.dbl("expected")!!, selector.recencyMultiplier(c.int("spinsAgo")))
        }
    }

    @Test
    fun weightsAndProbabilities() {
        for (c in v.arr("weights").objs()) {
            val catalog = SharedVectors.catalog(c["catalog"]!!)
            val hist = history(c["historyMovieIds"]!!.strings())
            for (key in c.keys.filter { it.startsWith("category_") }) {
                val category = catalog.categories.first { it.id == key.removePrefix("category_") }
                val rows = c[key]!!.objs()
                val candidates = selector.candidates(category, catalog)
                assertEquals("${c.str("name")} $key ids", rows.map { it.str("movieId") }, candidates.map { it.id })
                val weights = candidates.map { selector.weight(it, hist) }
                val total = weights.sum()
                rows.zip(weights).forEach { (r, w) ->
                    assertClose("${c.str("name")} ${r.str("movieId")} w", r.dbl("weight")!!, w)
                    assertClose("${c.str("name")} ${r.str("movieId")} p", r.dbl("probability")!!, w / total)
                }
            }
        }
    }

    @Test
    fun candidatesUnion() {
        val c = v.obj("candidates")
        val extra = c.obj("extraMovie")
        val base = SharedVectors.catalog(kotlinx.serialization.json.JsonPrimitive("fixture"))
        val template = base.movies.first()
        val ids = c["categoryMovieIds"]!!.strings() // fixture catalog; "ghost" has no movie
        val movies = base.movies + template.copy(id = extra.str("id")!!, categories = extra["categories"]!!.strings())
        val catalog = base.copy(categories = base.categories.map { if (it.id == "a") it.copy(movieIds = ids) else it }, movies = movies)
        assertEquals(c["expected"]!!.strings(), selector.candidates(catalog.categories[0], catalog).map { it.id })
    }

    @Test
    fun pickIndexIntervals() {
        for (c in v.arr("pickIndex").objs()) {
            val ws = c["weights"]!!.jsonArray().map { num(it)!! }
            assertEquals("$ws @ ${c["unitValue"]}", c.int("expected"), MovieSelector.pickIndex(ws, num(c["unitValue"]!!)!!))
        }
    }

    @Test
    fun bundledHorrorWeights() {
        val c = v.obj("bundledHorrorWeights")
        val catalog = SharedVectors.catalog(c["catalog"]!!)
        val category = catalog.categories.first { it.id == c.str("categoryId") }
        val candidates = selector.candidates(category, catalog)
        assertEquals(c.int("candidateCount"), candidates.size)
        val hist = history(c["historyMovieIds"]!!.strings())
        val byId = candidates.associate { it.id to selector.weight(it, hist) }
        val total = byId.values.sum()
        for (r in c.arr("weights").objs()) {
            assertClose(r.str("movieId")!!, r.dbl("weight")!!, byId.getValue(r.str("movieId")!!))
            assertClose(r.str("movieId")!!, r.dbl("probability")!!, byId.getValue(r.str("movieId")!!) / total)
        }
        assertClose("other", c.dbl("otherProbability")!!, c.dbl("otherWeight")!! / total)
    }

    @Test
    fun seededSelectMovie() {
        for (c in v.arr("seededSelectMovie").objs()) {
            val catalog = SharedVectors.catalog(c["catalog"]!!)
            val category = catalog.categories.first { it.id == c.str("categoryId") }
            val hist = history(c["historyMovieIds"]!!.strings())
            val r = SwiftCompatRandom(c.seed())
            val picks = c["picks"]!!.strings().map { selector.selectMovie(category, catalog, hist, r)!!.id }
            assertEquals("seed ${c.seed()}", c["picks"]!!.strings(), picks)
        }
    }

    @Test
    fun rareOutcomesAndClamping() {
        val ro = v.obj("rareOutcome")
        val pol = ro.obj("policy")
        val policy = RareOutcomePolicy(forcedRespinChance = pol.dbl("forcedRespinChance")!!, infamousJackpotChance = pol.dbl("infamousJackpotChance")!!)
        for (c in ro.arr("cases").objs()) {
            val p = if (c.str("policy") == "disabled") RareOutcomePolicy(isEnabled = false) else policy
            val got = RareOutcomeRoller.outcome(num(c["unitValue"]!!)!!, p, c.bool("infamousAvailable")!!, c.bool("allowRespin")!!)
            assertEquals("u=${c["unitValue"]} $c", c.str("expected"), got.wire)
        }
        for (c in ro.arr("policyClamping").objs()) {
            val p = RareOutcomePolicy(forcedRespinChance = num(c["forcedRespinChance"]!!)!!, infamousJackpotChance = num(c["infamousJackpotChance"]!!)!!)
            assertClose("forced", c.obj("effective").dbl("forcedRespin")!!, p.forcedRespinChance)
            assertClose("jackpot", c.obj("effective").dbl("infamousJackpot")!!, p.infamousJackpotChance)
        }
        for (c in ro.arr("jackpotPick").objs()) {
            val catalog = SharedVectors.catalog(c["catalog"]!!)
            val landed = catalog.categories.first { it.id == c.str("landed") }
            val (movie, credited) = FlaggedInfamousSelector.pick(catalog, landed, SwiftCompatRandom(c.seed()))!!
            assertEquals("seed ${c.seed()}", c.str("movieId"), movie.id)
            assertEquals("seed ${c.seed()}", c.str("categoryId"), credited.id)
        }
    }

    /** Android has no Siri/Shortcuts path; this checks the same building blocks compose identically. */
    @Test
    fun quickSpinComposition() {
        for (c in v.obj("quickSpin").arr("cases").objs()) {
            val catalog = SharedVectors.catalog(c["catalog"]!!)
            val geometry = WheelGeometry.forCatalog(catalog)
            val r = SwiftCompatRandom(c.seed())
            var allowRespin = true
            var wasForced = false
            while (true) {
                val target = geometry.randomTargetRotation(0.0, r, fullTurns = 1..1)
                val landed = catalog.categories[geometry.segmentIndex(target)]
                val outcome = RareOutcomeRoller.roll(RareOutcomePolicy(), FlaggedInfamousSelector.isAvailable(catalog), allowRespin, r)
                if (outcome == RareOutcome.ForcedRespin) { allowRespin = false; wasForced = true; continue }
                val jackpot = if (outcome == RareOutcome.InfamousJackpot) FlaggedInfamousSelector.pick(catalog, landed, r) else null
                val movie = jackpot?.first ?: selector.selectMovie(landed, catalog, emptyList(), r)!!
                val tag = "seed ${c.seed()}"
                assertEquals(tag, c.str("landed"), landed.id)
                assertEquals(tag, c.str("categoryId"), (jackpot?.second ?: landed).id)
                assertEquals(tag, c.str("movieId"), movie.id)
                assertEquals(tag, c.bool("isJackpot"), jackpot != null)
                assertEquals(tag, c.bool("wasForcedRespin"), wasForced)
                break
            }
        }
    }

    /** Drives the real RouletteViewModel with the Swift-compatible RNG; rotations compare mod 360 (D6). */
    @Test
    fun appSpinSequenceThroughViewModel() = runTest {
        for (c in v.obj("appSpinSequence").arr("cases").objs()) runCase(c)
    }

    private fun TestScope.runCase(c: JsonObject) {
        val catalog: MovieCatalog = SharedVectors.catalog(c["catalog"]!!)
        val history = SpinHistoryStore(null)
        val vm = RouletteViewModel(
            catalogState = MutableStateFlow<CatalogRepository.State>(CatalogRepository.State.Loaded(catalog)),
            history = history,
            scope = TestScope(UnconfinedTestDispatcher(testScheduler)).backgroundScope,
            random = SwiftCompatRandom(c.seed()),
            reduceMotion = { c.bool("reduceMotion")!! },
        )
        vm.spin()
        c.arr("steps").objs().forEachIndexed { i, step ->
            val tag = "seed ${c.seed()} step $i"
            val run = assertNotNull(tag, vm.state.value.spin).let { vm.state.value.spin!! }
            assertClose("$tag rotation", mod360(step.dbl("rotation")!!), mod360(run.to), 1e-6)
            vm.spinAnimationEnded(run.token)
            assertEquals(tag, step.str("landed"), vm.state.value.lastCategory?.id)
            if (step.str("outcome") == "forcedRespin") {
                assertTrue(tag, vm.state.value.isAwaitingForcedRespin)
                advanceTimeBy(RouletteViewModel.FORCED_RESPIN_DELAY_MS + 1)
                runCurrent()
            } else {
                val reveal = vm.state.value.reveal!!
                assertEquals(tag, step.str("movieId"), reveal.movie.id)
                assertEquals(tag, step.str("categoryId"), reveal.category.id)
                assertEquals(tag, step.str("outcome") == "infamousJackpot", reveal.isJackpot)
                vm.spinAgain()
            }
        }
    }

    private fun mod360(x: Double) = ((x % 360) + 360) % 360
    private fun kotlinx.serialization.json.JsonElement.jsonArray() = this as kotlinx.serialization.json.JsonArray

    @Test
    fun ratingWeightedJackpotVectors() {
        fun w(avgs: List<Double?>) = avgs.map { RatingWeightedSelector.weight(it) }
        val units = listOf(0.0, 0.25, 0.5, 0.75, 0.999)
        val cases = listOf(
            Triple(w(listOf(1.0, 5.0)), listOf(4.5, 0.5), listOf(0, 0, 0, 0, 1)),
            Triple(w(listOf(1.0, 3.0, 5.0, null)), listOf(4.5, 2.5, 0.5, 2.5), listOf(0, 0, 1, 3, 3)),
            Triple(w(List(4) { null }), List(4) { 2.5 }, listOf(0, 1, 2, 3, 3)),
            Triple(w(listOf(0.5, 2.5, 4.5)), listOf(5.0, 3.0, 1.0), listOf(0, 0, 0, 1, 2)),
            Triple(w(listOf(5.0, 2.0, null)), listOf(0.5, 3.5, 2.5), listOf(0, 1, 1, 2, 2)), // V5 after 5d459a0 (no flag)
        )
        cases.forEachIndexed { i, (got, weights, idx) ->
            assertEquals("V${i + 1} weights", weights, got)
            assertEquals("V${i + 1} picks", idx, units.map { RatingWeightedSelector.pickIndex(got, it) })
        }
        assertEquals(2.5, RatingWeightedSelector.weight(Double.NaN), 0.0)
        assertEquals(0.5, RatingWeightedSelector.weight(9.0), 0.0)
        assertEquals(5.0, RatingWeightedSelector.weight(0.0), 0.0)

        val v1 = cases[0].first
        val v2 = cases[1].first
        fun picks(ws: List<Double>, n: Int) = SwiftCompatRandom(42).let { r -> List(n) { RatingWeightedSelector.pickIndex(ws, r.nextDouble())!! } }
        assertEquals(listOf(0, 0, 0, 1, 0, 0, 0, 0, 0, 0), picks(v1, 10))
        assertEquals(listOf(2, 1, 1, 3, 3, 0, 0, 1, 0, 1), picks(v2, 10))
        assertEquals(listOf(89969, 10031), picks(v1, 100_000).groupingBy { it }.eachCount().toSortedMap().values.toList())
        assertEquals(listOf(44723, 25147, 5102, 25028), picks(v2, 100_000).groupingBy { it }.eachCount().toSortedMap().values.toList())
        fun counts(ws: List<Double>) = picks(ws, 100_000).groupingBy { it }.eachCount().toSortedMap().values.toList()
        assertEquals(listOf(24728, 25004, 25240, 25028), counts(cases[2].first))
        assertEquals(listOf(55284, 33586, 11130), counts(cases[3].first))
        assertEquals(listOf(7644, 53594, 38762), counts(cases[4].first))
    }

    /** A legacy `"isInfamous": true` entry weighs exactly what it would without the key (V5 note). */
    @Test
    fun legacyInfamousKeyHasNoWeight() {
        val catalog = com.movieroulettetuesday.app.catalog.CatalogJson.decode(com.movieroulettetuesday.app.catalog.CatalogFixtures.bundledBytes)
        val movies = catalog.movies.take(3)
        val flagged = listOf(movies[0].copy(isInfamous = true)) + movies.drop(1)
        val avgs = mapOf(movies[0].id to 5.0, movies[1].id to 2.0)
        assertEquals(listOf(0.5, 3.5, 2.5), RatingWeightedSelector.weights(flagged, avgs))
        assertEquals(RatingWeightedSelector.weights(movies, avgs), RatingWeightedSelector.weights(flagged, avgs))
    }
}
