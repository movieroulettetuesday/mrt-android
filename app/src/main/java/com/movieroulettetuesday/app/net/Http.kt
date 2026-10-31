package com.movieroulettetuesday.app.net

import com.movieroulettetuesday.app.catalog.HttpStatusException
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.net.UnknownHostException
import java.util.zip.GZIPInputStream
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Minimal HTTP client used by every network feature. HTTPS only (the manifest also forbids
 * cleartext). [allowLoopbackHttp] exists only so JVM tests can talk to a local mock server.
 *
 * Addresses are tried IPv4-first. Android resolves with `AI_ADDRCONFIG` and connects in that
 * order, and an emulator that has an IPv6 address gets the AAAA records for
 * `rss.buzzsprout.com` even when those routes do not work. That host is the episode feed, and
 * movie posters come from the episode artwork on it (`posterURL` in the catalog is null), so a
 * connect that dies on IPv6 leaves Episodes empty and every movie on its placeholder. IPv4 for
 * that host answers immediately; IPv6 here is "network is unreachable".
 */
object Http {
    @Volatile
    var allowLoopbackHttp: Boolean = false

    /** Test seam. Production resolves through the platform. */
    @Volatile
    internal var resolveHost: (String) -> List<InetAddress> = { host ->
        InetAddress.getAllByName(host).toList()
    }

    class Response(val code: Int, val body: ByteArray, val headers: Map<String, List<String>>)

    fun checkUrl(url: String) {
        val ok = url.startsWith("https://") ||
            (allowLoopbackHttp && (url.startsWith("http://127.0.0.1") || url.startsWith("http://localhost")))
        require(ok) { "HTTPS only" }
    }

    /** IPv4 first; order within each family stays as the resolver returned it. */
    internal fun orderAddresses(addresses: List<InetAddress>): List<InetAddress> {
        val v4 = addresses.filterIsInstance<Inet4Address>()
        val rest = addresses.filter { it !is Inet4Address }
        return v4 + rest
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
        var current = URL(url)
        var currentMethod = method
        var currentBody = body
        var currentType = contentType
        var hops = 0
        while (true) {
            val response = exchange(current, currentMethod, currentBody, currentType, timeoutMs, accept, headers)
            val location = header(response.headers, "Location")
            if (location == null || response.code !in REDIRECTS || hops == MAX_REDIRECTS) return response
            hops++
            val next = URL(current, location)
            checkUrl(next.toExternalForm())
            current = next
            // 303, and the historical 301/302 POST-to-GET switch. 307/308 keep the method and body.
            if (response.code == 303 || (response.code != 307 && response.code != 308 && currentMethod != "GET" && currentMethod != "HEAD")) {
                currentMethod = "GET"
                currentBody = null
                currentType = null
            }
        }
    }

    private fun exchange(
        url: URL,
        method: String,
        body: ByteArray?,
        contentType: String?,
        timeoutMs: Int,
        accept: String,
        headers: Map<String, String>,
    ): Response {
        val addresses = orderAddresses(resolveHost(url.host))
        if (addresses.isEmpty()) throw UnknownHostException(url.host)
        var last: IOException? = null
        for (addr in addresses) {
            val socket = try {
                open(addr, url, timeoutMs)
            } catch (e: IOException) {
                last = e
                continue
            }
            socket.use { return transact(it, url, method, body, contentType, accept, headers) }
        }
        throw last ?: UnknownHostException(url.host)
    }

    private fun open(addr: InetAddress, url: URL, timeoutMs: Int): Socket {
        val port = if (url.port != -1) url.port else if (url.protocol == "https") 443 else 80
        val tcp = Socket()
        try {
            tcp.connect(InetSocketAddress(addr, port), timeoutMs)
            tcp.soTimeout = timeoutMs
            if (url.protocol != "https") return tcp
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val ssl = factory.createSocket(tcp, url.host, port, true) as SSLSocket
            val params = ssl.sslParameters
            params.serverNames = listOf(SNIHostName(url.host))
            params.endpointIdentificationAlgorithm = "HTTPS"
            ssl.sslParameters = params
            ssl.startHandshake()
            return ssl
        } catch (e: IOException) {
            tcp.close()
            throw e
        }
    }

    private fun transact(
        socket: Socket,
        url: URL,
        method: String,
        body: ByteArray?,
        contentType: String?,
        accept: String,
        headers: Map<String, String>,
    ): Response {
        val target = requestTarget(url)
        val head = buildString {
            append(method).append(' ').append(target).append(" HTTP/1.1\r\n")
            append("Host: ").append(hostHeader(url)).append("\r\n")
            append("Accept: ").append(accept).append("\r\n")
            // Plain body. Android's HttpURLConnection asks for gzip and is supposed to inflate
            // it; when that inflation doesn't happen the RSS parser sees a binary feed and the
            // episode list stays empty. We still inflate a gzip body below if one arrives.
            append("Accept-Encoding: identity\r\n")
            append("Connection: close\r\n")
            append("User-Agent: MovieRouletteTuesday Android\r\n")
            if (contentType != null) append("Content-Type: ").append(contentType).append("\r\n")
            for ((k, v) in headers) {
                if (k.equals("Host", true) || k.equals("Content-Length", true) || k.equals("Connection", true)) continue
                append(k).append(": ").append(v).append("\r\n")
            }
            if (body != null) append("Content-Length: ").append(body.size).append("\r\n")
            append("\r\n")
        }
        val out = socket.getOutputStream()
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        if (body != null) out.write(body)
        out.flush()

        val input = socket.getInputStream()
        val status = readLine(input)
        val code = status.split(' ').getOrNull(1)?.toIntOrNull() ?: throw IOException("Bad status line")
        val responseHeaders = LinkedHashMap<String, MutableList<String>>()
        while (true) {
            val line = readLine(input)
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val name = line.substring(0, colon).trim()
            val value = line.substring(colon + 1).trim()
            responseHeaders.getOrPut(name) { mutableListOf() }.add(value)
        }
        val frozen = responseHeaders.mapValues { it.value.toList() }
        val raw = readBody(input, code, frozen)
        return Response(code, inflate(frozen, raw), frozen)
    }

    private fun readBody(input: InputStream, code: Int, headers: Map<String, List<String>>): ByteArray {
        if (code == 204 || code == 304) return ByteArray(0)
        val transfer = header(headers, "Transfer-Encoding").orEmpty()
        if (transfer.contains("chunked", ignoreCase = true)) return readChunked(input)
        val length = header(headers, "Content-Length")?.toIntOrNull()
        if (length != null) {
            if (length == 0) return ByteArray(0)
            if (length > MAX_BODY) throw IOException("Response too large")
            return readExact(input, length)
        }
        return readToLimit(input)
    }

    private fun readChunked(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val size = readLine(input).substringBefore(';').trim().toInt(16)
            if (size == 0) {
                while (readLine(input).isNotEmpty()) {
                    // Trailer headers. Nothing in them is used.
                }
                break
            }
            if (size < 0 || out.size() + size > MAX_BODY) throw IOException("Response too large")
            out.write(readExact(input, size))
            val cr = input.read()
            val lf = input.read()
            if (cr != '\r'.code || lf != '\n'.code) throw IOException("Bad chunk")
        }
        return out.toByteArray()
    }

    private fun inflate(headers: Map<String, List<String>>, body: ByteArray): ByteArray {
        val encoding = header(headers, "Content-Encoding").orEmpty()
        if (!encoding.contains("gzip", ignoreCase = true)) return body
        if (body.isEmpty()) return body
        return GZIPInputStream(body.inputStream()).use { it.readBytes() }
    }

    private fun requestTarget(url: URL): String {
        val external = url.toExternalForm()
        val scheme = external.indexOf("://")
        val path = if (scheme < 0) -1 else external.indexOf('/', scheme + 3)
        if (path < 0) return "/"
        val hash = external.indexOf('#', path)
        return if (hash < 0) external.substring(path) else external.substring(path, hash)
    }

    private fun hostHeader(url: URL): String {
        val defaultPort = if (url.protocol == "https") 443 else 80
        val port = if (url.port == -1) defaultPort else url.port
        return if (port == defaultPort) url.host else "${url.host}:$port"
    }

    private fun header(headers: Map<String, List<String>>, name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()

    private fun readLine(input: InputStream): String {
        val out = ByteArrayOutputStream()
        while (out.size() <= MAX_LINE) {
            val b = input.read()
            if (b < 0 || b == '\n'.code) break
            if (b != '\r'.code) out.write(b)
        }
        if (out.size() > MAX_LINE) throw IOException("Response header too long")
        return out.toByteArray().toString(Charsets.ISO_8859_1)
    }

    private fun readExact(input: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(buf, off, n - off)
            if (r < 0) throw IOException("Unexpected end of response")
            off += r
        }
        return buf
    }

    private fun readToLimit(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (out.size() + n > MAX_BODY) throw IOException("Response too large")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private val REDIRECTS = setOf(301, 302, 303, 307, 308)

    private const val MAX_REDIRECTS = 5
    private const val MAX_BODY = 20 * 1024 * 1024
    private const val MAX_LINE = 16 * 1024
}
