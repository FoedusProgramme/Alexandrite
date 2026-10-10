package org.foedusprogramme.alexandrite.channel.telegram.api

import kotlinx.coroutines.runBlocking
import org.foedusprogramme.alexandrite.channel.telegram.OTHER_TOKEN
import org.foedusprogramme.alexandrite.channel.telegram.QUICK
import org.foedusprogramme.alexandrite.channel.telegram.TelegramInstanceConfig
import org.foedusprogramme.alexandrite.channel.telegram.configOf
import org.foedusprogramme.alexandrite.channel.telegram.fake.FakeBotApi
import org.foedusprogramme.alexandrite.channel.telegram.fake.FakeUser
import org.foedusprogramme.alexandrite.channel.telegram.logged
import org.foedusprogramme.alexandrite.channel.telegram.reply.SendLimiter
import org.foedusprogramme.alexandrite.sdk.config.Secret
import org.slf4j.LoggerFactory
import java.net.ServerSocket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class TokenMaskingTest {
    private val fake = FakeBotApi()
    private val config = configOf(fake)
    private val api = HttpBotApi(config, QUICK)
    private val chat = fake.direct(FakeUser(42, "Ada")).id
    private val failures = mutableListOf<BotApiException>()

    @AfterTest
    fun close() {
        api.close()
        fake.close()
    }

    private suspend fun fails(block: suspend () -> Unit) {
        failures += assertFailsWith<BotApiException> { block() }
    }

    private fun assertMasked(text: String) {
        for (token in listOf(fake.token, OTHER_TOKEN)) {
            assertFalse(token.substringAfter(':') in text, text)
        }
    }

    @Test
    fun `no failure path shows the token in an exception, a toString or a log line`() = runBlocking {
        val echo = "/bot${fake.token}/getMe"
        val closedPort = ServerSocket(0).use { it.localPort }
        val lines = logged {
            fake.fail("sendMessage", 400, "Bad Request: chat ${fake.token} not found")
            fails { api.sendMessage(SendMessage(chat, "Hi")) }
            fake.respond("getMe", 502, "<html>No upstream for $echo</html>")
            fails { api.getMe() }
            fake.respond("getMe", 200, """{"ok":false,"description":"$echo is down"}""")
            fails { api.getMe() }
            fake.respond("getMe", 200, "<html>$echo</html>")
            fails { api.getMe() }
            fake.respond("getMe", 200, """{"ok":true,"result":{"id":"${fake.token}"}}""")
            fails { api.getMe() }
            fake.respond("getMe", 302, "", mapOf("location" to "${fake.baseUrl}$echo"))
            fails { api.getMe() }
            fake.breakConnection("getMe")
            fails { api.getMe() }
            fake.delay("getMe", 2.seconds)
            fails { api.getMe() }
            repeat(2) { fake.fail("sendMessage", 429, "Too Many Requests: ${fake.token}", retryAfter = 1) }
            fails { SendLimiter().message(chat) { api.sendMessage(SendMessage(chat, "Hi")) } }
            HttpBotApi(configOf(fake, OTHER_TOKEN)).use { other -> fails { other.getMe() } }
            val gone = TelegramInstanceConfig(Secret(fake.token), apiBaseUrl = "http://127.0.0.1:$closedPort")
            HttpBotApi(gone, QUICK).use { unreachable -> fails { unreachable.getMe() } }
            val logger = LoggerFactory.getLogger(TokenMaskingTest::class.java)
            failures.forEach { logger.error("The call failed", it) }
        }

        assertEquals(11, failures.size)
        assertEquals(11, lines.count { it.startsWith("ERROR The call failed") })
        assertTrue(lines.any { it.startsWith("WARN") && "not retried" in it }, lines.joinToString("\n"))
        for (failure in failures) {
            assertMasked(failure.toString())
            assertMasked(failure.stackTraceToString())
            assertMasked(failure.description.orEmpty())
        }
        lines.forEach(::assertMasked)
        assertMasked(api.toString())
        assertMasked(config.toString())
        assertMasked(config.botToken.toString())
    }
}
