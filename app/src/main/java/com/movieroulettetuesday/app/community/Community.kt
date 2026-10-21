package com.movieroulettetuesday.app.community

import com.movieroulettetuesday.app.catalog.StableHash
import com.movieroulettetuesday.app.movies.HalfStars
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** Port of RouletteCore/Community.swift (pure parts). */
object Sha256 {
    fun hex(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

/**
 * Deterministic CloudKit record names (iOS `CommunityRecordNames`): one record per voter per poll
 * or movie, so a re-vote overwrites. iOS hashes the iCloud user record name; Android hashes its
 * random install id the same way (a UUID never equals a CloudKit user record name, which starts
 * with "_", so the two can't collide). Only the hash leaves the device.
 */
object CommunityRecordNames {
    const val VOTE_RECORD_TYPE = "PollVote"
    const val RATING_RECORD_TYPE = "ListenerRating"
    const val SUGGESTION_RECORD_TYPE = "Suggestion"
    private const val ALLOWED = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789._-"

    /** sha256(voter + pollId), the voter's dedupe key for a poll. */
    fun voteKey(pollId: String, voter: String) = Sha256.hex(voter + pollId)

    /** sha256(voter + "rating:" + movieId). */
    fun ratingKey(movieId: String, voter: String) = Sha256.hex(voter + "rating:" + movieId)

    fun voteName(pollId: String, key: String) = "vote-${safeComponent(pollId)}-$key"
    fun ratingName(movieId: String, key: String) = "rating-${safeComponent(movieId)}-$key"

    /** `vote-<pollId>-<sha256(voter + pollId)>` */
    fun vote(pollId: String, voter: String) = voteName(pollId, voteKey(pollId, voter))

    /** `rating-<movieId>-<sha256(voter + "rating:" + movieId)>` */
    fun rating(movieId: String, voter: String) = ratingName(movieId, ratingKey(movieId, voter))

    /** ASCII [A-Za-z0-9._-], 1..120 chars passes; anything else becomes "h" + 32 hex of its sha256. */
    fun safeComponent(id: String): String {
        if (id.isNotEmpty() && id.length <= 120 && id.all { it in ALLOWED }) return id
        return "h" + Sha256.hex(id).take(32)
    }
}

data class PollOption(val id: String, val label: String)

data class PollDefinition(val id: String, val episodeGuid: String, val question: String, val options: List<PollOption>)

/** Bundled `polls.json` per-episode overrides (iOS `PollOverrides`). */
data class PollOverrides(val polls: List<Entry> = emptyList()) {
    data class Entry(val episodeGuid: String, val question: String, val options: List<String>, val id: String? = null)

    fun entry(episodeGuid: String): Entry? = polls.firstOrNull { it.episodeGuid == episodeGuid && it.options.size >= 2 && it.question.isNotEmpty() }

    companion object {
        /** Strict like `JSONDecoder`: a malformed file yields null (no overrides). */
        fun decode(text: String): PollOverrides? = runCatching {
            val root = Json.parseToJsonElement(text).jsonObject
            val polls = (root["polls"] as JsonArray).map { e ->
                val o = e.jsonObject
                fun str(k: String) = (o[k] as JsonPrimitive).also { require(it.isString) }.content
                Entry(
                    episodeGuid = str("episodeGuid"),
                    question = str("question"),
                    options = (o["options"] as JsonArray).map { (it as JsonPrimitive).also { p -> require(p.isString) }.content },
                    id = (o["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
                )
            }
            PollOverrides(polls)
        }.getOrNull()
    }
}

/** No admin backend: every episode gets one deterministic default poll unless overridden. */
object PollFactory {
    enum class Kind { GoodOrBad, Recommend, Argument }

    fun defaultKind(episodeGuid: String): Kind = Kind.entries[StableHash.index("poll:$episodeGuid", Kind.entries.size)]

    fun defaultPoll(episodeGuid: String, hosts: List<String>): PollDefinition {
        val base = CommunityRecordNames.safeComponent(episodeGuid)
        return when (defaultKind(episodeGuid)) {
            Kind.GoodOrBad -> PollDefinition("$base.good-bad", episodeGuid, "Was this movie good or bad?", listOf(PollOption("good", "Good"), PollOption("bad", "Bad")))
            Kind.Recommend -> PollDefinition("$base.recommend", episodeGuid, "Would you recommend it?", listOf(PollOption("yes", "Yes"), PollOption("no", "No")))
            Kind.Argument -> {
                // Swift trims .whitespaces (not newlines) here.
                val names = hosts.map { it.trim(' ', '\t') }.filter { it.isNotEmpty() }
                val hostOptions = if (names.size >= 2) names.mapIndexed { i, n -> PollOption("host-${i + 1}", n) }
                else listOf(PollOption("host-1", "Host A"), PollOption("host-2", "Host B"))
                PollDefinition("$base.argument", episodeGuid, "Who won the argument?", hostOptions + PollOption("tie", "Tie"))
            }
        }
    }

    fun poll(episodeGuid: String, hosts: List<String>, overrides: PollOverrides?): PollDefinition {
        val entry = overrides?.entry(episodeGuid) ?: return defaultPoll(episodeGuid, hosts)
        val id = entry.id?.let(CommunityRecordNames::safeComponent)
            ?: "${CommunityRecordNames.safeComponent(episodeGuid)}.custom-${Sha256.hex(entry.question).take(8)}"
        return PollDefinition(id, episodeGuid, entry.question, entry.options.mapIndexed { i, l -> PollOption("opt-${i + 1}", l) })
    }
}

data class PollResults(val pollId: String, val rows: List<Row>, val total: Int) {
    data class Row(val option: PollOption, val count: Int, val fraction: Double)

    val leaders: List<PollOption>
        get() {
            val top = rows.maxOfOrNull { it.count } ?: return emptyList()
            return if (top > 0) rows.filter { it.count == top }.map { it.option } else emptyList()
        }

    companion object {
        /** Poll order; votes for unknown options are ignored. */
        fun tally(poll: PollDefinition, votes: Iterable<String>): PollResults {
            val valid = poll.options.map { it.id }.toSet()
            val counts = votes.filter { it in valid }.groupingBy { it }.eachCount()
            val total = counts.values.sum()
            return PollResults(poll.id, poll.options.map { o ->
                val n = counts[o.id] ?: 0
                Row(o, n, if (total == 0) 0.0 else n.toDouble() / total)
            }, total)
        }
    }
}

data class RatingAggregate(val count: Int, val average: Double?, val histogram: List<Int>) {
    val averageToHalf: Double? get() = average?.let(HalfStars::roundedToHalf)
    val averageHalfStars: Int? get() = averageToHalf?.let { HalfStars.clamped(Math.round(it * 2).toInt()) }

    companion object {
        fun fromStars(stars: Iterable<Int>): RatingAggregate = fromHalfStars(stars.filter { it in 1..5 }.map { it * 2 })

        fun fromHalfStars(halfStars: Iterable<Int>): RatingAggregate {
            val valid = halfStars.filter { it in HalfStars.range }
            val histogram = MutableList(5) { 0 }
            valid.forEach { histogram[HalfStars.roundedStars(it) - 1]++ }
            return RatingAggregate(valid.size, if (valid.isEmpty()) null else valid.sum() / 2.0 / valid.size, histogram)
        }
    }
}

/** Short-lived cache so screen switches don't refetch every time (iOS `TimedCache`). */
class TimedCache<K, V>(private val ttlMs: Long, maxEntries: Int = 200, private val clock: () -> Long = System::currentTimeMillis) {
    private val max = maxOf(1, maxEntries)
    private val storage = LinkedHashMap<K, Pair<V, Long>>()

    val size: Int get() = storage.size

    @Synchronized fun value(key: K): V? = storage[key]?.takeIf { clock() - it.second < ttlMs }?.first
    @Synchronized fun staleValue(key: K): V? = storage[key]?.first
    @Synchronized fun insert(key: K, value: V) {
        storage.remove(key)
        storage[key] = value to clock()
        while (storage.size > max) storage.remove(storage.entries.minByOrNull { it.value.second }!!.key)
    }
    @Synchronized fun invalidate(key: K) { storage.remove(key) }
}

/** Friendly failures (iOS `CommunityError`, wording adapted where iOS names iCloud). */
sealed class CommunityError(message: String) : Exception(message) {
    class NotConfigured : CommunityError("Voting isn’t available in this version of the app.")
    class InvalidOption : CommunityError("Something went wrong with that vote. Try again.")
    class InvalidStars : CommunityError("Ratings are ½ to 5 stars.")
    class Offline : CommunityError("You’re offline. Listener results will load when you’re back online.")
    class Busy(val retryAfter: Int?) : CommunityError(
        if (retryAfter != null && retryAfter > 0) "The listener service is busy right now. Try again in $retryAfter second${if (retryAfter == 1) "" else "s"}."
        else "The listener service is busy right now. Try again in a moment.",
    )
    class QuotaExceeded : CommunityError("Listener features are temporarily unavailable. Please try again later.")
    class NotSetUp : CommunityError("Listener polls and ratings aren’t available yet. Check back later.")
    class Service(message: String) : CommunityError(message)

    /** Minimum wait before calling the service again. */
    val backoffSeconds: Long?
        get() = when (this) {
            is Busy -> (retryAfter ?: 10).coerceIn(1, 300).toLong()
            is QuotaExceeded -> 60
            else -> null
        }
}

/** After rate limiting, show cached results instead of calling the service (no retry loops). */
class CommunityBackoff(private val clock: () -> Long = System::currentTimeMillis) {
    var blockedUntil: Long? = null
        private set

    fun note(error: Throwable) {
        val seconds = (error as? CommunityError)?.backoffSeconds ?: return
        val until = clock() + seconds * 1000
        val current = blockedUntil
        if (current != null && current > until) return
        blockedUntil = until
    }

    fun secondsRemaining(): Int? {
        val until = blockedUntil ?: return null
        val left = until - clock()
        return if (left > 0) ((left + 999) / 1000).toInt() else null
    }

    fun reset() { blockedUntil = null }
}

/** Merge this device's just-saved record (public-DB queries are eventually consistent). */
object CommunityMerge {
    fun <V> merging(fetched: Map<String, V>, own: Pair<String, V>?): Map<String, V> =
        if (own == null) fetched else fetched + own
}
