package org.foedusprogramme.alexandrite.internal.http

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.net.ServerSocket
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class HttpTransportTest {
    private val server = TestServer()
    private val now = Instant.parse("2026-10-08T12:00:00Z")
    private val transport = HttpTransport(SHORT, Clock.fixed(now, ZoneOffset.UTC))

    @AfterTest
    fun close() {
        transport.close()
        server.close()
    }

    private fun post(path: String = "/chat", key: String = "sk-test-secret") = HttpCall(
        "POST",
        server.uri.resolve(path),
        mapOf("authorization" to "Bearer $key", "content-type" to "application/json"),
        """{"stream":true}""",
        listOf(key),
    )

    @Test
    fun `events stream in order, the last one also without a blank line`() = runBlocking {
        server.handle { it.chunk("data: a\n\n").chunk(": keep-alive\n\ndata: b").chunk("\n\nevent: x\ndata: c").end() }

        val events = transport.open(post()) { response -> response.events().toList() }

        assertEquals(listOf("a", "b", "c"), events.map { it.data })
        assertEquals("x", events.last().type)
        val request = server.nextRequest()
        assertEquals("POST", request.method)
        assertEquals("/chat", request.path)
        assertEquals("Bearer sk-test-secret", request.headers["authorization"])
        assertEquals("""{"stream":true}""", request.body)
    }

    @Test
    fun `send reads a whole body`() = runBlocking {
        server.handle { it.header("content-type", "application/json").chunk("""{"data":""").chunk("[]}").end() }

        assertEquals("""{"data":[]}""", transport.send(HttpCall("GET", server.uri.resolve("/models"))))
    }

    @Test
    fun `a status error carries its kind, retry delay, request id and a masked body`() = runBlocking {
        server.handle {
            it.status(429).header("retry-after", "7").header("x-request-id", "req-1")
                .chunk("""{"error":{"message":"slow down, key sk-test-secret"}}""").end()
        }

        val error = assertFailsWith<HttpException> { transport.open(post()) { } }

        assertEquals(HttpFailureKind.RATE_LIMITED, error.kind)
        assertEquals(429, error.status)
        assertEquals(7.seconds, error.retryAfter)
        assertEquals("req-1", error.requestId)
        assertFalse(error.afterResponse)
        assertFalse("sk-test-secret" in error.message.orEmpty(), error.message)
        assertFalse("sk-test-secret" in error.body.orEmpty(), error.body)
        assertTrue("slow down" in error.body.orEmpty())
    }

    @Test
    fun `a retry date counts from now`() = runBlocking {
        server.handle { it.status(503).header("retry-after", "Thu, 08 Oct 2026 12:00:30 GMT").end() }

        val error = assertFailsWith<HttpException> { transport.open(post()) { } }

        assertEquals(HttpFailureKind.OVERLOADED, error.kind)
        assertEquals(30.seconds, error.retryAfter)
        assertEquals(null, error.body)
    }

    @Test
    fun `a refused connection is a connection failure`() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }

        val error = assertFailsWith<HttpException> {
            transport.open(HttpCall("GET", URI("http://127.0.0.1:$port/x"))) { }
        }

        assertEquals(HttpFailureKind.CONNECTION, error.kind)
        assertTrue(error.kind.retryable)
        assertFalse(error.afterResponse)
    }

    @Test
    fun `cancelling the reader closes the connection at once`() = runBlocking {
        val closed = CompletableDeferred<Boolean>()
        server.handle { exchange ->
            exchange.chunk("data: a\n\n")
            closed.complete(exchange.awaitClientClose(3.seconds))
        }
        val first = CompletableDeferred<Unit>()
        val reader = launch(Dispatchers.Default) {
            transport.open(post()) { response -> response.events().collect { first.complete(Unit) } }
        }
        first.await()

        reader.cancelAndJoin()

        assertTrue(withContext(Dispatchers.IO) { closed.await() }, "The server still holds the connection.")
    }

    @Test
    fun `a server that sends nothing in time times out before the response`() = runBlocking {
        val quick = HttpTransport(QUICK)
        server.handle { Thread.sleep(1_000) }

        val error = quick.use { assertFailsWith<HttpException> { it.open(post()) { } } }

        assertEquals(HttpFailureKind.TIMEOUT, error.kind)
        assertFalse(error.afterResponse)
    }

    @Test
    fun `a body that pauses too long times out after the response`() = runBlocking {
        val quick = HttpTransport(QUICK)
        server.handle { it.chunk("data: a\n\n").pause(1.seconds).end() }
        val seen = mutableListOf<String>()

        val error = quick.use {
            assertFailsWith<HttpException> {
                it.open(post()) { response -> response.events().collect { event -> seen += event.data } }
            }
        }

        assertEquals(listOf("a"), seen)
        assertEquals(HttpFailureKind.TIMEOUT, error.kind)
        assertTrue(error.afterResponse)
    }

    @Test
    fun `a body cut off is a connection failure after the response`() = runBlocking {
        server.handle { it.chunk("data: a\n\n").drop() }
        val seen = mutableListOf<String>()

        val error = assertFailsWith<HttpException> {
            transport.open(post()) { response -> response.events().collect { event -> seen += event.data } }
        }

        assertEquals(listOf("a"), seen)
        assertEquals(HttpFailureKind.CONNECTION, error.kind)
        assertTrue(error.afterResponse)
        assertEquals(200, error.status)
    }

    @Test
    fun `a call prints neither credentials nor query`() {
        val call = HttpCall("GET", URI("https://user:pw@example.com:8443/v1/models?key=secret#x"))

        assertEquals("GET https://example.com:8443/v1/models", call.toString())
    }

    @Test
    fun `calls run in parallel on one transport`() = runBlocking {
        repeat(2) { server.handle { it.chunk("data: ok\n\n").end() } }

        val results = List(2) {
            async(Dispatchers.Default) {
                transport.open(post()) { response -> response.events().toList().single().data }
            }
        }.map { it.await() }

        assertEquals(listOf("ok", "ok"), results)
    }

    @Test
    fun `statuses map to kinds`() {
        val kinds = listOf(400, 401, 402, 403, 404, 408, 409, 413, 422, 429, 500, 502, 503, 504, 529, 302)
            .associateWith { HttpFailureKind.of(it) }

        assertEquals(
            mapOf(
                400 to HttpFailureKind.INVALID_REQUEST,
                401 to HttpFailureKind.AUTHENTICATION,
                402 to HttpFailureKind.QUOTA_EXHAUSTED,
                403 to HttpFailureKind.PERMISSION_DENIED,
                404 to HttpFailureKind.NOT_FOUND,
                408 to HttpFailureKind.TIMEOUT,
                409 to HttpFailureKind.SERVER_ERROR,
                413 to HttpFailureKind.REQUEST_TOO_LARGE,
                422 to HttpFailureKind.INVALID_REQUEST,
                429 to HttpFailureKind.RATE_LIMITED,
                500 to HttpFailureKind.SERVER_ERROR,
                502 to HttpFailureKind.SERVER_ERROR,
                503 to HttpFailureKind.OVERLOADED,
                504 to HttpFailureKind.TIMEOUT,
                529 to HttpFailureKind.OVERLOADED,
                302 to HttpFailureKind.PROTOCOL,
            ),
            kinds,
        )
    }

    @Test
    fun `retry delays come from milliseconds, seconds or a date`() {
        fun headers(vararg pairs: Pair<String, String>) =
            java.net.http.HttpHeaders.of(pairs.associate { it.first to listOf(it.second) }) { _, _ -> true }

        assertEquals(1.5.seconds, retryAfter(headers("retry-after-ms" to "1500", "retry-after" to "9"), now))
        assertEquals(2.seconds, retryAfter(headers("retry-after" to "2"), now))
        assertEquals(0.seconds, retryAfter(headers("retry-after" to "Thu, 08 Oct 2026 11:00:00 GMT"), now))
        assertEquals(null, retryAfter(headers("retry-after" to "soon"), now))
        assertEquals(null, retryAfter(headers(), now))
        assertEquals("abc", requestId(headers("request-id" to "abc")))
    }
}
