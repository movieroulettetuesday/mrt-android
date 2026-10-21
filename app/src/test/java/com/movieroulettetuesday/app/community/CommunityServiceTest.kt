package com.movieroulettetuesday.app.community

import com.movieroulettetuesday.app.catalog.ShowInfo
import com.movieroulettetuesday.app.data.InMemoryKeyValueStore
import com.movieroulettetuesday.app.suggest.InAppSuggestion
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeCommunityStore(override var canWrite: Boolean = true, private val voter: String = "install-1") : CommunityStore {
    val votes = mutableMapOf<String, MutableMap<String, String>>()
    val ratings = mutableMapOf<String, MutableMap<String, Int>>()
    val suggestions = mutableListOf<InAppSuggestion>()
    var fail: Exception? = null
    var fetches = 0
    /** Simulates public-DB eventual consistency: saved records don't show in queries. */
    var hideOwnWrites = false

    override suspend fun fetchVotes(pollId: String): Map<String, String> { fetches++; fail?.let { throw it }; return votes[pollId].orEmpty().toMap() }
    override suspend fun submitVote(pollId: String, option: String): String {
        fail?.let { throw it }
        val name = CommunityRecordNames.vote(pollId, voter)
        if (!hideOwnWrites) votes.getOrPut(pollId) { mutableMapOf() }[name] = option
        return name
    }
    override suspend fun fetchRatings(movieId: String): Map<String, Int> { fetches++; fail?.let { throw it }; return ratings[movieId].orEmpty().toMap() }
    override suspend fun submitRating(movieId: String, halfStars: Int): String {
        fail?.let { throw it }
        val name = CommunityRecordNames.rating(movieId, voter)
        if (!hideOwnWrites) ratings.getOrPut(movieId) { mutableMapOf() }[name] = halfStars
        return name
    }
    override suspend fun submitSuggestion(suggestion: InAppSuggestion, appVersion: String) { fail?.let { throw it }; suggestions += suggestion }
}

class CommunityServiceTest {
    private val defaults = InMemoryKeyValueStore()
    private var now = 0L
    private val poll = PollFactory.defaultPoll("Buzzsprout-19877460", ShowInfo.MRT.hosts) // good/bad

    @Test
    fun resultsHiddenUntilYouVoteThenTallyAndPersist() = runBlocking {
        val store = FakeCommunityStore().apply { votes[poll.id] = mutableMapOf("vote-x" to "bad", "vote-y" to "bad") }
        val s = CommunityService(store, defaults, clock = { now })
        assertFalse(s.canSeeResults(poll))
        s.loadPoll(poll)
        assertEquals(0, store.fetches) // no peeking before voting
        s.vote(poll, poll.options[0])
        val r = s.state.value.pollResults[poll.id]!!
        assertEquals(3, r.total)
        assertEquals(listOf(1, 2), r.rows.map { it.count })
        assertEquals("good", s.myVote(poll))
        assertTrue(defaults.getString(CommunityService.VOTED_POLLS_KEY)!!.contains("\"value\":\"good\""))
        // Same keys / shape after a restart.
        val again = CommunityService(store, defaults)
        assertEquals("good", again.myVote(poll))
        assertTrue(again.canSeeResults(poll))
    }

    @Test
    fun ownRecordIsMergedWhenQueriesLag() = runBlocking {
        val store = FakeCommunityStore().apply { hideOwnWrites = true; votes[poll.id] = mutableMapOf("vote-x" to "bad") }
        val s = CommunityService(store, defaults)
        s.vote(poll, poll.options[0])
        assertEquals(2, s.state.value.pollResults[poll.id]!!.total)
        s.submitListenerRating("m", 8)
        assertEquals(1, s.state.value.ratingAggregates["m"]!!.count)
        assertEquals(8, s.myListenerRating("m"))
    }

    @Test
    fun readOnlyBuildShowsResultsAndRefusesWrites() = runBlocking {
        val store = FakeCommunityStore(canWrite = false).apply { votes[poll.id] = mutableMapOf("vote-x" to "good") }
        val s = CommunityService(store, defaults)
        assertTrue(s.canSeeResults(poll))
        s.loadPoll(poll)
        assertEquals(1, s.state.value.pollResults[poll.id]!!.total)
        s.vote(poll, poll.options[1])
        assertEquals(CommunityError.NotConfigured().message, s.state.value.errors[poll.id])
        assertNull(s.myVote(poll))
        assertEquals("In-app suggestions aren’t available right now. Use email instead.", s.submitSuggestion(InAppSuggestion("Tremors"), "1"))
    }

    @Test
    fun busyBacksOffAndServesStaleCache() = runBlocking {
        val store = FakeCommunityStore().apply { ratings["m"] = mutableMapOf("r1" to 6) }
        val s = CommunityService(store, defaults, clock = { now })
        s.loadRating("m")
        assertEquals(1, store.fetches)
        s.loadRating("m") // fresh cache: no request
        assertEquals(1, store.fetches)
        now = 61_000
        store.fail = CommunityError.Busy(20)
        s.loadRating("m")
        assertEquals("The listener service is busy right now. Try again in 20 seconds.", s.state.value.errors["m"])
        assertEquals(1, s.state.value.ratingAggregates["m"]!!.count) // stale value kept
        store.fail = null
        s.loadRating("m", force = true)
        assertEquals(2, store.fetches) // still backing off: no request
        assertTrue(s.state.value.errors["m"]!!.startsWith("The listener service is busy"))
        now += 20_000
        s.loadRating("m", force = true)
        assertEquals(3, store.fetches)
        assertNull(s.state.value.errors["m"])
    }

    @Test
    fun invalidInputsAreIgnored() = runBlocking {
        val store = FakeCommunityStore()
        val s = CommunityService(store, defaults)
        s.vote(poll, PollOption("nope", "Nope"))
        s.submitListenerRating("m", 0)
        s.submitListenerRating("m", 11)
        assertTrue(store.votes.isEmpty() && store.ratings.isEmpty())
        assertEquals("Add a movie title (note up to 280 characters).", s.submitSuggestion(InAppSuggestion(" "), "1"))
        assertNull(s.submitSuggestion(InAppSuggestion("Tremors"), "1"))
        assertEquals(1, store.suggestions.size)
    }

    @Test
    fun overridesAndInstallId() {
        val s = CommunityService(FakeCommunityStore(), defaults, PollOverrides.decode("""{"polls":[{"episodeGuid":"g","question":"Best snack?","options":["A","B"],"id":"g.snack"}]}"""))
        assertEquals("g.snack", s.poll("g", ShowInfo.MRT).id)
        assertEquals(poll.id, s.poll("Buzzsprout-19877460", ShowInfo.MRT).id)
        val id = InstallId(defaults)
        val first = id.value
        assertEquals(first, id.value)
        assertEquals(36, first.length)
        defaults.putString(InstallId.KEY, "garbage")
        assertNotEquals("garbage", InstallId(defaults).value)
    }
}
