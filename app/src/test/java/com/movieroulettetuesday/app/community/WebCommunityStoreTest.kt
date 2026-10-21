package com.movieroulettetuesday.app.community

import com.movieroulettetuesday.app.community.MockCloudKit.Reply
import com.movieroulettetuesday.app.suggest.InAppSuggestion
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WebCommunityStoreTest {
    private val mock = MockCloudKit()
    private val installId = "6f1c2a3b-0000-4000-8000-1234567890ab"

    private fun store(token: String = "tok", relay: String? = null) = WebCommunityStore(
        CommunityConfig(apiToken = token, relayUrl = relay ?: "${mock.base}/relay", cloudKitBase = mock.base),
        installId = { installId },
        timeoutMs = 5_000,
    )

    @After fun tearDown() = mock.close()

    private fun JsonObject.s(vararg path: String): String? {
        var o: kotlinx.serialization.json.JsonElement = this
        for (p in path) o = (o as? JsonObject)?.get(p) ?: (o as? JsonArray)?.getOrNull(p.toInt()) ?: return null
        return (o as? JsonPrimitive)?.content
    }

    @Test
    fun queryUsesCloudKitWebServicesShapeAndPages() = runBlocking {
        mock.handler = { r ->
            if (r.body?.get("continuationMarker") == null) {
                Reply(200, """{"records":[${MockCloudKit.voteRecord("vote-a", "good")},${MockCloudKit.voteRecord("vote-b", "bad")}],"continuationMarker":"m1"}""")
            } else {
                Reply(200, """{"records":[${MockCloudKit.voteRecord("vote-c", "good")},{"recordName":"x","serverErrorCode":"NOT_FOUND"}]}""")
            }
        }
        val votes = store().fetchVotes("Buzzsprout-1.good-bad")
        assertEquals(mapOf("vote-a" to "good", "vote-b" to "bad", "vote-c" to "good"), votes)
        assertEquals(2, mock.requests.size)
        val first = mock.requests[0]
        assertEquals("POST", first.method)
        assertEquals("/database/1/iCloud.com.movieroulettetuesday.app/production/public/records/query", first.path)
        assertEquals("ckAPIToken=tok", first.query)
        val b = first.body!!
        assertEquals("PollVote", b.s("query", "recordType"))
        assertEquals("pollId", b.s("query", "filterBy", "0", "fieldName"))
        assertEquals("EQUALS", b.s("query", "filterBy", "0", "comparator"))
        assertEquals("Buzzsprout-1.good-bad", b.s("query", "filterBy", "0", "fieldValue", "value"))
        assertEquals("200", b.s("resultsLimit"))
        assertEquals("_defaultZone", b.s("zoneID", "zoneName"))
        assertEquals("m1", mock.requests[1].body!!.s("continuationMarker"))
    }

    @Test
    fun ratingsResolveLegacyStarsAndIgnoreBadValues() = runBlocking {
        mock.handler = {
            Reply(200, """{"records":[${MockCloudKit.ratingRecord("r1", 7, 4)},${MockCloudKit.ratingRecord("r2", null, 3)},${MockCloudKit.ratingRecord("r3", null, null)}]}""")
        }
        assertEquals(mapOf("r1" to 7, "r2" to 6), store().fetchRatings("m"))
        assertEquals("ListenerRating", mock.requests[0].body!!.s("query", "recordType"))
        assertEquals("movieId", mock.requests[0].body!!.s("query", "filterBy", "0", "fieldName"))
    }

    @Test
    fun unknownRecordTypeMeansNoRecordsYet() = runBlocking {
        mock.handler = { Reply(400, """{"uuid":"u","serverErrorCode":"BAD_REQUEST","reason":"Did not find record type: PollVote"}""") }
        assertEquals(emptyMap<String, String>(), store().fetchVotes("p"))
    }

    @Test
    fun throttlingMapsToBusyWithRetryAfter() = runBlocking {
        mock.handler = { Reply(503, """{"serverErrorCode":"THROTTLED","retryAfter":12}""") }
        try { store().fetchVotes("p"); fail() } catch (e: CommunityError.Busy) { assertEquals(12, e.retryAfter) }
        mock.handler = { Reply(429, """{"error":"rate_limited"}""", mapOf("Retry-After" to "30")) }
        try { store().submitVote("p", "good"); fail() } catch (e: CommunityError.Busy) { assertEquals(30, e.retryAfter) }
        mock.handler = { Reply(503, "{}") }
        try { store().fetchRatings("m"); fail() } catch (e: CommunityError.Busy) { assertEquals(null, e.retryAfter) }
        mock.handler = { Reply(400, """{"serverErrorCode":"QUOTA_EXCEEDED"}""") }
        try { store().fetchRatings("m"); fail() } catch (_: CommunityError.QuotaExceeded) {}
        mock.handler = { Reply(401, """{"serverErrorCode":"AUTHENTICATION_FAILED"}""") }
        try { store().fetchRatings("m"); fail() } catch (_: CommunityError.NotSetUp) {}
    }

    @Test
    fun voteGoesToTheRelayWithOnlyTheDedupeHash() = runBlocking {
        val pollId = "Buzzsprout-19913482.good-bad"
        val key = Sha256.hex(installId + pollId)
        val name = "vote-$pollId-$key"
        mock.handler = { Reply(200, """{"records":[${MockCloudKit.voteRecord(name, "good")}]}""") }
        assertEquals(name, store().submitVote(pollId, "good"))
        val r = mock.requests.single()
        assertEquals("/relay/v1/vote", r.path)
        assertEquals(setOf("pollId", "option", "voterKey"), r.body!!.keys)
        assertEquals(key, r.body!!["voterKey"]!!.jsonPrimitive.content)
        assertFalse(r.body.toString().contains(installId)) // the install id never leaves the device
        assertEquals(CommunityRecordNames.vote(pollId, installId), name)
    }

    @Test
    fun ratingAndSuggestionBodies() = runBlocking {
        mock.handler = { r ->
            if (r.path.endsWith("/rating")) Reply(200, """{"records":[${MockCloudKit.ratingRecord("rating-m-x", 9, 5)}]}""")
            else Reply(200, """{"records":[{"recordName":"suggestion-ABC","recordType":"Suggestion","fields":{}}]}""")
        }
        assertEquals("rating-m-x", store().submitRating("the-room-2003", 9))
        val rb = mock.requests[0].body!!
        assertEquals(Sha256.hex(installId + "rating:the-room-2003"), rb["voterKey"]!!.jsonPrimitive.content)
        assertEquals("9", rb["halfStars"]!!.jsonPrimitive.content)
        store().submitSuggestion(InAppSuggestion(" Tremors ", " Horror ", " Worms ", " Laura "), "1.0.0 (1)")
        val sb = mock.requests[1].body!!
        assertEquals("/relay/v1/suggestion", mock.requests[1].path)
        assertEquals(mapOf("title" to "Tremors", "category" to "Horror", "note" to "Worms", "creditName" to "Laura", "appVersion" to "1.0.0 (1)"),
            sb.mapValues { it.value.jsonPrimitive.content })
        try { store().submitRating("m", 11); fail() } catch (_: CommunityError.InvalidStars) {}
    }

    @Test
    fun relayRecordErrorAndValidationErrors() = runBlocking {
        mock.handler = { Reply(200, """{"records":[{"recordName":"x","serverErrorCode":"ZONE_BUSY","retryAfter":3}]}""") }
        try { store().submitVote("p", "good"); fail() } catch (e: CommunityError.Busy) { assertEquals(3, e.retryAfter) }
        mock.handler = { Reply(400, """{"error":"invalid_option"}""") }
        try { store().submitVote("p", "nope"); fail() } catch (_: CommunityError.InvalidOption) {}
    }

    @Test
    fun disabledGracefully() = runBlocking {
        val readOnly = store(relay = "")
        assertFalse(readOnly.canWrite)
        try { readOnly.submitVote("p", "good"); fail() } catch (_: CommunityError.NotConfigured) {}
        try { store(token = "").fetchVotes("p"); fail() } catch (_: CommunityError.NotSetUp) {}
        assertTrue(mock.requests.isEmpty())
        assertFalse(CommunityConfig.fromBuild("", "https://relay.example").canRead)
        assertEquals("", CommunityConfig.fromBuild("t", "http://cleartext.example").relayUrl) // https only
        assertEquals("https://r.example", CommunityConfig.fromBuild(" t ", "https://r.example/ ").relayUrl)
    }

    @Test
    fun offlineIsFriendly() = runBlocking {
        val dead = WebCommunityStore(CommunityConfig("tok", "", cloudKitBase = "http://127.0.0.1:1"), { installId }, 2_000)
        try { dead.fetchVotes("p"); fail() } catch (_: CommunityError.Offline) {}
    }
}
