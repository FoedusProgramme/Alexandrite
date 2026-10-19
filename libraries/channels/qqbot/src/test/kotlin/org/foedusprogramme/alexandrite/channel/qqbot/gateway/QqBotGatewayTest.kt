package org.foedusprogramme.alexandrite.channel.qqbot.gateway

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.foedusprogramme.alexandrite.channel.qqbot.auth.QqBotTokens
import org.foedusprogramme.alexandrite.channel.qqbot.net.QqBotApi
import org.foedusprogramme.alexandrite.channel.qqbot.net.QqBotHttp
import org.foedusprogramme.alexandrite.channel.qqbot.testing.FakeApiServer
import org.foedusprogramme.alexandrite.channel.qqbot.testing.FakeGatewayServer
import org.foedusprogramme.alexandrite.channel.qqbot.testing.MutableClock
import org.foedusprogramme.alexandrite.channel.qqbot.testing.testConfig
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class QqBotGatewayTest {
    private val clock = MutableClock(Instant.parse("2026-07-21T00:00:00Z"))

    @Test
    fun `the gateway identifies, becomes ready and hands the pushed events on`() = runBlocking {
        val api = FakeApiServer()
        api.on(TOKEN_PATH, """{"access_token":"access","expires_in":"7200"}""")
        val platform = FakeGatewayServer().startAndAwait()
        val config = testConfig(api.base, gatewayUrl = platform.url)
        val http = QqBotHttp(config)
        try {
            val tokens = QqBotTokens(config, http, clock)
            val gateway = QqBotGateway(config, QqBotApi(http, tokens), tokens)
            gateway.onStart()

            val identify = assertNotNull(platform.awaitFrame(predicate = { it.contains("\"op\":2") }))
            assertTrue("QQBot access" in identify, identify)
            assertTrue("\"intents\":${1L shl 25}" in identify, identify)
            assertTrue("\"shard\":[0,1]" in identify, identify)
            assertEquals("bot1", gateway.botId)

            platform.push(EVENT)
            val frame = withTimeout(5_000) { gateway.frames.first { it.t == "C2C_MESSAGE_CREATE" } }
            assertEquals("event1", frame.id)
            assertEquals(
                "msg1",
                frame.d?.let {
                    org.foedusprogramme.alexandrite.channel.qqbot.protocol.QqBotWire.message(it)?.id
                },
            )

            gateway.onStop()
        } finally {
            platform.stop()
            http.close()
            api.close()
        }
    }

    @Test
    fun `a close code that ends the session is not resumed`() {
        assertTrue(dropsSession(4006))
        assertTrue(dropsSession(4007))
        assertTrue(dropsSession(4900))
        assertTrue(dropsSession(4913))
        assertFalse(dropsSession(4008))
        assertFalse(dropsSession(4009))
        assertFalse(dropsSession(4914))
    }

    private companion object {
        const val TOKEN_PATH = "/app/getAppAccessToken"

        const val EVENT = """{"id":"event1","op":0,"s":2,"t":"C2C_MESSAGE_CREATE","d":""" +
            """{"id":"msg1","author":{"id":"u1","user_openid":"u1","username":"Ada"},"content":"hi",""" +
            """"message_type":0,"timestamp":"2026-07-21T10:00:00+08:00"}}"""
    }
}
