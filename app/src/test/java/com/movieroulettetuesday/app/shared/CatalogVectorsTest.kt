package com.movieroulettetuesday.app.shared

import com.movieroulettetuesday.app.catalog.CatalogJson
import com.movieroulettetuesday.app.catalog.CatalogValidationException
import com.movieroulettetuesday.app.catalog.CatalogValidator
import com.movieroulettetuesday.app.catalog.CategoryPalette
import com.movieroulettetuesday.app.catalog.HexColor
import com.movieroulettetuesday.app.catalog.MovieCatalog
import com.movieroulettetuesday.app.roulette.MovieSelector
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

/** catalog.json: decoding, defaults, validation, dedupe, HexColor, display palette (D2), D9. */
class CatalogVectorsTest {
    private val v = SharedVectors.load("catalog.json")

    private fun validatorStatus(c: MovieCatalog): String = try {
        CatalogValidator.validate(c); "ok"
    } catch (e: CatalogValidationException.UnsupportedSchema) { "unsupportedSchema" } catch (e: CatalogValidationException.Empty) { "empty" } catch (e: CatalogValidationException.Inconsistent) { "inconsistent" }

    @Test
    fun decodingCases() {
        val cases = v.arr("cases").objs()
        assertEquals(27, cases.size)
        for (c in cases) {
            val name = c.str("name")!!
            val e = c.obj("expected")
            val decoded = runCatching { CatalogJson.decode(c.str("input")!!) }
            if (e.bool("decodes") == false) {
                if (decoded.isSuccess) fail("$name should not decode")
                continue
            }
            val cat = decoded.getOrElse { throw AssertionError("$name should decode", it) }
            assertEquals(name, e.int("schemaVersion"), cat.schemaVersion)
            assertEquals(name, e["categoryIds"]!!.strings(), cat.categories.map { it.id })
            assertEquals(name, e["categoryDisplayNames"]!!.strings(), cat.categories.map { it.displayName })
            assertEquals(name, e["movieIds"]!!.strings(), cat.movies.map { it.id })
            assertEquals(name, e.bool("hasShowBlock"), cat.show != null)
            assertEquals(name, e["showHosts"]!!.strings(), cat.showInfo.hosts)
            assertEquals(name, e["validationIssues"]!!.strings(), cat.validationIssues())
            assertEquals(name, e.str("validator"), validatorStatus(cat))
            val d = cat.deduplicated()
            val de = e.obj("deduplicated")
            assertEquals(name, de["categoryIds"]!!.strings(), d.categories.map { it.id })
            assertEquals(name, de["movieIds"]!!.strings(), d.movies.map { it.id })
            assertEquals(name, de["firstMovieCategories"]!!.strings(), d.movies.firstOrNull()?.categories ?: emptyList<String>())
            e["movies"]!!.objs().zip(cat.movies).forEach { (m, movie) ->
                val tag = "$name ${movie.id}"
                assertEquals(tag, m.str("id"), movie.id)
                assertEquals(tag, m["episodeIds"]!!.strings(), movie.episodeIds)
                assertEquals(tag, m.dbl("hostRating"), movie.hostRating)
                assertEquals(tag, m.int("tmdbId"), movie.tmdbId)
                assertEquals(tag, m.bool("isInfamous"), movie.isInfamous)
                assertEquals(tag, m.str("subtitle"), cat.subtitle(movie))
                assertEquals(tag, m["streaming"] is kotlinx.serialization.json.JsonNull, movie.streaming == null)
            }
        }
    }

    @Test
    fun posterUrlEmptyIsRejectedLikeIos() {
        val c = v.arr("cases").objs().first { it.str("name") == "posterURL-empty-string" }
        assertEquals(true, runCatching { CatalogJson.decode(c.str("input")!!) }.isFailure)
    }

    @Test
    fun hexColor() {
        for (c in v.obj("hexColor").arr("cases").objs()) {
            val input = c.str("input")!!
            val color = HexColor.parse(input)
            if (c.bool("valid") == false) { assertNull(input, color); continue }
            color!!
            assertEquals(input, c.str("hexString"), color.hexString)
            assertEquals(input, c.bool("prefersDarkText"), color.prefersDarkText)
            assertClose(input, c.dbl("relativeLuminance")!!, color.relativeLuminance)
            val argb = color.argb
            assertEquals(input, c["rgba255"]!!.doubles().map { it.toInt() }, listOf((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF, (argb ushr 24) and 0xFF))
        }
    }

    @Test
    fun displayPalette() {
        val p = v.obj("categoryPalette")
        for (c in p.arr("cases").objs()) {
            assertEquals("${c.str("categoryId")} ${c.str("catalogHex")}", c.str("displayHex"), CategoryPalette.hex(c.str("categoryId")!!, c.str("catalogHex")))
        }
        for (c in p.arr("radialStops").objs()) assertEquals(c.str("input"), c["stops"]!!.strings(), CategoryPalette.radialStops(c.str("input")!!))
    }

    @Test
    fun bundledCatalogMatchesIos() {
        val b = v.obj("bundled")
        val bytes = SharedVectors.bundledBytes
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals("app/src/main/assets/movies.json is the iOS bundled file", b.str("sha256"), sha)
        val cat = CatalogValidator.decodeValidated(bytes)
        assertEquals(b.int("movieCount"), cat.movies.size)
        assertEquals(b["categoryIds"]!!.strings(), cat.categories.map { it.id })
        assertEquals(b["hosts"]!!.strings(), cat.showInfo.hosts)
        assertEquals(b.int("infamousCount"), cat.movies.count { it.isInfamous })
        val sel = MovieSelector()
        for ((id, n) in b.obj("candidateCounts")) assertEquals(id, num(n)!!.toInt(), sel.candidates(cat.category(id)!!, cat).size)
    }
}
