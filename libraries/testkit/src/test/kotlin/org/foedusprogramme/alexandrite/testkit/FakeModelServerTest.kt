package org.foedusprogramme.alexandrite.testkit

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class FakeModelServerTest {
    private val server = FakeModelServer()
    private val client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()

    @AfterTest
    fun close() {
        server.close()
        client.close()
    }

    private fun post(path: String, body: String = """{"a":1}"""): HttpResponse<String> = client.send(
        HttpRequest.newBuilder(URI("${server.baseUrl}$path")).header("x-test", "yes")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    @Test
    fun `responses go to the first request of their path and requests are recorded`() {
        server.enqueue(fakeResponse { body("models") }, "/models")
        server.enqueue(
            fakeResponse {
                event("one")
                comment("keep-alive")
                pause(10.milliseconds)
                event("a\nb", "x")
            },
        )

        val chat = post("/chat?x=1")
        val models = post("/models")

        assertEquals("data: one\n\n: keep-alive\n\nevent: x\ndata: a\ndata: b\n\n", chat.body())
        assertEquals("text/event-stream", chat.headers().firstValue("content-type").orElse(null))
        assertEquals("models", models.body())
        assertEquals("application/json", models.headers().firstValue("content-type").orElse(null))
        val recorded = server.requests.first()
        assertEquals("POST", recorded.method)
        assertEquals("/chat?x=1", recorded.path)
        assertEquals("yes", recorded.header("X-Test"))
        assertEquals("1", recorded.json()["a"].toString())
        server.assertFinished()
    }

    @Test
    fun `statuses and headers reach the client`() {
        server.enqueue(FakeResponse.builder(429).header("Retry-After", "3").body("{}").build())

        val response = post("/chat")

        assertEquals(429, response.statusCode())
        assertEquals("3", response.headers().firstValue("retry-after").orElse(null))
    }

    @Test
    fun `a dropped response breaks the body`() {
        server.enqueue(fakeResponse { event("one") }.dropped())

        assertFailsWith<IOException> { post("/chat") }
    }

    @Test
    fun `a hanging response sees the client leave`() = runBlocking {
        server.enqueue(fakeResponse { event("one") }.hanging())
        val stream = withContext(Dispatchers.IO) {
            client.send(
                HttpRequest.newBuilder(URI("${server.baseUrl}/chat")).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream(),
            ).body()
        }
        withContext(Dispatchers.IO) { stream.readNBytes(6) }

        withContext(Dispatchers.IO) { stream.close() }

        assertTrue(server.requests.single().awaitClosed(5.seconds))
    }

    @Test
    fun `an unanswered request and an untaken response fail the server`() {
        server.enqueue(fakeResponse { body("x") }, "/models")

        assertEquals(500, post("/chat").statusCode())

        val error = assertFailsWith<AssertionError> { server.assertFinished() }
        assertTrue("has no queued response" in error.message.orEmpty(), error.message)
        assertTrue("No request took" in error.message.orEmpty(), error.message)
    }
}
