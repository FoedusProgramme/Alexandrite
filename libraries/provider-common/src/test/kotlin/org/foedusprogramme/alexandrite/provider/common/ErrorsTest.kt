package org.foedusprogramme.alexandrite.provider.common

import kotlinx.coroutines.runBlocking
import org.foedusprogramme.alexandrite.provider.common.chat.ChatEndpoint
import org.foedusprogramme.alexandrite.provider.common.chat.ChatFlavor
import org.foedusprogramme.alexandrite.provider.common.chat.FIXTURE_API_KEY
import org.foedusprogramme.alexandrite.provider.common.chat.FIXTURE_TIMEOUTS
import org.foedusprogramme.alexandrite.provider.common.chat.chatEndpoint
import org.foedusprogramme.alexandrite.provider.common.chat.events
import org.foedusprogramme.alexandrite.sdk.model.ModelError
import org.foedusprogramme.alexandrite.sdk.model.ModelErrorKind
import org.foedusprogramme.alexandrite.sdk.model.ModelException
import org.foedusprogramme.alexandrite.sdk.model.ModelRequest
import org.foedusprogramme.alexandrite.sdk.model.RequestIds
import org.foedusprogramme.alexandrite.sdk.transcript.EndpointId
import org.foedusprogramme.alexandrite.sdk.transcript.ModelRef
import org.foedusprogramme.alexandrite.testkit.FakeModelServer
import org.foedusprogramme.alexandrite.testkit.FakeResponse
import org.foedusprogramme.alexandrite.testkit.testTurn
import org.foedusprogramme.alexandrite.testkit.testUserEntry
import java.net.ServerSocket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ErrorsTest {
    private val server = FakeModelServer()
    private val turn = testTurn()
    private val request = ModelRequest.builder(
        ModelRef(EndpointId("test"), "chat-model"),
        listOf(testUserEntry(turn)),
        0,
        RequestIds(turn.conversation, turn.id, 0),
    ).build()

    @AfterTest
    fun close() {
        server.close()
    }

    private fun error(status: Int, body: String, vararg headers: Pair<String, String>): ModelError {
        val response = FakeResponse.builder(status).body(body)
        headers.forEach { (name, value) -> response.header(name, value) }
        server.enqueue(response.build())
        return assertFailsWith<ModelException> {
            runBlocking { chatEndpoint(server, ChatFlavor.STANDARD).use { it.events(request) } }
        }.error
    }

    @Test
    fun `statuses map to their kinds, with retry advice`() {
        val cases = mapOf(
            400 to ModelErrorKind.INVALID_REQUEST,
            401 to ModelErrorKind.AUTHENTICATION,
            402 to ModelErrorKind.QUOTA_EXHAUSTED,
            403 to ModelErrorKind.PERMISSION_DENIED,
            404 to ModelErrorKind.MODEL_NOT_FOUND,
            408 to ModelErrorKind.TIMEOUT,
            413 to ModelErrorKind.CONTEXT_WINDOW_EXCEEDED,
            422 to ModelErrorKind.INVALID_REQUEST,
            500 to ModelErrorKind.SERVER_ERROR,
            503 to ModelErrorKind.OVERLOADED,
            504 to ModelErrorKind.TIMEOUT,
            529 to ModelErrorKind.OVERLOADED,
        )

        val errors = cases.keys.associateWith { error(it, "{}") }

        assertEquals(cases, errors.mapValues { it.value.kind })
        assertEquals(setOf(408, 500, 503, 504, 529), errors.filterValues { it.retryable }.keys)
        assertTrue(errors.values.none { it.outputStarted })
    }

    @Test
    fun `what the body says makes the kind precise`() {
        val quota = error(429, """{"error":{"message":"You exceeded your quota.","type":"insufficient_quota"}}""")
        val context = error(
            400,
            """{"error":{"message":"This model's maximum context length is 32768 tokens.","type":"BadRequestError"}}""",
        )
        val missing = error(400, """{"error":{"message":"Model Not Exist","type":"invalid_request_error"}}""")
        val filtered = error(400, """{"error":{"message":"Blocked.","code":"content_filter"}}""")

        assertEquals(ModelErrorKind.QUOTA_EXHAUSTED to false, quota.kind to quota.retryable)
        assertEquals("insufficient_quota", quota.rawType)
        assertEquals(ModelErrorKind.CONTEXT_WINDOW_EXCEEDED, context.kind)
        assertEquals(ModelErrorKind.MODEL_NOT_FOUND, missing.kind)
        assertEquals(ModelErrorKind.CONTENT_FILTERED, filtered.kind)
    }

    @Test
    fun `an echoed key never reaches the error`() {
        val error = error(
            401,
            """{"error":{"message":"Incorrect API key provided: $FIXTURE_API_KEY."}}""",
            "request-id" to "r-9",
        )

        assertEquals(ModelErrorKind.AUTHENTICATION, error.kind)
        assertEquals(401 to "r-9", error.status to error.requestId)
        assertFalse(FIXTURE_API_KEY in error.message, error.message)
        assertTrue("Incorrect API key provided" in error.message, error.message)
    }

    @Test
    fun `a backend that cannot be reached is a connection failure`() {
        val port = ServerSocket(0).use { it.localPort }
        val settings = EndpointSettings(baseUrl = "http://127.0.0.1:$port/v1", timeouts = FIXTURE_TIMEOUTS)

        val error = ChatEndpoint(EndpointId("test"), settings, ChatFlavor.STANDARD).use {
            assertFailsWith<ModelException> { runBlocking { it.events(request) } }
        }.error

        assertEquals(ModelErrorKind.CONNECTION, error.kind)
        assertTrue(error.retryable && !error.outputStarted)
    }
}
