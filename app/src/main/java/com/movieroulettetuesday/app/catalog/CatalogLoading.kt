package com.movieroulettetuesday.app.catalog

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Where the live movie list lives: movies.json on the iOS repo's `main` branch (see CATALOG.md). */
object RemoteCatalog {
    const val URL =
        "https://raw.githubusercontent.com/movieroulettetuesday/movie-roulette-tuesday/main/MovieRouletteTuesday/Resources/movies.json"
    /** Short on purpose: the refresh is background and best-effort (iOS: 8 s). */
    const val TIMEOUT_MS = 8_000
    /** Bundled fallback in app/src/main/assets. */
    const val BUNDLED_ASSET = "movies.json"
}

sealed class CatalogValidationException(message: String) : Exception(message) {
    class UnsupportedSchema(val version: Int) :
        CatalogValidationException("The movie list uses an unsupported format (schema $version).")
    class Empty : CatalogValidationException("The movie list is empty.")
    class Inconsistent(val issues: List<String>) :
        CatalogValidationException("The movie list has problems: ${issues.take(3).joinToString("; ")}")
}

/**
 * A catalog only replaces what the app has when it decodes, uses a schema this build understands,
 * is non-empty, and is fully consistent. Port of `CatalogValidator`.
 */
object CatalogValidator {
    val supportedSchemaVersions = 1..2

    fun validate(catalog: MovieCatalog): MovieCatalog {
        if (catalog.schemaVersion !in supportedSchemaVersions) {
            throw CatalogValidationException.UnsupportedSchema(catalog.schemaVersion)
        }
        if (catalog.movies.isEmpty() || catalog.categories.isEmpty()) throw CatalogValidationException.Empty()
        val issues = catalog.validationIssues()
        if (issues.isNotEmpty()) throw CatalogValidationException.Inconsistent(issues)
        return catalog
    }

    fun decodeValidated(bytes: ByteArray): MovieCatalog = validate(CatalogJson.decode(bytes))
}

class HttpStatusException(val code: Int) : IOException("The server responded with HTTP $code.")

/** Plain GET with a short timeout, no cookies, no HTTP cache. [fetch] is injectable for tests. */
class RemoteCatalogSource(
    val url: String = RemoteCatalog.URL,
    private val fetch: (String) -> ByteArray = ::httpGet,
) {
    fun fetchData(): ByteArray = fetch(url)

    companion object {
        fun httpGet(url: String): ByteArray {
            require(url.startsWith("https://")) { "HTTPS only" }
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "GET"
                conn.connectTimeout = RemoteCatalog.TIMEOUT_MS
                conn.readTimeout = RemoteCatalog.TIMEOUT_MS
                conn.useCaches = false
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("Accept", "application/json")
                val code = conn.responseCode
                if (code !in 200..299) throw HttpStatusException(code)
                return conn.inputStream.use { it.readBytes() }
            } finally {
                conn.disconnect()
            }
        }
    }
}

/**
 * The last good remote movies.json (raw bytes that passed validation) plus a sidecar recording
 * which app build wrote it. A cache from another build is ignored: after an app update the bundled
 * list may be newer, and the next refresh repopulates the cache. Port of `CatalogCache`.
 */
class CatalogCache(val file: File, val build: String) {
    @Serializable
    private data class Meta(val build: String, val fetchedAtEpochMs: Long)

    private val metaFile: File get() = File(file.path + ".meta")

    private fun meta(): Meta? = runCatching { json.decodeFromString<Meta>(metaFile.readText()) }.getOrNull()

    fun loadData(): ByteArray? {
        val m = meta() ?: return null
        if (m.build != build) return null
        return runCatching { file.readBytes() }.getOrNull()
    }

    val fetchedAtEpochMs: Long?
        get() = meta()?.takeIf { it.build == build && file.isFile }?.fetchedAtEpochMs

    fun loadCatalog(): MovieCatalog? = loadData()?.let { runCatching { CatalogValidator.decodeValidated(it) }.getOrNull() }

    fun save(data: ByteArray, fetchedAtEpochMs: Long = System.currentTimeMillis()) {
        file.parentFile?.mkdirs()
        atomicWrite(file, data)
        atomicWrite(metaFile, json.encodeToString(Meta.serializer(), Meta(build, fetchedAtEpochMs)).toByteArray())
    }

    fun clear() {
        file.delete()
        metaFile.delete()
    }

    private fun atomicWrite(target: File, bytes: ByteArray) {
        val tmp = File(target.path + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) throw IOException("Couldn't write ${target.name}")
        }
    }

    companion object {
        const val FILE_NAME = "movies-remote-cache.json"
        private val json = Json { ignoreUnknownKeys = true }
    }
}

/** Fetches, validates, and only then replaces the cache. Any failure throws and leaves the cache untouched. */
class CatalogRefresher(private val remote: RemoteCatalogSource, private val cache: CatalogCache?) {
    fun refresh(nowEpochMs: Long = System.currentTimeMillis()): MovieCatalog {
        val data = remote.fetchData()
        val catalog = CatalogValidator.decodeValidated(data)
        runCatching { cache?.save(data, nowEpochMs) }
        return catalog
    }
}

/**
 * What the app shows immediately, with no network: the last good remote copy if there is one, else
 * the bundled movies.json. [bundled] supplies the asset bytes. Port of `LocalCatalog`.
 */
object LocalCatalog {
    fun load(cache: CatalogCache?, bundled: () -> ByteArray): MovieCatalog =
        cache?.loadCatalog() ?: CatalogJson.decode(bundled())
}
