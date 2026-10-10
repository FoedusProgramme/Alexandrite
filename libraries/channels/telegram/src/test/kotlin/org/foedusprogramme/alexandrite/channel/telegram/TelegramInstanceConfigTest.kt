package org.foedusprogramme.alexandrite.channel.telegram

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.foedusprogramme.alexandrite.channel.telegram.fake.FAKE_BOT_TOKEN
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TelegramInstanceConfigTest {
    private fun decode(json: String): TelegramInstanceConfig =
        Json.decodeFromString(TelegramInstanceConfig.serializer(), json)

    private fun withToken(more: String = "", token: String = FAKE_BOT_TOKEN): String =
        """{"botToken": "$token"${if (more.isEmpty()) "" else ", $more"}}"""

    /** The message that decoding [json] fails with, which quotes none of [values]. */
    private fun refusal(json: String, vararg values: String): String {
        val message = assertFailsWith<IllegalArgumentException> { decode(json) }.message.orEmpty()
        for (value in values) assertFalse(value in message, message)
        return message
    }

    @Test
    fun `a bot needs only its token`() {
        val config = decode(withToken())

        assertEquals(FAKE_BOT_TOKEN, config.botToken.reveal())
        assertEquals(emptyList(), config.adminUserIds)
        assertEquals(emptyList(), config.allowedChats)
        assertEquals("https://api.telegram.org", config.apiBase)
        assertEquals(listOf(25, 100, 30), config.polling.run { listOf(timeoutSeconds, limit, retryMaxSeconds) })
        assertTrue(config.streaming.enabled)
        assertEquals(listOf(1000L, 3000L), config.streaming.run { listOf(editIntervalMillis, groupEditIntervalMillis) })
        assertFalse(FAKE_BOT_TOKEN in config.toString())
        assertFalse(FAKE_BOT_TOKEN in config.botToken.toString())
    }

    @Test
    fun `a token that is empty or no bot token is refused without its value`() {
        assertTrue("botToken is empty" in refusal(withToken(token = "")))
        assertTrue("botToken is empty" in refusal(withToken(token = "   ")))
        val malformed = listOf(
            "AAFakeTokenForAlexandriteTests_0123456789",
            "1234567890AAFakeTokenForAlexandriteTests_0123456789",
            "12a4:AAFakeTokenForAlexandriteTests_0123456789",
            "1234567890:AAFakeTokenTooShort_0123",
            "1234567890:AAFakeTokenForAlexandriteTests!0123456789",
            " $FAKE_BOT_TOKEN",
        )
        for (token in malformed) {
            val message = refusal(withToken(token = token), token.trim(), token.substringAfter(':'))
            assertTrue("botToken must be a bot token" in message, message)
        }
    }

    @Test
    fun `user and chat ids are refused by their position without their values`() {
        val admin = refusal(withToken(""""adminUserIds": [7, -987654]"""), "987654")
        assertTrue("adminUserIds[1] must be a Telegram user id" in admin, admin)
        assertTrue("adminUserIds[0]" in refusal(withToken(""""adminUserIds": [0]""")))
        val chat = refusal(withToken(""""allowedChats": [5, -1001234567890, 0]"""), "1001234567890")
        assertTrue("allowedChats[2] must be a Telegram chat id" in chat, chat)

        val config = decode(withToken(""""adminUserIds": [7], "allowedChats": [7, -1001234567890]"""))
        assertEquals(listOf(7L), config.adminUserIds)
        assertEquals(listOf(7L, -1001234567890L), config.allowedChats)
    }

    @Test
    fun `allowedUsers is no key of the section`() {
        assertFailsWith<SerializationException> { decode(withToken(""""allowedUsers": [7]""")) }
    }

    @Test
    fun `apiBaseUrl is an absolute http URL without user info, query or fragment, kept without a trailing slash`() {
        val refused = listOf(
            "ftp://bots.example.com",
            "bots.example.com",
            "https://name:secret-word@bots.example.com",
            "https://bots.example.com/?secret-word=1",
            "https://bots.example.com/#secret-word",
            "http://",
            "https://bots example.com",
        )
        for (url in refused) {
            val message = refusal(withToken(""""apiBaseUrl": "$url""""), "secret-word", url)
            assertTrue("apiBaseUrl must be an http or https URL" in message, message)
        }

        val local = decode(withToken(""""apiBaseUrl": "http://127.0.0.1:8081/""""))
        assertEquals("http://127.0.0.1:8081", local.apiBase)
        val proxied = decode(withToken(""""apiBaseUrl": "https://bots.example.com/tg//""""))
        assertEquals("https://bots.example.com/tg", proxied.apiBase)
    }

    @Test
    fun `polling and streaming values outside their ranges are refused`() {
        val refused = mapOf(
            """"polling": {"timeoutSeconds": -1}""" to "polling.timeoutSeconds",
            """"polling": {"timeoutSeconds": 51}""" to "polling.timeoutSeconds",
            """"polling": {"limit": 0}""" to "polling.limit",
            """"polling": {"limit": 101}""" to "polling.limit",
            """"polling": {"retryMaxSeconds": 0}""" to "polling.retryMaxSeconds",
            """"polling": {"retryMaxSeconds": 301}""" to "polling.retryMaxSeconds",
            """"streaming": {"editIntervalMillis": -1}""" to "streaming.editIntervalMillis",
            """"streaming": {"editIntervalMillis": 60001}""" to "streaming.editIntervalMillis",
            """"streaming": {"groupEditIntervalMillis": 60001}""" to "streaming.groupEditIntervalMillis",
        )
        for ((json, key) in refused) {
            val message = refusal(withToken(json), "60001", "301", "101", "51")
            assertTrue("$key must lie between" in message, message)
        }

        val edges = decode(
            withToken(
                """"polling": {"timeoutSeconds": 0, "limit": 1, "retryMaxSeconds": 300},
                   "streaming": {"enabled": false, "editIntervalMillis": 0, "groupEditIntervalMillis": 60000}""",
            ),
        )
        assertEquals(listOf(0, 1, 300), edges.polling.run { listOf(timeoutSeconds, limit, retryMaxSeconds) })
        assertEquals(listOf(0L, 60000L), edges.streaming.run { listOf(editIntervalMillis, groupEditIntervalMillis) })
        val longest = decode(withToken(""""polling": {"timeoutSeconds": 50, "limit": 100}"""))
        assertEquals(listOf(50, 100), longest.polling.run { listOf(timeoutSeconds, limit) })
    }
}
