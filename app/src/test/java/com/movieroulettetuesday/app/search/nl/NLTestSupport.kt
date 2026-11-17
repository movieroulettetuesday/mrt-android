package com.movieroulettetuesday.app.search.nl

import com.movieroulettetuesday.app.catalog.CatalogJson
import com.movieroulettetuesday.app.catalog.MovieCatalog
import com.movieroulettetuesday.app.shared.SharedVectors
import java.io.File
import kotlinx.coroutines.awaitCancellation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Fake Gemini Nano: a fixed status and a reply per question. No AICore, no network. */
class FakeNLModel(
    var currentStatus: NLModelStatus = NLModelStatus.Available,
    var hang: Boolean = false,
    var throwOnGenerate: Throwable? = null,
    val reply: (question: String) -> String? = { null },
) : NLSearchModel {
    val questions = mutableListOf<String>()
    var statusCalls = 0
    var cancelled = 0
    var downloads = 0

    override suspend fun status(): NLModelStatus { statusCalls++; return currentStatus }

    override suspend fun generate(prompt: String): String? {
        val q = questionOf(prompt)
        questions += q
        throwOnGenerate?.let { throw it }
        if (hang) {
            try { awaitCancellation() } finally { cancelled++ }
        }
        return reply(q)
    }

    override suspend fun download() { downloads++ }

    companion object {
        fun questionOf(prompt: String): String = prompt.substringAfterLast("Request: ").substringBefore("\nJSON:")
    }
}

object NLFixtures {
    val catalog: MovieCatalog by lazy {
        CatalogJson.decode(listOf("src/main/assets/movies.json", "app/src/main/assets/movies.json").map(::File).first { it.isFile }.readBytes())
    }
    val providerNames: List<String> by lazy { NLSearchVocab.providerNames(catalog) }

    /** The pinned copy of mrt-shared `nl-search-test-cases.json` (hash-checked by verifySharedContract). */
    val sharedCases: JsonObject by lazy {
        Json.parseToJsonElement(File(SharedVectors.root(), "nl-search-test-cases.json").readText()).jsonObject
    }
}
