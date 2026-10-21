package com.movieroulettetuesday.app.catalog

import java.io.File

object CatalogFixtures {
    /** The bundled asset, read straight from the source tree (unit tests run in the module dir). */
    val bundledBytes: ByteArray by lazy {
        listOf("src/main/assets/movies.json", "app/src/main/assets/movies.json")
            .map(::File).first { it.isFile }.readBytes()
    }

    const val MINIMAL = """
        {"categories":[{"id":"c1","name":"One","colorHex":"#112233","movieIds":["m1"]}],
         "movies":[{"id":"m1","title":"Movie","year":1999,"genre":"Drama","categories":["c1"]}]}
    """

    fun minimal(
        schema: Int = 2,
        color: String = "#112233",
        title: String = "Movie",
    ) = """
        {"schemaVersion":$schema,"_note":"ignored",
         "categories":[{"id":"c1","name":"One","colorHex":"$color","movieIds":["m1"]}],
         "movies":[{"id":"m1","title":"$title","year":1999,"genre":"Drama","categories":["c1"]}]}
    """.trimIndent().toByteArray()
}
