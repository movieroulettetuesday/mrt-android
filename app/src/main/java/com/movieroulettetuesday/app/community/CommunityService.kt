package com.movieroulettetuesday.app.community

import com.movieroulettetuesday.app.data.KeyValueStore
import com.movieroulettetuesday.app.catalog.ShowInfo
import com.movieroulettetuesday.app.movies.HalfStars
import com.movieroulettetuesday.app.suggest.InAppSuggestion
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** Observable community state (iOS `CommunityService` @Observable properties). */
data class CommunityState(
    val pollResults: Map<String, PollResults> = emptyMap(),
    val ratingAggregates: Map<String, RatingAggregate> = emptyMap(),
    val busy: Set<String> = emptySet(),
    val errors: Map<String, String> = emptyMap(),
    val myVotes: Map<String, String> = emptyMap(),
    val myRatings: Map<String, Int> = emptyMap(),
)

/**
 * Port of iOS CommunityService. Android has no account: [CommunityStore.canWrite] (relay
 * configured) plays the role of `accountStatus.canWrite`, and the "noAccount" branch never happens.
 * Local submissions use the iOS keys and JSON shape (`{"<id>":{"value":…,"recordName":…}}`).
 */
class CommunityService(
    private val store: CommunityStore,
    private val defaults: KeyValueStore,
    val pollOverrides: PollOverrides? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class Local<V>(val value: V, val recordName: String)

    private val _state = MutableStateFlow(CommunityState())
    val state: StateFlow<CommunityState> = _state.asStateFlow()

    private val votedPolls = loadStrings(VOTED_POLLS_KEY)
    private val listenerRatings = loadInts(LISTENER_HALF_STARS_KEY)
    private val pollCache = TimedCache<String, Map<String, String>>(60_000, clock = clock)
    private val ratingCache = TimedCache<String, Map<String, Int>>(60_000, clock = clock)
    private val backoff = CommunityBackoff(clock)

    /** Bulk-read averages for the rating-weighted jackpot (`community.listenerRatingsSnapshot`). */
    private var snapshot: Map<String, RatingAggregate> = loadSnapshot()
    private var lastBulkStart: Long? = null
    private val ratingFetchedAt = HashMap<String, Long>()

    init {
        _state.update { it.copy(myVotes = votedPolls.mapValues { e -> e.value.value }, myRatings = listenerRatings.mapValues { e -> e.value.value }) }
    }

    val canWrite: Boolean get() = store.canWrite

    // region Polls

    fun poll(episodeGuid: String, show: ShowInfo): PollDefinition = PollFactory.poll(episodeGuid, show.hosts, pollOverrides)

    fun myVote(poll: PollDefinition): String? = votedPolls[poll.id]?.value

    /** Results after you vote, or always when this build can't vote. */
    fun canSeeResults(poll: PollDefinition): Boolean = myVote(poll) != null || !store.canWrite

    suspend fun loadPoll(poll: PollDefinition, force: Boolean = false) {
        if (!canSeeResults(poll)) return
        fetchPollResults(poll, force)
    }

    suspend fun vote(poll: PollDefinition, option: PollOption) {
        if (option !in poll.options || poll.id in _state.value.busy) return
        backoffMessage()?.let { setError(poll.id, it); return }
        if (!store.canWrite) { setError(poll.id, CommunityError.NotConfigured().message); return }
        setBusy(poll.id, true)
        try {
            val name = store.submitVote(poll.id, option.id)
            votedPolls[poll.id] = Local(option.id, name)
            saveStrings(VOTED_POLLS_KEY, votedPolls)
            _state.update { it.copy(myVotes = it.myVotes + (poll.id to option.id)) }
            setError(poll.id, null)
            pollCache.invalidate(poll.id)
        } catch (e: Exception) {
            handle(e)?.let { setError(poll.id, it) }
            return
        } finally {
            setBusy(poll.id, false)
        }
        fetchPollResults(poll, force = true)
    }

    private suspend fun fetchPollResults(poll: PollDefinition, force: Boolean) {
        if (!force) pollCache.value(poll.id)?.let { publish(poll, it); return }
        backoffMessage()?.let { msg ->
            setError(poll.id, msg)
            pollCache.staleValue(poll.id)?.let { publish(poll, it) }
            return
        }
        setBusy(poll.id, true)
        try {
            val votes = store.fetchVotes(poll.id)
            pollCache.insert(poll.id, votes)
            setError(poll.id, null)
            publish(poll, votes)
        } catch (e: Exception) {
            handle(e)?.let { msg ->
                setError(poll.id, msg)
                pollCache.staleValue(poll.id)?.let { publish(poll, it) }
            }
        } finally {
            setBusy(poll.id, false)
        }
    }

    private fun publish(poll: PollDefinition, votes: Map<String, String>) {
        val merged = CommunityMerge.merging(votes, own(votedPolls[poll.id]))
        _state.update { it.copy(pollResults = it.pollResults + (poll.id to PollResults.tally(poll, merged.values))) }
    }

    // endregion

    // region Listener ratings

    fun myListenerRating(movieId: String): Int? = listenerRatings[movieId]?.value

    suspend fun loadRating(movieId: String, force: Boolean = false) {
        if (!force) ratingCache.value(movieId)?.let { publishRating(movieId, it); return }
        backoffMessage()?.let { msg ->
            setError(movieId, msg)
            ratingCache.staleValue(movieId)?.let { publishRating(movieId, it) }
            return
        }
        setBusy(movieId, true)
        try {
            val ratings = store.fetchRatings(movieId)
            ratingCache.insert(movieId, ratings)
            ratingFetchedAt[movieId] = clock()
            setError(movieId, null)
            publishRating(movieId, ratings)
        } catch (e: Exception) {
            handle(e)?.let { msg ->
                setError(movieId, msg)
                ratingCache.staleValue(movieId)?.let { publishRating(movieId, it) }
            }
        } finally {
            setBusy(movieId, false)
        }
    }

    suspend fun submitListenerRating(movieId: String, halfStars: Int) {
        if (halfStars !in HalfStars.range || movieId in _state.value.busy) return
        backoffMessage()?.let { setError(movieId, it); return }
        if (!store.canWrite) { setError(movieId, CommunityError.NotConfigured().message); return }
        setBusy(movieId, true)
        try {
            val name = store.submitRating(movieId, halfStars)
            listenerRatings[movieId] = Local(halfStars, name)
            saveInts(LISTENER_HALF_STARS_KEY, listenerRatings)
            _state.update { it.copy(myRatings = it.myRatings + (movieId to halfStars)) }
            setError(movieId, null)
            ratingCache.invalidate(movieId)
        } catch (e: Exception) {
            handle(e)?.let { setError(movieId, it) }
            return
        } finally {
            setBusy(movieId, false)
        }
        loadRating(movieId, force = true)
    }

    private fun publishRating(movieId: String, ratings: Map<String, Int>) {
        val merged = CommunityMerge.merging(ratings, own(listenerRatings[movieId]))
        _state.update { it.copy(ratingAggregates = it.ratingAggregates + (movieId to RatingAggregate.fromHalfStars(merged.values))) }
    }

    /**
     * Listener averages in stars for the rating-weighted jackpot: the bulk snapshot, overlaid by any
     * movie page loaded since. Missing movies count as unrated in the selector.
     */
    fun knownAverages(): Map<String, Double> {
        val out = HashMap<String, Double>()
        snapshot.forEach { (id, agg) -> agg.average?.let { out[id] = it } }
        _state.value.ratingAggregates.forEach { (id, agg) -> agg.average?.let { out[id] = it } ?: out.remove(id) }
        return out
    }

    /**
     * Bulk read (iOS 56606a6): `ListenerRating WHERE movieId IN` chunks of ≤ 100 catalog ids, at launch
     * once the catalog loads and after an episode refresh; at most once every 5 minutes and never
     * during a back-off. Failures are silent (previous values kept). Per-movie data fetched after
     * the bulk read started is not overwritten; this device's own rating is merged by record name.
     */
    suspend fun refreshListenerAverages(movieIds: List<String>, force: Boolean = false) {
        val ids = movieIds.distinct()
        if (ids.isEmpty() || backoff.secondsRemaining() != null) return
        val started = clock()
        lastBulkStart?.let { if (!force && started - it < BULK_INTERVAL_MS) return }
        lastBulkStart = started
        val fetched = HashMap<String, Map<String, Int>>()
        try {
            for (chunk in ids.chunked(WebCommunityStore.BULK_CHUNK)) fetched += store.fetchRatings(chunk)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            backoff.note(e)
            return
        }
        val next = snapshot.toMutableMap()
        for (id in ids) {
            if ((ratingFetchedAt[id] ?: Long.MIN_VALUE) >= started) continue
            val merged = CommunityMerge.merging(fetched[id].orEmpty(), own(listenerRatings[id]))
            next[id] = RatingAggregate.fromHalfStars(merged.values)
        }
        snapshot = next
        saveSnapshot(next, started)
    }

    private fun loadSnapshot(): Map<String, RatingAggregate> {
        val root = loadObject(SNAPSHOT_KEY) ?: return emptyMap()
        if ((root["version"] as? JsonPrimitive)?.intOrNull != 1) return emptyMap()
        val movies = root["movies"] as? JsonObject ?: return emptyMap()
        val out = HashMap<String, RatingAggregate>()
        for ((id, e) in movies) {
            val o = e as? JsonObject ?: continue
            val count = (o["count"] as? JsonPrimitive)?.intOrNull ?: continue
            val avg = (o["average"] as? JsonPrimitive)?.takeUnless { it is kotlinx.serialization.json.JsonNull }?.content?.toDoubleOrNull()
            val hist = (o["histogram"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }
            if (count < 0 || hist == null || hist.size != 5 || (avg != null && !avg.isFinite())) continue
            out[id] = RatingAggregate(count, avg, hist)
        }
        return out
    }

    private fun saveSnapshot(map: Map<String, RatingAggregate>, at: Long) = defaults.putString(SNAPSHOT_KEY, buildJsonObject {
        put("version", 1)
        put("updatedAt", at / 1000.0) // seconds since 1970 (Android-only storage)
        put("movies", buildJsonObject {
            map.forEach { (id, a) ->
                put(id, buildJsonObject {
                    put("count", a.count)
                    put("average", a.average?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull)
                    put("histogram", kotlinx.serialization.json.JsonArray(a.histogram.map(::JsonPrimitive)))
                })
            }
        })
    }.toString())

    // endregion

    /** In-app suggestion (backup to email). Returns a user-facing problem, or null when sent. */
    suspend fun submitSuggestion(suggestion: InAppSuggestion, appVersion: String): String? {
        if (!suggestion.isValid) return "Add a movie title (note up to ${InAppSuggestion.NOTE_LIMIT} characters)."
        if (!store.canWrite) return "In-app suggestions aren’t available right now. Use email instead."
        return try {
            store.submitSuggestion(suggestion, appVersion)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: CommunityError) {
            e.message
        } catch (e: Exception) {
            "Couldn’t send. Try email instead."
        }
    }

    private fun handle(e: Exception): String? {
        if (e is CancellationException) throw e
        backoff.note(e)
        return (e as? CommunityError)?.message ?: "Something went wrong. Try again later."
    }

    private fun backoffMessage(): String? = backoff.secondsRemaining()?.let { CommunityError.Busy(it).message }

    private fun setBusy(id: String, on: Boolean) = _state.update { it.copy(busy = if (on) it.busy + id else it.busy - id) }
    private fun setError(id: String, message: String?) =
        _state.update { it.copy(errors = if (message == null) it.errors - id else it.errors + (id to message)) }

    private fun <V> own(local: Local<V>?): Pair<String, V>? = local?.takeIf { it.recordName.isNotEmpty() }?.let { it.recordName to it.value }

    // region persistence (same keys / JSON as iOS)

    private fun loadObject(key: String): JsonObject? =
        defaults.getString(key)?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }

    private fun loadStrings(key: String): MutableMap<String, Local<String>> {
        val out = LinkedHashMap<String, Local<String>>()
        loadObject(key)?.forEach { (id, e) ->
            val o = e as? JsonObject ?: return@forEach
            val v = (o["value"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@forEach
            out[id] = Local(v, (o["recordName"] as? JsonPrimitive)?.content.orEmpty())
        }
        return out
    }

    private fun loadInts(key: String): MutableMap<String, Local<Int>> {
        val out = LinkedHashMap<String, Local<Int>>()
        loadObject(key)?.forEach { (id, e) ->
            val o = e as? JsonObject ?: return@forEach
            val v = (o["value"] as? JsonPrimitive)?.intOrNull?.let(HalfStars::clamped) ?: return@forEach
            out[id] = Local(v, (o["recordName"] as? JsonPrimitive)?.content.orEmpty())
        }
        return out
    }

    private fun saveStrings(key: String, map: Map<String, Local<String>>) = defaults.putString(key, buildJsonObject {
        map.forEach { (id, l) -> put(id, buildJsonObject { put("value", l.value); put("recordName", l.recordName) }) }
    }.toString())

    private fun saveInts(key: String, map: Map<String, Local<Int>>) = defaults.putString(key, buildJsonObject {
        map.forEach { (id, l) -> put(id, buildJsonObject { put("value", l.value); put("recordName", l.recordName) }) }
    }.toString())

    // endregion

    companion object {
        const val VOTED_POLLS_KEY = "community.votedPolls"
        const val LISTENER_HALF_STARS_KEY = "community.listenerHalfStars"
        const val SNAPSHOT_KEY = "community.listenerRatingsSnapshot"
        const val BULK_INTERVAL_MS = 5 * 60 * 1000L
    }
}
