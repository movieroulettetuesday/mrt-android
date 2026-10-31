package com.movieroulettetuesday.app.movies

import com.movieroulettetuesday.app.catalog.CatalogJson
import com.movieroulettetuesday.app.catalog.StreamingInfo.Provider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Mirrors iOS MRTCoreTests/ParamountProviderTests (PR #8). */
class ParamountProviderTest {
    private val fubo = Provider(257, "fuboTV", priority = 10)
    private val premium = Provider(2303, "Paramount Plus Premium", "/premium.png", 14)
    private val essential = Provider(2616, "Paramount Plus Essential", priority = 15)
    private val amazon = Provider(582, "Paramount+ Amazon Channel", priority = 26)
    private val roku = Provider(633, "Paramount+ Roku Premium Channel", priority = 62)
    private val legacy = Provider(531, "Paramount Plus", priority = 40)
    private val netflix = Provider(8, "Netflix", priority = 1)
    private val netflixAds = Provider(1796, "Netflix Standard with Ads", priority = 3)
    private val peacock = Provider(386, "Peacock Premium", priority = 4)
    private val peacockPlus = Provider(387, "Peacock Premium Plus", priority = 5)

    private fun collapse(vararg p: Provider) = StreamingCatalog.collapsingParamountPlusDuplicates(p.toList())
    private fun isParamount(p: Provider) = p.id == 531 || p.name.lowercase().contains("paramount")

    @Test fun keepsBestPriorityParamountAndLeavesOtherProviders() {
        val c = collapse(essential, fubo, netflix, premium, amazon, netflixAds, roku, peacock, peacockPlus)
        assertEquals(listOf(fubo, netflix, premium, netflixAds, peacock, peacockPlus).map { it.id }, c.map { it.id })
        assertEquals("/premium.png", c.first { it.id == premium.id }.logo)
    }

    @Test fun singleParamountAndEmptyListStayPut() {
        assertEquals(listOf(premium.id, netflix.id), collapse(premium, netflix).map { it.id })
        assertEquals(emptyList<Int>(), collapse().map { it.id })
    }

    @Test fun legacyParamountIdCollapsesWithNamedVariants() {
        val oddName = Provider(531, "P+", priority = 2)
        assertEquals(listOf(531), collapse(essential, oddName, legacy).map { it.id })
    }

    @Test fun nameMatchIsCaseInsensitive() {
        val upper = Provider(2303, "PARAMOUNT PLUS PREMIUM", priority = 9)
        assertEquals(listOf(upper.id), collapse(amazon, upper).map { it.id })
    }

    @Test fun equalPriorityKeepsTheEarliestParamount() {
        val earlier = Provider(2616, "Paramount Plus Essential", priority = 14)
        val later = Provider(2303, "Paramount Plus Premium", priority = 14)
        assertEquals(listOf(fubo.id, earlier.id), collapse(fubo, earlier, later).map { it.id })
    }

    @Test fun bundledCatalogMoviesShowOneParamountPlus() {
        val catalog = CatalogJson.decode(File("src/main/assets/movies.json").readBytes())
        val marathon = catalog.movie("marathon-man-1976")!!
        val streaming = marathon.streaming!!
        val raw = streaming.stream + streaming.free
        assertTrue("fixture still has the duplicate ids", raw.count(::isParamount) > 1)
        val c = StreamingCatalog.collapsingParamountPlusDuplicates(raw)
        assertEquals(listOf(2303), c.filter(::isParamount).map { it.id })
        assertEquals(raw.filterNot(::isParamount).map { it.id }, c.filterNot(::isParamount).map { it.id })
        var dupMovies = 0
        for (m in catalog.movies) {
            val s = m.streaming ?: continue
            val p = s.stream + s.free
            if (p.count(::isParamount) > 1) dupMovies++
            val shown = StreamingCatalog.collapsingParamountPlusDuplicates(p)
            assertTrue(m.id, shown.count(::isParamount) <= 1)
            assertEquals(m.id, p.filterNot(::isParamount).map { it.id }, shown.filterNot(::isParamount).map { it.id })
        }
        assertTrue(dupMovies > 0)
    }
}
