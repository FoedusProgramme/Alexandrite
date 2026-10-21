package org.unitedsoftware.alexandrite.channel.telegram

import kotlinx.serialization.json.Json
import org.unitedsoftware.alexandrite.channel.telegram.fake.FAKE_BOT_TOKEN
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
        val trusted = refusal(withToken(""""trustedUserIds": [7, -123456]"""), "123456")
        assertTrue("trustedUserIds[1] must be a Telegram user id" in trusted, trusted)
        val chat = refusal(withToken(""""allowedChats": [5, -1001234567890, 0]"""), "1001234567890")
        assertTrue("allowedChats[2] must be a Telegram chat id" in chat, chat)

        val config = decode(
            withToken(""""adminUserIds": [7], "trustedUserIds": [8], "allowedChats": [7, -1001234567890]"""),
        )
        assertEquals(listOf(7L), config.adminUserIds)
        assertEquals(listOf(8L), config.trustedUserIds)
        assertEquals(emptyList(), decode(withToken()).trustedUserIds)
        assertEquals(listOf(7L, -1001234567890L), config.allowedChats)
    }

    @Test
    fun `the menu is on and another plugin's command is opened by its name to whom it names`() {
        assertTrue(decode(withToken()).commandMenu)
        assertEquals(emptyMap(), decode(withToken()).commands)

        val config = decode(withToken(""""commandMenu": false, "commands": {"notes": "anyone", "purge": "direct"}"""))

        assertFalse(config.commandMenu)
        assertEquals(mapOf("notes" to Who.ANYONE, "purge" to Who.DIRECT), config.commands)
    }

    @Test
    fun `a malformed command name is refused in the commands, a default command's rule taken`() {
        for (name in listOf("Notes", "my-notes", "", "a".repeat(33))) {
            val message = refusal(withToken(""""commands": {"$name": "anyone"}"""))
            assertTrue("commands.$name must name a command" in message, message)
        }
        for (name in listOf("new", "model", "home", "help")) {
            assertEquals(mapOf(name to Who.ANYONE), decode(withToken(""""commands": {"$name": "anyone"}""")).commands)
        }
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
    fun `polling, streaming and approval values outside their ranges are refused`() {
        val refused = mapOf(
            """"polling": {"limit": 101}""" to "polling.limit",
            """"streaming": {"editIntervalMillis": 60001}""" to "streaming.editIntervalMillis",
            """"approvalMinutes": 1441""" to "approvalMinutes",
        )
        for ((json, key) in refused) {
            val message = refusal(withToken(json), "60001", "101", "1441")
            assertTrue("$key must lie between" in message, message)
        }

        val edges = decode(
            withToken(
                """"polling": {"timeoutSeconds": 50, "limit": 100, "retryMaxSeconds": 300},
                   "streaming": {"enabled": false, "editIntervalMillis": 0, "groupEditIntervalMillis": 60000},
                   "approvalMinutes": 1440""",
            ),
        )
        assertEquals(listOf(50, 100, 300), edges.polling.run { listOf(timeoutSeconds, limit, retryMaxSeconds) })
        assertEquals(listOf(0L, 60000L), edges.streaming.run { listOf(editIntervalMillis, groupEditIntervalMillis) })
        assertEquals(1440, edges.approvalMinutes)
    }
}
