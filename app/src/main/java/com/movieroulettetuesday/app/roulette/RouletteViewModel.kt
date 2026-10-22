package com.movieroulettetuesday.app.roulette

import androidx.lifecycle.ViewModel
import com.movieroulettetuesday.app.catalog.CatalogRepository
import com.movieroulettetuesday.app.catalog.Movie
import com.movieroulettetuesday.app.catalog.MovieCatalog
import com.movieroulettetuesday.app.catalog.RouletteCategory
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * State and actions for the Roulette tab (port of Roulette/RouletteViewModel.swift).
 * The wheel lands on a category purely from its final angle; the movie is then picked from that
 * category with [MovieSelector]. The UI animates [RouletteState.spin] and calls [spinAnimationEnded];
 * a watchdog finishes the spin anyway if the animation never reports back (e.g. wheel off-screen).
 */
class RouletteViewModel(
    private val catalogState: StateFlow<CatalogRepository.State>,
    private val history: SpinHistoryStore,
    private val scope: CoroutineScope,
    private val random: Random = Random.Default,
    private val selector: MovieSelector = MovieSelector(),
    private val rarePolicy: RareOutcomePolicy = RareOutcomePolicy(),
    private val reduceMotion: () -> Boolean = { false },
    /** Spin sound (wheel-spin.m4a); haptics are driven by the wheel's frame loop from [SpinRun]. */
    private val feedback: SpinFeedback = SpinFeedback.None,
    private val soundEnabled: () -> Boolean = { false },
    /** Monotonic seconds, for tap-to-stop timing. */
    private val now: () -> Double = { System.nanoTime() / 1e9 },
    /** Which movie an Infamous Jackpot picks (iOS 1.0.1: every movie, weighted by listener average). */
    private val jackpot: JackpotSelector = RatingWeightedSelector(),
    /** Each recorded spin (movie, credited category, epoch ms): the home-screen widget's "last spin". */
    private val onSpinRecorded: (Movie, RouletteCategory, Long) -> Unit = { _, _, _ -> },
) : ViewModel(scope) {

    data class Reveal(
        val id: String,
        val movie: Movie,
        val category: RouletteCategory,
        /** Spins of this movie on this device, including this one. */
        val spinCount: Int,
        val isJackpot: Boolean = false,
    )

    /**
     * One spin's animation: rotate from [from] to [to] degrees following [motion] (click-locked to the
     * spin sound). After a tap-to-stop, [stop] takes over from its start time and lands on its target.
     */
    data class SpinRun(
        val token: Int,
        val from: Double,
        val to: Double,
        val motion: SpinMotion,
        val startedAt: Double = 0.0,
        val stop: SpinStopPlan? = null,
    ) {
        val durationMs: Int get() = motion.durationMs
        /** Animation-local seconds at which the wheel comes to rest. */
        val endTime: Double get() = stop?.endTime ?: motion.duration
        val finalRotation: Double get() = stop?.newTarget ?: to

        /** Angle drawn at animation time [t] (seconds). */
        fun angleAt(t: Double): Double {
            val st = stop
            return if (st != null && t >= st.startTime) st.displayedAngle(t) else from + (to - from) * motion.progress(t)
        }

        /** Haptic tick times (s from spin start): crossings thinned to >= 45 ms, re-planned after a stop. */
        fun hapticTimes(geometry: WheelGeometry): List<Double> {
            val st = stop ?: return SpinTicks.thinned(motion.crossingTimes, HAPTIC_MIN_INTERVAL)
            val before = motion.crossingTimes.filter { it < st.startTime }
            val after = st.crossingTimes(geometry).map { it + st.startTime }
            return SpinTicks.thinned(before + after, HAPTIC_MIN_INTERVAL)
        }
    }

    data class RouletteState(
        val load: CatalogRepository.State = CatalogRepository.State.Loading,
        val catalog: MovieCatalog? = null,
        val geometry: WheelGeometry = WheelGeometry.uniform(1),
        /** Settled rotation (degrees, normalised to [0, 360) at each spin start). */
        val rotation: Double = 0.0,
        val spin: SpinRun? = null,
        val reveal: Reveal? = null,
        val lastCategory: RouletteCategory? = null,
        val notice: String? = null,
        val rareBanner: String? = null,
        val isAwaitingForcedRespin: Boolean = false,
    ) {
        val isSpinning: Boolean get() = spin != null
        /** Tap-to-stop is available: spinning and not already stopping. */
        val canStop: Boolean get() = spin != null && spin.stop == null
        val canSpin: Boolean get() = catalog != null && spin == null && reveal == null && !isAwaitingForcedRespin
    }

    private val _state = MutableStateFlow(RouletteState())
    val state: StateFlow<RouletteState> = _state.asStateFlow()

    private var token = 0
    private var watchdog: Job? = null
    private var forcedRespin: Job? = null
    private var pendingCatalog: MovieCatalog? = null
    private var spinAgainRequested = false
    private val wasForced = HashMap<Int, Boolean>()

    init {
        scope.launch { catalogState.collect(::onCatalogState) }
    }

    private fun onCatalogState(load: CatalogRepository.State) {
        val s = _state.value
        val fresh = (load as? CatalogRepository.State.Loaded)?.catalog
        if (fresh == null) {
            if (s.catalog == null) _state.update { it.copy(load = load) }
            return
        }
        // A refreshed list that arrives mid-spin or while a reveal is up is applied when idle.
        if (s.catalog == null || isIdle(s)) apply(fresh) else pendingCatalog = fresh
    }

    private fun isIdle(s: RouletteState) = s.spin == null && s.reveal == null && !s.isAwaitingForcedRespin

    private fun apply(catalog: MovieCatalog) {
        _state.update {
            it.copy(
                load = CatalogRepository.State.Loaded(catalog),
                catalog = catalog,
                geometry = WheelGeometry.forCatalog(catalog),
            )
        }
    }

    private fun applyPendingIfIdle() {
        val pending = pendingCatalog ?: return
        if (!isIdle(_state.value)) return
        pendingCatalog = null
        if (pending != _state.value.catalog) apply(pending)
    }

    fun spin() = startSpin(isForcedRespin = false)

    private fun startSpin(isForcedRespin: Boolean) {
        if (isForcedRespin) _state.update { it.copy(isAwaitingForcedRespin = false, rareBanner = null) }
        applyPendingIfIdle()
        val s = _state.value
        if (!s.canSpin) return
        token += 1
        val myToken = token
        // Normalise so the animated value stays small (Float precision); the landing math is unchanged.
        val start = ((s.rotation % 360.0) + 360.0) % 360.0
        val turns = if (reduceMotion()) REDUCED_MOTION_TURNS else FULL_TURNS
        val target = s.geometry.randomTargetRotation(start, random, fullTurns = turns)
        // Timing (not outcome) is locked to the recorded clip.
        val motion = SpinMotion.plan(start, target, s.geometry)
        val run = SpinRun(myToken, start, target, motion, startedAt = now())
        feedback.stopClip(0.0) // a previous clip never overlaps a new spin
        if (soundEnabled()) feedback.startClip()
        _state.update {
            it.copy(rotation = start, spin = run, notice = if (isForcedRespin) "Re-spin forced! Here we go again…" else null)
        }
        watchdog?.cancel()
        watchdog = scope.launch {
            delay(run.durationMs + WATCHDOG_GRACE_MS)
            finishSpin(myToken, isForcedRespin)
        }
        wasForced[myToken] = isForcedRespin
    }

    /**
     * Tap to stop (iOS `stopEarly`): a new random landing at least 120° ahead, reached over 0.8 s
     * easeOut; the sound fades over the same time. The result is still recorded exactly once.
     */
    fun stopEarly() {
        val s = _state.value
        val run = s.spin ?: return
        if (run.stop != null) return
        val elapsed = maxOf(0.0, now() - run.startedAt - STOP_LEAD)
        val plan = SpinStopPlan.make(run.motion, run.from, run.to, elapsed, s.geometry, random) ?: return
        _state.update { it.copy(spin = run.copy(stop = plan)) }
        feedback.stopClip(SpinStopPlan.DURATION)
        val remainingMs = (maxOf(0.0, plan.endTime - (now() - run.startedAt)) * 1000).toLong()
        val forced = wasForced[run.token] ?: false
        watchdog?.cancel()
        watchdog = scope.launch {
            delay(remainingMs + WATCHDOG_GRACE_MS)
            finishSpin(run.token, forced)
        }
    }

    /** View gone / app backgrounded: stop the sound; the spin result is unaffected. */
    fun stopFeedback() = feedback.stopClip(SpinStopPlan.DURATION)

    /** Called by the UI when the spin animation for [token] has finished. */
    fun spinAnimationEnded(token: Int) {
        finishSpin(token, wasForced[token] ?: false)
    }

    private fun finishSpin(token: Int, wasForcedRespin: Boolean) {
        val s = _state.value
        val run = s.spin ?: return
        if (run.token != token) return
        watchdog?.cancel()
        watchdog = null
        wasForced.remove(token)
        val rotation = run.finalRotation
        _state.update { it.copy(spin = null, rotation = rotation, rareBanner = null) }
        val catalog = s.catalog ?: return
        val index = s.geometry.segmentIndex(rotation)
        val landed = catalog.categories.getOrNull(index) ?: return
        _state.update { it.copy(lastCategory = landed) }

        val outcome = RareOutcomeRoller.roll(
            rarePolicy,
            infamousAvailable = jackpot.isAvailable(catalog),
            allowRespin = !wasForcedRespin,
            random = random,
        )
        if (outcome == RareOutcome.ForcedRespin) {
            _state.update {
                it.copy(
                    notice = "Re-spin forced! The wheel demands a do-over.",
                    rareBanner = "RE-SPIN FORCED!",
                    isAwaitingForcedRespin = true,
                )
            }
            forcedRespin?.cancel()
            forcedRespin = scope.launch {
                delay(FORCED_RESPIN_DELAY_MS)
                forcedRespin = null
                startSpin(isForcedRespin = true)
            }
            return
        }

        var category = landed
        var isJackpot = false
        val movie: Movie
        val jackpot = if (outcome == RareOutcome.InfamousJackpot) jackpot.pick(catalog, landed, random) else null
        if (jackpot != null) {
            movie = jackpot.first
            category = jackpot.second
            isJackpot = true
        } else {
            val picked = selector.selectMovie(landed, catalog, history.results, random)
            if (picked == null) {
                _state.update { it.copy(notice = "No movies in ${landed.name} yet. Spin again!") }
                return
            }
            movie = picked
        }
        val result = SpinResult(movieId = movie.id, categoryId = category.id)
        history.record(result)
        runCatching { onSpinRecorded(movie, category, result.timestampEpochMs) }
        _state.update {
            it.copy(
                notice = if (isJackpot) "Infamous Jackpot!" else null,
                reveal = Reveal(result.id, movie, category, history.spinCount(movie.id), isJackpot),
            )
        }
    }

    /** From the reveal sheet: dismiss, then spin once the sheet is gone. */
    fun spinAgain() {
        spinAgainRequested = true
        dismissReveal()
    }

    fun dismissReveal() {
        if (_state.value.reveal == null) return
        _state.update { it.copy(reveal = null) }
        applyPendingIfIdle()
        if (spinAgainRequested) {
            spinAgainRequested = false
            spin()
        }
    }

    companion object {
        val FULL_TURNS = 5..8
        val REDUCED_MOTION_TURNS = 1..2
        const val FORCED_RESPIN_DELAY_MS = 1_600L
        const val WATCHDOG_GRACE_MS = 1_500L
        /** Lead so the next frame is past the stop plan's start (no one-frame jump). */
        const val STOP_LEAD = 0.03
        /** Selection haptics closer together than this are skipped. */
        const val HAPTIC_MIN_INTERVAL = 0.045
    }
}
