package com.movieroulettetuesday.app.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CatalogValidationTest {
    private fun cat(id: String, movies: List<String>, color: String = "#112233") =
        RouletteCategory(id = id, name = id, colorHex = color, movieIds = movies)

    private fun movie(id: String, cats: List<String>) = Movie(id = id, title = id, year = 2000, genre = "Drama", categories = cats)

    @Test fun findsProblems() {
        val c = MovieCatalog(
            categories = listOf(cat("a", listOf("m1", "ghost")), cat("b", emptyList(), color = "red"), cat("a", listOf("m1"))),
            movies = listOf(movie("m1", listOf("a", "nowhere")), movie("m1", listOf("a")), movie("m2", listOf("b"))),
        )
        val issues = c.validationIssues()
        assertTrue(issues.contains("Duplicate movie id 'm1'"))
        assertTrue(issues.contains("Duplicate category id 'a'"))
        assertTrue(issues.contains("Category 'a' references unknown movie 'ghost'"))
        assertTrue(issues.contains("Category 'b' has invalid colorHex 'red'"))
        assertTrue(issues.contains("Category 'b' has no movies"))
        assertTrue(issues.contains("Movie 'm1' references unknown category 'nowhere'"))
        assertTrue(issues.contains("Movie 'm2' lists 'b' but that category doesn't list it"))
        assertEquals(issues.sorted(), issues)
    }

    @Test fun consistentCatalogHasNoIssues() {
        val c = MovieCatalog(categories = listOf(cat("a", listOf("m1"))), movies = listOf(movie("m1", listOf("a"))))
        assertEquals(emptyList<String>(), c.validationIssues())
        CatalogValidator.validate(c)
    }

    @Test fun rejectsUnsupportedSchemaEmptyAndInconsistent() {
        expect<CatalogValidationException.UnsupportedSchema> { CatalogValidator.decodeValidated(CatalogFixtures.minimal(schema = 3)) }
        expect<CatalogValidationException.Empty> { CatalogValidator.validate(MovieCatalog(categories = emptyList(), movies = emptyList())) }
        expect<CatalogValidationException.Inconsistent> { CatalogValidator.decodeValidated(CatalogFixtures.minimal(color = "#12")) }
    }

    @Test fun deduplicatedKeepsFirst() {
        val c = MovieCatalog(
            categories = listOf(cat("a", listOf("m1")), cat("a", listOf("x"))),
            movies = listOf(movie("m1", listOf("a")), movie("m1", emptyList())),
        ).deduplicated()
        assertEquals(1, c.movies.size)
        assertEquals(listOf("a"), c.movies.single().categories)
        assertEquals(listOf("m1"), c.categories.single().movieIds)
    }

    @Test fun hexColorParsing() {
        val c = HexColor.parse("#FF8000")!!
        assertEquals(1.0, c.red, 1e-9)
        assertEquals(128 / 255.0, c.green, 1e-9)
        assertEquals(0xFFFF8000.toInt(), c.argb)
        assertEquals(0x80112233.toInt(), HexColor.parse("11223380")!!.argb)
        assertNull(HexColor.parse("#GG0000"))
        assertNull(HexColor.parse("#FFF"))
        assertTrue(HexColor.parse("#E9C46A")!!.prefersDarkText)
        assertTrue(!HexColor.parse("#7B2CBF")!!.prefersDarkText)
    }

    private inline fun <reified T : Throwable> expect(block: () -> Unit) {
        try {
            block(); fail("expected ${T::class.simpleName}")
        } catch (e: Throwable) {
            if (e !is T) throw e
        }
    }
}
