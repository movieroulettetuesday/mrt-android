package com.movieroulettetuesday.app.community

import com.movieroulettetuesday.app.net.Http
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Local stand-in for CloudKit Web Services (`records/query`) and the relay (which answers in the
 * `records/modify` response shape). Requests are recorded; [handler] returns (status, body, headers).
 */
class MockCloudKit : AutoCloseable {
    data class Request(val method: String, val path: String, val query: String?, val body: JsonObject?)
    data class Reply(val status: Int, val body: String, val headers: Map<String, String> = emptyMap())

    val requests = mutableListOf<Request>()
    var handler: (Request) -> Reply = { Reply(404, "{}") }
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    init {
        Http.allowLoopbackHttp = true
        server.createContext("/") { ex ->
            val raw = ex.requestBody.readBytes().decodeToString()
            val req = Request(ex.requestMethod, ex.requestURI.path, ex.requestURI.rawQuery,
                raw.takeIf { it.isNotBlank() }?.let { Json.parseToJsonElement(it).jsonObject })
            synchronized(requests) { requests += req }
            val reply = handler(req)
            reply.headers.forEach { (k, v) -> ex.responseHeaders.add(k, v) }
            ex.responseHeaders.add("Content-Type", "application/json")
            val bytes = reply.body.toByteArray()
            ex.sendResponseHeaders(reply.status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    val base: String get() = "http://127.0.0.1:${server.address.port}"

    override fun close() {
        server.stop(0)
        Http.allowLoopbackHttp = false
    }

    companion object {
        fun voteRecord(name: String, option: String) =
            """{"recordName":"$name","recordType":"PollVote","fields":{"pollId":{"value":"p","type":"STRING"},"option":{"value":"$option","type":"STRING"}}}"""

        fun ratingRecord(name: String, half: Int?, stars: Int?) = buildString {
            append("""{"recordName":"$name","recordType":"ListenerRating","fields":{"movieId":{"value":"m","type":"STRING"}""")
            half?.let { append(""","halfStars":{"value":$it,"type":"INT64"}""") }
            stars?.let { append(""","stars":{"value":$it,"type":"INT64"}""") }
            append("}}")
        }
    }
}
