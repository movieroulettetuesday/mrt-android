package com.movieroulettetuesday.app.shared

import com.movieroulettetuesday.app.catalog.CatalogRepository
import com.movieroulettetuesday.app.catalog.MovieCatalog
import com.movieroulettetuesday.app.roulette.InfamousSelection
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
            val averages = c.obj("listenerAverages").mapValues { num(it.value)!! }
            val r = CountingSwiftRandom(c.seed())
            val (movie, credited) = RareOutcomeRoller.jackpotPick(catalog, landed, averages, r)!!
            val tag = "seed ${c.seed()} averages $averages"
            assertEquals(tag, c.str("movieId"), movie.id)
            assertEquals(tag, c.str("categoryId"), credited.id)
            assertEquals(tag, c.int("rngDraws"), r.draws)
        }
    }

    /** Android has no Siri/Shortcuts path; this checks the same building blocks compose identically. */
    @Test
    fun quickSpinComposition() {
        for (c in v.obj("quickSpin").arr("cases").objs()) {
            val catalog = SharedVectors.catalog(c["catalog"]!!)
            val geometry = WheelGeometry.forCatalog(catalog)
            val jackpot = RatingWeightedSelector() // Siri/widget spins have no averages in the vectors
            val r = SwiftCompatRandom(c.seed())
            var allowRespin = true
            var wasForced = false
            while (true) {
                val target = geometry.randomTargetRotation(0.0, r, fullTurns = 1..1)
                val landed = catalog.categories[geometry.segmentIndex(target)]
                val outcome = RareOutcomeRoller.roll(RareOutcomePolicy(), jackpot.isAvailable(catalog), allowRespin, r)
                if (outcome == RareOutcome.ForcedRespin) { allowRespin = false; wasForced = true; continue }
                val jackpot = if (outcome == RareOutcome.InfamousJackpot) jackpot.pick(catalog, landed, r) else null
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

    /** selection.json#infamousSelection (iOS 1.0.1 InfamousSelection): V1-V5 + clamp/non-finite. */
    @Test
    fun infamousSelectionVectors() {
        val iv = v.obj("infamousSelection")
        assertEquals(iv.dbl("unratedWeight")!!, InfamousSelection.UNRATED_WEIGHT, 0.0)
        assertEquals(iv.dbl("maxWeight")!!, InfamousSelection.weight(InfamousSelection.MIN_STARS), 0.0)
        val cases = iv.arr("cases").objs()
        assertEquals(listOf("V1", "V2", "V3", "V4", "V5", "clamp-and-nonfinite"), cases.map { it.str("name") })
        for (c in cases) {
            val tag = c.str("name")!!
            val stars = c["averageStars"]!!.jsonArray().map { num(it) }
            val movies = stars.indices.map { com.movieroulettetuesday.app.catalog.Movie(id = "m$it", title = "M$it", year = 2000, genre = "Drama") }
            val averages = movies.zip(stars).mapNotNull { (m, a) -> a?.let { m.id to it } }.toMap()
            val weights = InfamousSelection.weights(movies, averages)
            assertEquals(tag, c["weights"]!!.doubles(), weights)
            c["probabilities"]!!.doubles().zip(InfamousSelection.probabilities(movies, averages)).forEach { (e, g) -> assertClose("$tag p", e, g) }
            for (u in c.arr("unitToIndex").objs()) {
                assertEquals("$tag @ ${u.dbl("unit")}", u.int("index"), InfamousSelection.pickIndex(weights, u.dbl("unit")!!))
            }
            val r = SwiftCompatRandom(42)
            val picks = List(100_000) { movies.indexOf(InfamousSelection.pick(movies, averages, r)!!) }
            assertEquals("$tag first 10", c["seed42First10"]!!.doubles().map { it.toInt() }, picks.take(10))
            val counts = IntArray(movies.size).also { a -> picks.forEach { a[it]++ } }.toList()
            assertEquals("$tag counts", c["seed42Counts100k"]!!.doubles().map { it.toInt() }, counts)
        }
        assertEquals(null, InfamousSelection.pick(emptyList(), emptyMap(), SwiftCompatRandom(1)))
    }

    /**
     * iOS 1.0.1: a legacy `"isInfamous": true` key is ignored. The fixtureInfamous catalog (legacy keys on
     * d-2 and b-1) decodes to exactly the plain fixture and weighs the same in normal and jackpot spins.
     */
    @Test
    fun legacyInfamousKeyHasNoWeight() {
        val plain = SharedVectors.catalog(kotlinx.serialization.json.JsonPrimitive("fixture"))
        val legacy = SharedVectors.catalog(kotlinx.serialization.json.JsonPrimitive("fixtureInfamous"))
        assertTrue(v.obj("fixtures").obj("fixtureInfamous").toString().contains("\"isInfamous\":true"))
        assertEquals(plain, legacy)
        val hist = history(listOf("a-1"))
        assertEquals(plain.movies.map { selector.weight(it, hist) }, legacy.movies.map { selector.weight(it, hist) })
        assertTrue(plain.movies.all { selector.weight(it, emptyList()) == 1.0 }) // no x1.25 boost
        val avgs = mapOf("d-2" to 5.0, "b-1" to 2.0)
        assertEquals(InfamousSelection.weights(plain.movies, avgs), InfamousSelection.weights(legacy.movies, avgs))
    }

    /** Counts unit doubles drawn (one Swift `next()` each), for `rngDraws`. */
    private class CountingSwiftRandom(seed: Long) : SwiftCompatRandom(seed) {
        var draws = 0
        override fun nextDouble(): Double { draws++; return super.nextDouble() }
    }
}
