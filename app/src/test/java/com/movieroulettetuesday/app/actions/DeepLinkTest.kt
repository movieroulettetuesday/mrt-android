package com.movieroulettetuesday.app.actions

import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DeepLinkTest {
    private fun parse(s: String) = DeepLink.parse(Uri.parse(s))

    @Test
    fun parsesEveryRoute() {
        assertEquals(DeepLink.Spin, parse("mrt://spin"))
        assertEquals(DeepLink.OpenMovie("laura-1944"), parse("mrt://movie/laura-1944"))
        assertEquals(DeepLink.OpenEpisode("Buzzsprout-19913482"), parse("mrt://episode/Buzzsprout-19913482"))
        assertEquals(DeepLink.LatestEpisode, parse("mrt://latest"))
        assertEquals(DeepLink.Watchlist, parse("mrt://watchlist"))
        assertEquals(DeepLink.AddToWatchlist("laura-1944"), parse("mrt://watchlist/add/laura-1944"))
        assertEquals(DeepLink.MarkWatched("laura-1944"), parse("mrt://watched/laura-1944"))
        assertEquals(DeepLink.WhereToWatch("the-devils-rain-1975"), parse("mrt://wtw/the-devils-rain-1975"))
        assertEquals(DeepLink.OpenOnProvider("laura-1944", "Netflix"), parse("mrt://movie/laura-1944/provider/Netflix"))
        assertEquals(DeepLink.Suggest, parse("mrt://suggest"))
        assertEquals(DeepLink.FindMovie("Laura", 1944), parse("mrt://find?title=Laura&year=1944"))
        assertEquals(DeepLink.FindMovie("Laura", null), parse("mrt://find?title=Laura"))
    }

    @Test
    fun decodesEpisodeGuid() {
        val guid = "Buzzsprout-19913482"
        val uri = DeepLink.OpenEpisode(guid).toUri()
        assertEquals(DeepLink.OpenEpisode(guid), DeepLink.parse(uri))
    }

    @Test
    fun roundTrips() {
        val links = listOf(
            DeepLink.Spin,
            DeepLink.OpenMovie("passengers-2016"),
            DeepLink.OpenEpisode("Buzzsprout-1"),
            DeepLink.LatestEpisode,
            DeepLink.Watchlist,
            DeepLink.AddToWatchlist("x"),
            DeepLink.MarkWatched("x"),
            DeepLink.WhereToWatch("x"),
            DeepLink.OpenOnProvider("x", "Shudder"),
            DeepLink.Suggest,
            DeepLink.FindMovie("The Thing", 1982),
        )
        for (link in links) {
            assertEquals(link, DeepLink.parse(link.toUri()))
        }
    }

    @Test
    fun rejectsJunk() {
        assertNull(parse("https://example.com/spin"))
        assertNull(parse("mrt://unknown"))
        assertNull(parse("mrt://movie/"))
        assertNull(parse("mrt://spin/extra"))
        assertNull(parse("mrt://watchlist/remove/x"))
        assertNull(parse("mrt://find"))
        assertNull(parse("mrt://episode/"))
        assertNull(DeepLink.parse(Intent(Intent.ACTION_VIEW)))
        assertNull(DeepLink.parse(null as Intent?))
    }

    @Test
    fun intentUsesViewAndActivity() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = DeepLink.Spin.intent(ctx)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("mrt://spin", intent.data.toString())
        assertEquals(DeepLink.ACTIVITY, intent.component?.className)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
        assertEquals(DeepLink.Spin, DeepLink.parse(intent))
    }
}
