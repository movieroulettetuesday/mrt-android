package com.movieroulettetuesday.app.movies

import android.app.Application
import android.content.Intent
import android.content.pm.PackageInfo
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.movieroulettetuesday.app.catalog.CatalogJson
import com.movieroulettetuesday.app.catalog.Movie
import com.movieroulettetuesday.app.catalog.MovieCatalog
import com.movieroulettetuesday.app.catalog.StreamingInfo
import com.movieroulettetuesday.app.catalog.StreamingInfo.Provider
import com.movieroulettetuesday.app.roulette.SpinHistoryStore
import com.movieroulettetuesday.app.roulette.SpinStats
import com.movieroulettetuesday.app.ui.theme.MrtTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** Provider → package / URI mapping, and the movie-page chip that fires it. */
@RunWith(RobolectricTestRunner::class)
class ProviderLaunchTest {
    private fun movie(title: String = "The Thing", tmdbId: Int? = null, link: String? = null) = Movie(
        id = "m", title = title, year = 1982, genre = "Horror", tmdbId = tmdbId,
        streaming = StreamingInfo(link = link, stream = listOf(Provider(8, "Netflix"))),
    )

    private fun plan(provider: Provider, title: String = "The Thing", link: String? = null) =
        ProviderLaunch.plan(provider, movie(title, link = link, tmdbId = if (link != null) 5 else null))

    @Test fun netflixPrefersPhonePackageThenTvAndWebSearch() {
        val p = plan(Provider(8, "Netflix"))
        assertEquals(listOf("com.netflix.mediaclient", "com.netflix.ninja"), p.packages)
        assertEquals("https://www.netflix.com/search?q=The%20Thing", p.webUri)
        assertEquals(
            listOf(
                "https://www.netflix.com/search?q=The%20Thing",
                "nflx://www.netflix.com/search?q=The%20Thing",
            ),
            p.appUris,
        )
        val installed = ProviderLaunch.candidateIntents(p, "com.netflix.mediaclient", Intent(Intent.ACTION_MAIN))
        assertEquals("com.netflix.mediaclient", installed[0].`package`)
        assertEquals(p.appUris[0], installed[0].dataString)
        assertEquals(p.appUris[1], installed[1].dataString)
        assertEquals(Intent.ACTION_MAIN, installed[2].action)
        assertNull(installed[3].`package`)
        assertEquals(p.webUri, installed[3].dataString)
        val absent = ProviderLaunch.candidateIntents(p, null, null)
        assertEquals(1, absent.size)
        assertNull(absent[0].`package`)
        assertEquals(p.webUri, absent[0].dataString)
    }

    @Test fun mappedProviders() {
        assertEquals("com.amazon.avod.thirdpartyclient", plan(Provider(10, "Amazon Video")).packages.first())
        assertEquals("https://app.primevideo.com/search?phrase=The%20Thing", plan(Provider(9, "Amazon Prime Video")).webUri)
        assertTrue(plan(Provider(9, "Amazon Prime Video")).appUris.any { it.startsWith("aiv://") })
        assertEquals(listOf("com.wbd.stream"), plan(Provider(1899, "HBO Max")).packages)
        assertEquals("https://play.max.com/search?q=The%20Thing", plan(Provider(1899, "Max")).webUri)
        assertEquals(listOf("com.wbd.stream"), plan(Provider(1825, "HBO Max Amazon Channel")).packages)
        assertEquals("https://tv.apple.com/us/search?term=The%20Thing", plan(Provider(2, "Apple TV Store")).webUri)
        assertEquals(listOf("com.apple.atve.androidtv.appletv"), plan(Provider(350, "Apple TV")).packages)
        assertEquals("https://www.disneyplus.com/search?q=The%20Thing", plan(Provider(337, "Disney Plus")).webUri)
        assertEquals(listOf("com.hulu.plus"), plan(Provider(15, "Hulu")).packages)
        assertEquals(listOf("com.cbs.ott"), plan(Provider(531, "P+")).packages)
        assertEquals("https://www.paramountplus.com/search/?q=The%20Thing", plan(Provider(2303, "Paramount Plus Premium")).webUri)
        assertEquals(listOf("com.cbs.ott"), plan(Provider(582, "Paramount+ Amazon Channel")).packages)
        assertEquals(listOf("com.peacocktv.peacockandroid"), plan(Provider(386, "Peacock Premium")).packages)
        assertEquals("https://tubitv.com/search/The%20Thing", plan(Provider(73, "Tubi TV")).webUri)
        assertTrue(plan(Provider(258, "Criterion Channel")).packages.isEmpty())
        assertEquals("https://www.criterionchannel.com/search?q=The%20Thing", plan(Provider(258, "Criterion Channel")).webUri)
        assertEquals(listOf("com.google.android.apps.youtube.unplugged"), plan(Provider(2528, "YouTube TV")).packages)
        assertEquals("https://tv.youtube.com/search?q=The%20Thing", plan(Provider(2528, "YouTube TV")).webUri)
        assertEquals("com.google.android.youtube", plan(Provider(192, "YouTube")).packages.first())
        assertEquals("com.google.android.youtube", plan(Provider(235, "YouTube Free")).packages.first())
        assertEquals("https://www.youtube.com/results?search_query=The%20Thing", plan(Provider(192, "YouTube")).webUri)
        assertEquals(listOf("com.mubi"), plan(Provider(11, "MUBI")).packages)
        assertEquals(listOf("tv.pluto.android"), plan(Provider(300, "Pluto TV")).packages)
        assertEquals(listOf("com.dramafever.shudder"), plan(Provider(99, "Shudder")).packages)
        assertTrue(plan(Provider(361, "TCM")).packages.isEmpty())
        assertEquals("https://www.tcm.com/search?q=The%20Thing", plan(Provider(361, "TCM")).webUri)
        assertEquals(listOf("com.google.android.videos"), plan(Provider(3, "Google Play Movies")).packages)
        assertEquals("https://play.google.com/store/search?q=The%20Thing&c=movies", plan(Provider(3, "Google Play Movies")).webUri)
        assertEquals(listOf("air.com.vudu.air.DownloaderTablet"), plan(Provider(7, "Fandango At Home")).packages)
        assertEquals(listOf("com.plexapp.android"), plan(Provider(538, "Plex")).packages)
        assertEquals("com.netflix.mediaclient", plan(Provider(1796, "Netflix Standard with Ads")).packages.first())
    }

    @Test fun encodesReservedCharactersInTheTitle() {
        assertEquals(
            "https://www.netflix.com/search?q=A%20%26%20B",
            plan(Provider(8, "Netflix"), title = "A & B").webUri,
        )
    }

    @Test fun unknownProviderUsesTmdbThenWebSearch() {
        val watch = "https://www.themoviedb.org/movie/5/watch?locale=US"
        val withLink = plan(Provider(331, "FlixFling"), link = watch)
        assertTrue(withLink.packages.isEmpty())
        assertTrue(withLink.appUris.isEmpty())
        assertEquals(watch, withLink.webUri)
        val search = ProviderLaunch.plan(Provider(331, "FlixFling"), Movie("m", "The Thing", 1982, "Horror"))
        assertEquals("https://www.google.com/search?q=The%20Thing%20on%20FlixFling", search.webUri)
        assertTrue(search.webUri.startsWith("https://"))
    }

    @Test fun everyBundledProviderHasAnHttpsFallback() {
        val catalog = CatalogJson.decode(File("src/main/assets/movies.json").readBytes())
        val seen = HashSet<String>()
        for (m in catalog.movies) {
            val s = m.streaming ?: continue
            for (p in s.stream + s.free + s.rent + s.buy) {
                val planned = ProviderLaunch.plan(p, m)
                assertTrue("${m.id} ${p.name}", planned.webUri.startsWith("https://") && planned.webUri.length > 8)
                planned.packages.forEach { seen += it }
            }
        }
        assertEquals(ProviderLaunch.PACKAGES.toSet(), seen)
    }

    @Test fun manifestQueriesEveryPackage() {
        val xml = File("src/main/AndroidManifest.xml").readText()
        assertEquals(ProviderLaunch.PACKAGES.toSet().size, ProviderLaunch.PACKAGES.size)
        for (pkg in ProviderLaunch.PACKAGES) {
            assertTrue(pkg, xml.contains("<package android:name=\"$pkg\" />"))
        }
    }

    @Test fun openUsesInstalledPhonePackageElseHttps() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val provider = Provider(8, "Netflix")
        val movie = movie()
        assertTrue(ProviderLaunch.open(app, provider, movie))
        val web = shadowOf(app).nextStartedActivity
        assertNull(web.`package`)
        assertEquals("https://www.netflix.com/search?q=The%20Thing", web.dataString)

        val info = PackageInfo()
        info.packageName = "com.netflix.mediaclient"
        shadowOf(app.packageManager).installPackage(info)
        assertEquals("com.netflix.mediaclient", ProviderLaunch.installedPackage(app, ProviderLaunch.plan(provider, movie)))
        assertTrue(ProviderLaunch.open(app, provider, movie))
        val inApp = shadowOf(app).nextStartedActivity
        assertEquals("com.netflix.mediaclient", inApp.`package`)
        assertEquals(Intent.ACTION_VIEW, inApp.action)
        assertEquals("https://www.netflix.com/search?q=The%20Thing", inApp.dataString)
    }

    @Test fun unknownOpenDoesNotThrow() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val movie = Movie("m", "Bare Title", 1999, "Drama")
        assertTrue(ProviderLaunch.open(app, Provider(999001, "Mystery Stream"), movie))
        val started = shadowOf(app).nextStartedActivity
        assertEquals("https://www.google.com/search?q=Bare%20Title%20on%20Mystery%20Stream", started.dataString)
        assertNull(started.`package`)
    }
}

@RunWith(RobolectricTestRunner::class)
class ProviderChipTapTest {
    @get:Rule val compose = createComposeRule()

    @Test fun providerChipIsClickableAndOpensListing() {
        val netflix = Provider(8, "Netflix")
        val essential = Provider(2616, "Paramount Plus Essential", priority = 15)
        val premium = Provider(2303, "Paramount Plus Premium", priority = 14)
        val odd = Provider(331, "FlixFling")
        val watch = "https://www.themoviedb.org/movie/1091/watch?locale=US"
        val movie = Movie(
            id = "the-thing-1982", title = "The Thing", year = 1982, genre = "Horror", tmdbId = 1091,
            streaming = StreamingInfo(link = watch, stream = listOf(netflix, essential, premium), free = listOf(odd)),
        )
        val catalog = MovieCatalog(categories = emptyList(), movies = listOf(movie))
        val stats = SpinStats(SpinHistoryStore.Log())
        compose.setContent {
            MrtTheme {
                MovieDetailScreen(
                    movie.id,
                    MoviesInputs(catalog, PersonalRatings(), stats, MyServices(setOf(essential.id))),
                    PersonalRatingsStore(null),
                    onBack = {},
                    onOpenEpisode = {},
                )
            }
        }
        compose.onNodeWithTag("streaming-stream").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("provider-stream-8").assertIsDisplayed().assertHasClickAction().performClick()
        val app = ApplicationProvider.getApplicationContext<Application>()
        assertEquals("https://www.netflix.com/search?q=The%20Thing", shadowOf(app).nextStartedActivity.dataString)
        compose.onNodeWithTag("provider-stream-2303").assertIsDisplayed()
        compose.onAllNodesWithTag("provider-stream-2616").assertCountEquals(0)
        compose.onNodeWithTag("streaming-free").performScrollTo()
        compose.onNodeWithTag("provider-free-331").assertHasClickAction().performClick()
        assertEquals(watch, shadowOf(app).nextStartedActivity.dataString)
    }
}
