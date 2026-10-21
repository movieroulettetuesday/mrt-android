package com.movieroulettetuesday.app.shared

import com.movieroulettetuesday.app.catalog.CatalogJson
import com.movieroulettetuesday.app.catalog.MovieCatalog
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals

/**
 * Loads the pinned mrt-shared vectors (repo `shared/test-vectors`, hash-checked by the build) so the
 * Android tests run against the cases generated from the unmodified iOS sources.
 */
object SharedVectors {
    const val TOLERANCE = 1e-9
    private val cache = HashMap<String, JsonObject>()

    fun root(): File = listOf("../shared", "shared").map(::File).first { File(it, "SHA256SUMS").isFile }

    fun load(name: String): JsonObject = cache.getOrPut(name) {
        Json.parseToJsonElement(File(root(), "test-vectors/$name").readText()).jsonObject
    }

    val bundledBytes: ByteArray by lazy {
        listOf("src/main/assets/movies.json", "app/src/main/assets/movies.json").map(::File).first { it.isFile }.readBytes()
    }

    /** "bundled" | "fixture" | "fixtureInfamous" from selection.json#fixtures, or an inline catalog. */
    fun catalog(ref: JsonElement): MovieCatalog = when {
        ref is JsonPrimitive && ref.content == "bundled" -> CatalogJson.decode(bundledBytes)
        ref is JsonPrimitive -> CatalogJson.decode(load("selection.json").obj("fixtures")[ref.content].toString())
        else -> CatalogJson.decode(ref.toString())
    }
}

fun JsonObject.obj(key: String): JsonObject = this[key]!!.jsonObject
fun JsonObject.arr(key: String): JsonArray = this[key]!!.jsonArray
fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
fun JsonObject.dbl(key: String): Double? = this[key]?.let(::num)
fun JsonObject.int(key: String): Int? = dbl(key)?.toInt()
fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
fun JsonElement.objs(): List<JsonObject> = jsonArray.map { it.jsonObject }
fun JsonElement.strings(): List<String> = jsonArray.map { it.jsonPrimitive.content }
fun JsonElement.doubles(): List<Double> = jsonArray.map { num(it)!! }

/** Numbers, plus the "NaN"/"Infinity"/"-Infinity" string encoding. */
fun num(e: JsonElement): Double? {
    val p = e as? JsonPrimitive ?: return null
    if (p is JsonNull) return null
    if (p.isString) return when (p.content) { "NaN" -> Double.NaN; "Infinity" -> Double.POSITIVE_INFINITY; "-Infinity" -> Double.NEGATIVE_INFINITY; else -> null }
    return p.doubleOrNull
}

fun assertClose(message: String, expected: Double, actual: Double, tol: Double = SharedVectors.TOLERANCE) {
    if (expected.isNaN()) { assertEquals(message, true, actual.isNaN()); return }
    assertEquals(message, expected, actual, tol)
}

fun JsonObject.seed(): Long = str("seed")!!.toULong().toLong()
