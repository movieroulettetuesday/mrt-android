package com.movieroulettetuesday.app.net

import com.movieroulettetuesday.app.catalog.HttpStatusException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal HTTP client used by every network feature. HTTPS only (the manifest also forbids
 * cleartext). [allowLoopbackHttp] exists only so JVM tests can talk to a local mock server.
 */
object Http {
    @Volatile
    var allowLoopbackHttp: Boolean = false

    class Response(val code: Int, val body: ByteArray, val headers: Map<String, List<String>>)

    fun checkUrl(url: String) {
        val ok = url.startsWith("https://") ||
            (allowLoopbackHttp && (url.startsWith("http://127.0.0.1") || url.startsWith("http://localhost")))
        require(ok) { "HTTPS only" }
    }

    /** Throws [HttpStatusException] for non-2xx. */
    fun get(url: String, timeoutMs: Int, accept: String = "*/*", headers: Map<String, String> = emptyMap()): ByteArray {
        val r = request("GET", url, null, null, timeoutMs, accept, headers)
        if (r.code !in 200..299) throw HttpStatusException(r.code)
        return r.body
    }

    /** Returns the response whatever the status (callers map error bodies). */
    fun request(
        method: String,
        url: String,
        body: ByteArray?,
        contentType: String?,
        timeoutMs: Int,
        accept: String = "application/json",
        headers: Map<String, String> = emptyMap(),
    ): Response {
        checkUrl(url)
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.useCaches = false
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("Accept", accept)
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body != null) {
                conn.doOutput = true
                contentType?.let { conn.setRequestProperty("Content-Type", it) }
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            return Response(code, bytes, conn.headerFields.filterKeys { it != null })
        } finally {
            conn.disconnect()
        }
    }
}
