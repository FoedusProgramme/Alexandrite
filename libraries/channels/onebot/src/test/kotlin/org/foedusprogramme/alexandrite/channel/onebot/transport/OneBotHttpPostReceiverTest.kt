package org.foedusprogramme.alexandrite.channel.onebot.transport

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.foedusprogramme.alexandrite.channel.onebot.auth.OneBotAuth
import org.foedusprogramme.alexandrite.channel.onebot.auth.Value
import org.foedusprogramme.alexandrite.channel.onebot.protocol.event.OneBotEvent
import org.foedusprogramme.alexandrite.sdk.config.Secret
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OneBotHttpPostReceiverTest {
    private val secret = Value.of(Secret("signing-key"))
    private val receiver = OneBotHttpPostReceiver(settings()).also { it.start() }
    private val client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()

    @AfterTest
    fun close() {
        receiver.close()
    }

    @Test
    fun `a signed report is taken and answered with no content`() {
        val body = privateMessage()
        val response = post(body, OneBotAuth.signature(secret, body), "/")
        assertEquals(204, response.statusCode())
        assertEquals("", response.body())
        val event = runBlocking { withTimeout(5_000) { first() } }
        assertEquals(1515204254L, event.time)
        assertIs<OneBotEvent.Message.Private>(event)
    }

    @Test
    fun `a report without a signature is refused and nothing is reported`() {
        val response = post(privateMessage(), null, "/")
        assertEquals(403, response.statusCode())
        assertTrue(response.body().contains("signature"))
        assertEquals(0, receiver.reported())
    }

    @Test
    fun `a report whose body changed after signing is refused`() {
        val signature = OneBotAuth.signature(secret, privateMessage())
        val response = post("""{"time":1,"post_type":"meta_event","meta_event_type":"lifecycle"}""", signature, "/")
        assertEquals(403, response.statusCode())
        assertEquals(0, receiver.reported())
    }

    @Test
    fun `a report on another path is not this instance's`() {
        val body = privateMessage()
        val response = post(body, OneBotAuth.signature(secret, body), "/other")
        assertEquals(404, response.statusCode())
        assertEquals(0, receiver.reported())
    }

    @Test
    fun `a report that is no event is refused`() {
        val body = "{ not json"
        val response = post(body, OneBotAuth.signature(secret, body), "/")
        assertEquals(400, response.statusCode())
        assertEquals(0, receiver.reported())
    }

    @Test
    fun `a report that names no account is refused when the instance names one`() {
        val body = privateMessage().replace("\"self_id\": 10001000,", "\"self_id\": 0,")
        val response = postTo(receiver.port, body, OneBotAuth.signature(secret, body), "/", selfId = null)

        assertEquals(403, response.statusCode())
        assertEquals(0, receiver.reported())
    }

    @Test
    fun `a report that names its account only in the header is taken`() {
        // The header names of a request are folded to lower case when its head is read, so a lookup by the spelling
        // of the standard finds nothing. This case would pass for the wrong reason if the header were ignored: the
        // body below names the account the instance serves, so it is taken whichever of the two is read.
        val body = privateMessage()
        val response = post(body, OneBotAuth.signature(secret, body), "/")

        assertEquals(204, response.statusCode())
        assertEquals(1, receiver.reported())
    }

    @Test
    fun `a report of an account is taken when the instance names none`() {
        val open = OneBotHttpPostReceiver(settings(selfId = null)).also { it.start() }
        try {
            val body = privateMessage()
            val response = postTo(open.port, body, OneBotAuth.signature(secret, body), "/", selfId = null)

            assertEquals(204, response.statusCode())
            assertEquals(1, open.reported())
        } finally {
            open.close()
        }
    }

    @Test
    fun `a report of another account is refused when the instance names one`() {
        val named = OneBotHttpPostReceiver(settings(selfId = "10001000")).also { it.start() }
        try {
            val body = privateMessage().replace("\"self_id\": 10001000", "\"self_id\": 20002000")
            val response = postTo(
                named.port,
                body,
                OneBotAuth.signature(secret, body),
                "/",
                selfId = "20002000",
            )
            assertEquals(403, response.statusCode())
            assertEquals(0, named.reported())
        } finally {
            named.close()
        }
    }

    @Test
    fun `a report never answers with the secret or the token`() {
        val response = post(privateMessage(), "sha1=00", "/")
        assertEquals(403, response.statusCode())
        assertFalse(response.body().contains("signing-key"))
        assertFalse(response.body().contains("s3cr3t-token"))
    }

    private fun OneBotHttpPostReceiver.reported(): Int = reportedCount

    private fun post(body: String, signature: String?, path: String): HttpResponse<String> =
        postTo(receiver.port, body, signature, path, selfId = "10001000")

    private fun postTo(
        port: Int,
        body: String,
        signature: String?,
        path: String,
        selfId: String?,
    ): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
            .header("Content-Type", "application/json")
            .apply { selfId?.let { header(OneBotAuth.SELF_ID, it) } }
            .POST(HttpRequest.BodyPublishers.ofString(body))
        signature?.let { builder.header(OneBotAuth.SIGNATURE, it) }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }
    private suspend fun first(): OneBotEvent = receiver.events.first()

    private fun settings(selfId: String? = "10001000"): OneBotSettings = OneBotSettings(
        endpoint = null,
        listenHost = "127.0.0.1",
        listenPort = 0,
        path = "/",
        token = Value.of(Secret("s3cr3t-token")),
        secret = secret,
        selfId = selfId,
        connectTimeoutMillis = 5_000,
        firstByteTimeoutMillis = 5_000,
        idleTimeoutMillis = 5_000,
        reconnectIntervalMillis = 100,
        eventCapacity = 16,
        rateLimitIntervalMillis = 0,
    )

    private fun privateMessage(): String = """
        {
          "time": 1515204254,
          "self_id": 10001000,
          "post_type": "message",
          "message_type": "private",
          "sub_type": "friend",
          "message_id": 12,
          "user_id": 12345678,
          "message": "hello",
          "raw_message": "hello",
          "sender": {"nickname": "someone"}
        }
    """.trimIndent()
}
