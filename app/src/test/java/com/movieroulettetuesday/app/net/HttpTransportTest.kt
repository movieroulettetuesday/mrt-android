package com.movieroulettetuesday.app.net

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.movieroulettetuesday.app.catalog.HttpStatusException
import com.movieroulettetuesday.app.episodes.EpisodeFixtures
import com.movieroulettetuesday.app.episodes.RssFeedParser
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.zip.GZIPOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The episode feed and the movie posters (episode artwork; catalog posterURL is null) both go
 * through [Http]. A resolver that lists an unreachable address before a working one must still
 * return the feed, and a gzip body must come back inflated or the RSS parser sees zero episodes.
 */
@RunWith(AndroidJUnit4::class)
class HttpTransportTest {
    private val savedResolve = Http.resolveHost

    @After
    fun reset() {
        Http.allowLoopbackHttp = false
        Http.resolveHost = savedResolve
    }

    @Test
    fun ipv4AddressesAreTriedBeforeIpv6() {
        val v4a = InetAddress.getByName("127.0.0.1")
        val v4b = InetAddress.getByName("10.0.0.1")
        val v6a = InetAddress.getByName("2001:db8::1")
        val v6b = InetAddress.getByName("2001:db8::2")
        assertEquals(listOf(v4a, v4b, v6a, v6b), Http.orderAddresses(listOf(v6a, v4a, v6b, v4b)))
    }

    @Test
    fun unreachableAddressDoesNotHideTheFeed() {
        val feed = EpisodeFixtures.resource("feed-snippet.xml")
        ScriptedServer { "HTTP/1.1 200 OK\r\nContent-Length: ${feed.size}\r\nConnection: close\r\n\r\n".toByteArray() + feed }.use { server ->
            // 127.0.0.2 refuses immediately; the working listener is 127.0.0.1. Same shape as an
            // AAAA record that fails before the A record is tried.
            Http.allowLoopbackHttp = true
            Http.resolveHost = {
                listOf(InetAddress.getByName("127.0.0.2"), InetAddress.getByName("127.0.0.1"))
            }
            val body = Http.get("http://127.0.0.1:${server.port}/2117292.rss", 2_000, accept = "application/rss+xml")
            val parsed = RssFeedParser.parse(body)
            assertEquals(2, parsed.episodes.size)
            assertTrue(parsed.episodes.all { it.imageURL?.startsWith("https://") == true })
            assertTrue(parsed.artworkURL?.startsWith("https://") == true)
        }
    }

    @Test
    fun gzipBodyIsInflatedBeforeTheCallerSeesIt() {
        val plain = "<rss>ok</rss>".toByteArray()
        val gz = ByteArrayOutputStream().also { raw -> GZIPOutputStream(raw).use { it.write(plain) } }.toByteArray()
        ScriptedServer {
            "HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\nContent-Length: ${gz.size}\r\nConnection: close\r\n\r\n".toByteArray() + gz
        }.use { server ->
            Http.allowLoopbackHttp = true
            assertEquals("<rss>ok</rss>", Http.get("http://127.0.0.1:${server.port}/x", 2_000).decodeToString())
        }
    }

    @Test
    fun chunkedBodyAndQueryStringArePreserved() {
        val captured = StringBuilder()
        ScriptedServer { req ->
            captured.append(req)
            val body = "img"
            "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n${body.length.toString(16)}\r\n$body\r\n0\r\n\r\n".toByteArray()
        }.use { server ->
            Http.allowLoopbackHttp = true
            val url = "http://127.0.0.1:${server.port}/0ivqancdb8l9ww2mlieeag1x8bdr?.jpg"
            assertEquals("img", Http.get(url, 2_000, accept = "image/*").decodeToString())
            assertTrue(captured.toString(), captured.startsWith("GET /0ivqancdb8l9ww2mlieeag1x8bdr?.jpg HTTP/1.1"))
        }
    }

    @Test
    fun redirectsAreFollowed() {
        ScriptedServer { req ->
            if (req.startsWith("GET /a ")) {
                "HTTP/1.1 302 Found\r\nLocation: /b\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()
            } else {
                "HTTP/1.1 200 OK\r\nContent-Length: 4\r\nConnection: close\r\n\r\nfeed".toByteArray()
            }
        }.use { server ->
            Http.allowLoopbackHttp = true
            assertEquals("feed", Http.get("http://127.0.0.1:${server.port}/a", 2_000).decodeToString())
        }
    }

    @Test
    fun cleartextAndHttpErrorsFail() {
        assertTrue(runCatching { Http.get("http://example.com/movies.json", 1_000) }.isFailure)
        ScriptedServer { "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray() }.use { server ->
            Http.allowLoopbackHttp = true
            val error = runCatching { Http.get("http://127.0.0.1:${server.port}/missing", 2_000) }.exceptionOrNull()
            assertTrue(error is HttpStatusException && error.code == 404)
        }
    }

    @Test
    fun postSendsTheBodyAndReadsTheErrorStatus() {
        val captured = StringBuilder()
        ScriptedServer { req ->
            captured.append(req)
            "HTTP/1.1 429 Too Many Requests\r\nRetry-After: 30\r\nContent-Length: 2\r\nConnection: close\r\n\r\nno".toByteArray()
        }.use { server ->
            Http.allowLoopbackHttp = true
            val response = Http.request(
                "POST",
                "http://127.0.0.1:${server.port}/v1/vote",
                """{"a":1}""".toByteArray(),
                "application/json",
                2_000,
            )
            assertEquals(429, response.code)
            assertEquals("30", response.headers.entries.first { it.key.equals("Retry-After", true) }.value.first())
            assertEquals("no", response.body.decodeToString())
            assertTrue(captured.toString(), captured.contains("""{"a":1}"""))
        }
    }
}

/** One request at a time, on 127.0.0.1. [reply] sees the raw request bytes as Latin-1. */
private class ScriptedServer(private val reply: (String) -> ByteArray) : AutoCloseable {
    private val server = ServerSocket()
    val port: Int
    private val thread: Thread

    init {
        server.bind(InetSocketAddress("127.0.0.1", 0))
        port = server.localPort
        thread = Thread {
            while (!server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (_: Exception) {
                    break
                }
                socket.use { serve(it) }
            }
        }.apply { isDaemon = true; name = "http-test"; start() }
    }

    private fun serve(socket: Socket) {
        socket.soTimeout = 2_000
        val input = socket.getInputStream()
        val raw = ByteArrayOutputStream()
        val window = ArrayList<Byte>(4)
        while (true) {
            val b = input.read()
            if (b < 0) break
            raw.write(b)
            window.add(b.toByte())
            if (window.size > 4) window.removeAt(0)
            if (window.size == 4 && window[0] == '\r'.code.toByte() && window[1] == '\n'.code.toByte() &&
                window[2] == '\r'.code.toByte() && window[3] == '\n'.code.toByte()
            ) break
        }
        val head = raw.toString(Charsets.ISO_8859_1)
        val length = Regex("(?i)Content-Length: (\\d+)").find(head)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        if (length > 0) {
            val body = ByteArray(length)
            var off = 0
            while (off < length) {
                val n = input.read(body, off, length - off)
                if (n < 0) break
                off += n
            }
            raw.write(body, 0, off)
        }
        socket.getOutputStream().apply {
            write(reply(raw.toString(Charsets.ISO_8859_1)))
            flush()
        }
    }

    override fun close() {
        server.close()
        thread.join(2_000)
    }
}
