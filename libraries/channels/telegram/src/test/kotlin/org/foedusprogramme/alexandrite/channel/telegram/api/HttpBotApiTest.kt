package org.foedusprogramme.alexandrite.channel.telegram.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.foedusprogramme.alexandrite.channel.telegram.OTHER_TOKEN
import org.foedusprogramme.alexandrite.channel.telegram.QUICK
import org.foedusprogramme.alexandrite.channel.telegram.api.BotApiFailure.BAD_REQUEST
import org.foedusprogramme.alexandrite.channel.telegram.api.BotApiFailure.CHAT_GONE
import org.foedusprogramme.alexandrite.channel.telegram.api.BotApiFailure.CONFLICT
import org.foedusprogramme.alexandrite.channel.telegram.api.BotApiFailure.EDIT_TARGET_GONE
import org.foedusprogramme.alexandrite.channel.telegram.api.BotApiFailure.ENTITIES_REJECTED
import org.foedusprogramme.alexandrite.channel.telegram.api.BotApiFailure.FORBIDDEN
import org.foedusprogramme.alexandrite.channel.telegram.api.BotApiFailure.NOT_MODIFIED
import org.foedusprogramme.alexandrite.channel.telegram.api.BotApiFailure.PROTOCOL
import org.foedusprogramme.alexandrite.channel.telegram.api.BotApiFailure.RATE_LIMITED
import org.foedusprogramme.alexandrite.channel.telegram.api.BotApiFailure.TOO_LONG
import org.foedusprogramme.alexandrite.channel.telegram.api.BotApiFailure.TRANSIENT
import org.foedusprogramme.alexandrite.channel.telegram.api.BotApiFailure.UNAUTHORIZED
import org.foedusprogramme.alexandrite.channel.telegram.configOf
import org.foedusprogramme.alexandrite.channel.telegram.fake.FakeBotApi
import org.foedusprogramme.alexandrite.channel.telegram.fake.FakeUser
import org.foedusprogramme.alexandrite.channel.telegram.logged
import org.foedusprogramme.alexandrite.channel.telegram.reply.SendLimiter
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class HttpBotApiTest {
    private val fake = FakeBotApi()
    private val api = HttpBotApi(configOf(fake))
    private val ada = FakeUser(42, "Ada", "Lovelace", "ada_test")
    private val direct = fake.direct(ada)

    @AfterTest
    fun close() {
        api.close()
        fake.close()
    }

    private fun failure(block: suspend HttpBotApi.() -> Unit): BotApiException = runBlocking {
        assertFailsWith<BotApiException> { api.block() }
    }

    private fun sendFailure(text: String = "Hello", chat: Long = direct.id): BotApiException =
        failure { sendMessage(SendMessage(chat, text)) }

    // Envelopes.

    @Test
    fun `getMe reads the bot`() = runBlocking {
        val me = api.getMe()

        assertEquals(fake.botId, me.id)
        assertTrue(me.isBot)
        assertEquals("alexandrite_test_bot", me.username)
        assertEquals(true, me.canReadAllGroupMessages)
        assertEquals(JsonObject(emptyMap()), fake.calls("getMe").single().params)
    }

    @Test
    fun `getWebhookInfo reads the webhook`() = runBlocking {
        assertEquals("", api.getWebhookInfo().url)

        fake.webhookUrl = "https://hooks.example.com/bot"
        fake.send(ada, direct, "Hi")

        val info = api.getWebhookInfo()
        assertEquals("https://hooks.example.com/bot", info.url)
        assertEquals(1, info.pendingUpdateCount)
    }

    @Test
    fun `sendMessage sends each field it is given and reads the message`() = runBlocking {
        val asked = fake.send(ada, direct, "What is 2 + 2?").id()

        val message = api.sendMessage(
            SendMessage(
                direct.id,
                "It is 4.",
                entities = listOf(MessageEntity("bold", 6, 1)),
                linkPreviewOptions = LinkPreviewOptions(isDisabled = true),
                replyParameters = ReplyParameters(asked, allowSendingWithoutReply = true),
            ),
        )

        assertEquals("It is 4.", message.text)
        assertEquals(direct.id, message.chat.id)
        assertEquals(fake.botId, message.from?.id)
        assertEquals(asked, message.replyToMessage?.messageId)
        assertEquals("bold", message.entities?.single()?.type)
        val expected = buildJsonObject {
            put("chat_id", direct.id)
            put("text", "It is 4.")
            put("entities", buildJsonArray { add(entity("bold", 6, 1)) })
            put("link_preview_options", buildJsonObject { put("is_disabled", true) })
            put(
                "reply_parameters",
                buildJsonObject {
                    put("message_id", asked)
                    put("allow_sending_without_reply", true)
                },
            )
        }
        assertEquals(expected, fake.calls("sendMessage").single().params)
        val kept = fake.botMessage(direct.id, message.messageId)!!
        assertEquals(asked, kept.replyTo)
        assertEquals(0, kept.edits)
    }

    @Test
    fun `editMessageText edits the message in place`() = runBlocking {
        val sent = api.sendMessage(SendMessage(direct.id, "Draft"))

        val edited = api.editMessageText(
            EditMessageText(direct.id, sent.messageId, "Final", entities = listOf(MessageEntity("italic", 0, 5))),
        )

        assertEquals(sent.messageId, edited.messageId)
        assertEquals("Final", edited.text)
        assertTrue(edited.editDate != null)
        val kept = fake.botMessage(direct.id, sent.messageId)!!
        assertEquals("Final", kept.text)
        assertEquals(1, kept.edits)
        assertEquals(JsonArray(listOf(entity("italic", 0, 5))), kept.entities)
    }

    @Test
    fun `sendChatAction shows typing in a topic`() = runBlocking {
        val forum = fake.group(-1001, "Forum", forum = true)
        val topic = fake.topic(forum, "Builds", ada)

        api.sendChatAction(forum.id, topic.threadId)
        api.sendChatAction(direct.id, null)

        val params = fake.calls("sendChatAction").map { it.params }
        assertEquals(
            listOf(
                buildJsonObject {
                    put("chat_id", forum.id)
                    put("message_thread_id", topic.threadId)
                    put("action", "typing")
                },
                buildJsonObject {
                    put("chat_id", direct.id)
                    put("action", "typing")
                },
            ),
            params,
        )
    }

    @Test
    fun `getUpdates reads the queued updates and confirms those below its offset`() = runBlocking {
        fake.send(ada, direct, "/help@alexandrite_test_bot now")
        fake.send(ada, direct, "Thanks")

        val first = api.getUpdates(null, 100, 0)
        val again = api.getUpdates(first.last().updateId, 100, 0)
        val none = api.getUpdates(first.last().updateId + 1, 100, 0)

        assertEquals(listOf("/help@alexandrite_test_bot now", "Thanks"), first.map { it.message?.text })
        val command = first.first().message!!.entities!!.single()
        assertEquals(listOf("bot_command", "0", "26"), listOf(command.type, "${command.offset}", "${command.length}"))
        assertEquals(42L, first.first().message?.from?.id)
        assertEquals("private", first.first().message?.chat?.type)
        assertEquals(listOf("Thanks"), again.map { it.message?.text })
        assertEquals(emptyList(), none)
        assertEquals(listOf(null, first.last().updateId, first.last().updateId + 1), fake.offsets)
        assertEquals(first.last().updateId + 1, fake.confirmedOffset)
        val params = fake.calls("getUpdates").first().params
        assertEquals(
            buildJsonObject {
                put("limit", 100)
                put("timeout", 0)
                put("allowed_updates", buildJsonArray { add(JsonPrimitive("message")) })
            },
            params,
        )
    }

    @Test
    fun `an update that does not read is passed on empty under its id, and the ones around it read`() = runBlocking {
        val message = """{"message_id": 1, "date": 0, "chat": {"id": 42, "type": "private"}, "text": "Hello"}"""
        val broken = """{"message_id": 2, "date": "yesterday", "chat": {"id": 42, "type": "private"}}"""
        val result = listOf(5 to message, 6 to broken, 7 to message)
            .joinToString { (id, update) -> """{"update_id": $id, "message": $update}""" }
        fake.respond("getUpdates", 200, """{"ok": true, "result": [$result]}""")

        lateinit var updates: List<Update>
        val lines = logged { updates = api.getUpdates(null, 100, 0) }

        assertEquals(listOf(5L, 6L, 7L), updates.map { it.updateId })
        assertEquals(listOf("Hello", null, "Hello"), updates.map { it.message?.text })
        assertEquals(listOf("WARN Update 6 does not read and is skipped"), lines.filter { it.startsWith("WARN") })
        assertFalse(lines.any { "yesterday" in it })
    }

    @Test
    fun `an update without an id fails the poll`() {
        fake.respond("getUpdates", 200, """{"ok": true, "result": [{"update_id": 5}, {"message": {}}]}""")

        assertEquals(PROTOCOL, failure { getUpdates(null, 100, 0) }.failure)
    }

    @Test
    fun `a long poll is held until an update arrives`() = runBlocking {
        val started = TimeSource.Monotonic.markNow()
        val poll = async(Dispatchers.IO) { api.getUpdates(null, 100, 10) }
        fake.await { calls("getUpdates").singleOrNull() }

        fake.send(ada, direct, "Late")

        assertEquals(listOf("Late"), poll.await().map { it.message?.text })
        assertTrue(started.elapsedNow() < 5.seconds)
    }

    @Test
    fun `a long poll outlasts the first-byte timeout that ends any other call`() = runBlocking {
        HttpBotApi(configOf(fake), QUICK).use { quick ->
            val started = TimeSource.Monotonic.markNow()

            assertEquals(emptyList(), quick.getUpdates(null, 100, 1))

            assertTrue(started.elapsedNow() >= 900.milliseconds)
            fake.delay("getMe", 2.seconds)
            val slow = assertFailsWith<BotApiException> { quick.getMe() }
            assertEquals(TRANSIENT, slow.failure)
        }
    }

    // Classification: one test per row.

    @Test
    fun `400 message is not modified`() {
        val sent = runBlocking { api.sendMessage(SendMessage(direct.id, "Same")) }

        val error = failure { editMessageText(EditMessageText(direct.id, sent.messageId, "Same")) }

        assertEquals(NOT_MODIFIED, error.failure)
        assertTrue(error.description!!.startsWith("Bad Request: message is not modified"))
    }

    @Test
    fun `400 message to edit not found, message can't be edited`() {
        val own = fake.send(ada, direct, "Mine").id()

        val gone = failure { editMessageText(EditMessageText(direct.id, 999, "x")) }
        val foreign = failure { editMessageText(EditMessageText(direct.id, own, "x")) }

        assertEquals(listOf(EDIT_TARGET_GONE, EDIT_TARGET_GONE), listOf(gone.failure, foreign.failure))
        assertEquals("Bad Request: message can't be edited", foreign.description)
    }

    @Test
    fun `400 can't parse entities and other entity errors`() {
        val outside = failure {
            sendMessage(SendMessage(direct.id, "Hi", entities = listOf(MessageEntity("bold", 0, 9))))
        }
        fake.fail("sendMessage", 400, "Bad Request: ENTITY_BOUNDS_INVALID")
        val bounds = sendFailure()

        assertEquals(listOf(ENTITIES_REJECTED, ENTITIES_REJECTED), listOf(outside.failure, bounds.failure))
        assertTrue(outside.description!!.startsWith("Bad Request: can't parse entities"))
    }

    @Test
    fun `400 message is too long, text is too long`() {
        val long = sendFailure("x".repeat(4097))
        fake.fail("editMessageText", 400, "Bad Request: text is too long")
        val edit = failure { editMessageText(EditMessageText(direct.id, 1, "x")) }

        assertEquals(listOf(TOO_LONG, TOO_LONG), listOf(long.failure, edit.failure))
        assertEquals("Bad Request: message is too long", long.description)
    }

    @Test
    fun `400 chat not found, message thread not found, or a migration`() {
        val forum = fake.group(-1002, "Forum", forum = true)
        val chat = sendFailure(chat = 404)
        val thread = failure { sendMessage(SendMessage(forum.id, "Hi", messageThreadId = 77)) }
        fake.fail("sendMessage", 400, "Bad Request: group chat was upgraded to a supergroup chat", migrateTo = -1003)
        val migrated = sendFailure()

        assertEquals(listOf(CHAT_GONE, CHAT_GONE, CHAT_GONE), listOf(chat.failure, thread.failure, migrated.failure))
        assertEquals("Bad Request: chat not found", chat.description)
        assertEquals("Bad Request: message thread not found", thread.description)
        assertEquals(-1003L, migrated.migrateTo)
        assertNull(chat.migrateTo)
    }

    @Test
    fun `other 400 and other 4xx`() {
        val reply = failure {
            sendMessage(SendMessage(direct.id, "Hi", replyParameters = ReplyParameters(999)))
        }
        fake.respond("sendMessage", 400, "")
        val bare = sendFailure()
        fake.fail("sendMessage", 404, "Not Found")
        val missing = sendFailure()

        assertEquals(listOf(BAD_REQUEST, BAD_REQUEST, BAD_REQUEST), listOf(reply, bare, missing).map { it.failure })
        assertEquals("Bad Request: message to be replied not found", reply.description)
        assertNull(bare.description)
        assertEquals("sendMessage failed: HTTP 400", bare.message)
    }

    @Test
    fun `401, and 404 on getMe`() = runBlocking {
        val refused = HttpBotApi(configOf(fake, OTHER_TOKEN)).use { other ->
            assertFailsWith<BotApiException> { other.sendMessage(SendMessage(direct.id, "Hi")) }
        }
        fake.fail("getMe", 404, "Not Found")
        val unknown = assertFailsWith<BotApiException> { api.getMe() }

        assertEquals(listOf(UNAUTHORIZED, UNAUTHORIZED), listOf(refused.failure, unknown.failure))
        assertEquals("Unauthorized", refused.description)
    }

    @Test
    fun `403`() {
        fake.fail("sendMessage", 403, "Forbidden: bot was blocked by the user")

        val error = sendFailure()

        assertEquals(FORBIDDEN, error.failure)
        assertEquals("sendMessage failed: Forbidden: bot was blocked by the user", error.message)
    }

    @Test
    fun `409`() {
        fake.fail("getUpdates", 409, "Conflict: terminated by other getUpdates request")

        assertEquals(CONFLICT, failure { getUpdates(null, 100, 0) }.failure)
    }

    @Test
    fun `429 with retry_after, else the header's wait, else a second`() {
        fake.fail("sendMessage", 429, "Too Many Requests: retry after 7", retryAfter = 7)
        val asked = sendFailure()
        fake.respond(
            "sendMessage",
            429,
            """{"ok":false,"error_code":429,"description":"Too Many Requests"}""",
            mapOf("content-type" to "application/json", "retry-after" to "3"),
        )
        val header = sendFailure()
        fake.respond("sendMessage", 429, "Too Many Requests")
        val bare = sendFailure()

        assertEquals(listOf(RATE_LIMITED, RATE_LIMITED, RATE_LIMITED), listOf(asked, header, bare).map { it.failure })
        assertEquals(listOf(7.seconds, 3.seconds, 1.seconds), listOf(asked, header, bare).map { it.retryAfter })
    }

    @Test
    fun `5xx, a broken connection and a timeout`() = runBlocking {
        fake.respond("sendMessage", 502, "<html>Bad Gateway</html>", mapOf("content-type" to "text/html"))
        val server = assertFailsWith<BotApiException> { api.sendMessage(SendMessage(direct.id, "Hi")) }
        fake.breakConnection("getMe")
        val broken = assertFailsWith<BotApiException> { api.getMe() }
        val timedOut = HttpBotApi(configOf(fake), QUICK).use { quick ->
            fake.delay("getWebhookInfo", 2.seconds)
            assertFailsWith<BotApiException> { quick.getWebhookInfo() }
        }

        assertEquals(listOf(TRANSIENT, TRANSIENT, TRANSIENT), listOf(server, broken, timedOut).map { it.failure })
        assertEquals("sendMessage failed: HTTP 502", server.message)
        assertEquals("getMe failed: the connection failed", broken.message)
        assertEquals("getWebhookInfo failed: no answer in time", timedOut.message)
    }

    @Test
    fun `a 2xx answer that is not ok, an unreadable answer or a redirect`() {
        val answers = listOf(
            200 to """{"ok":false,"description":"Strange"}""",
            200 to "<html>Login</html>",
            200 to """{"ok":true}""",
            200 to """{"ok":true,"result":{"id":"not a number"}}""",
            302 to "",
        )
        for ((status, body) in answers) {
            fake.respond("getMe", status, body, mapOf("location" to "https://example.com/elsewhere"))

            assertEquals(PROTOCOL, failure { getMe() }.failure, body)
        }
    }

    @Test
    fun `classify matches descriptions whatever their case`() {
        assertEquals(NOT_MODIFIED, classify("editMessageText", 400, "Bad Request: MESSAGE IS NOT MODIFIED", null))
        assertEquals(BAD_REQUEST, classify("getUpdates", 404, null, null))
        assertEquals(PROTOCOL, classify("getMe", 101, null, null))
    }

    // The limiter's 429.

    @Test
    fun `a 429 is waited out and the call made once more through the limiter`() = runBlocking {
        val limiter = SendLimiter()
        fake.fail("sendMessage", 429, "Too Many Requests: retry after 1", retryAfter = 1)
        val started = TimeSource.Monotonic.markNow()

        val message = limiter.message(direct.id) { api.sendMessage(SendMessage(direct.id, "Hello")) }

        assertEquals("Hello", message.text)
        assertTrue(started.elapsedNow() >= 1.seconds)
        assertEquals(2, fake.calls("sendMessage").size)
        assertEquals(1, fake.botMessages(direct.id).size)
    }

    @Test
    fun `a second 429 ends the call RATE_LIMITED, and a chat action gets no second try`() = runBlocking {
        val limiter = SendLimiter()
        repeat(2) { fake.fail("editMessageText", 429, "Too Many Requests: retry after 1", retryAfter = 1) }
        fake.fail("sendChatAction", 429, "Too Many Requests: retry after 1", retryAfter = 1)

        val edit = assertFailsWith<BotApiException> {
            limiter.message(direct.id) { api.editMessageText(EditMessageText(direct.id, 1, "x")) }
        }
        val action = assertFailsWith<BotApiException> {
            limiter.action(direct.id) { api.sendChatAction(direct.id, null) }
        }

        assertEquals(listOf(RATE_LIMITED, RATE_LIMITED), listOf(edit.failure, action.failure))
        assertEquals(2, fake.calls("editMessageText").size)
        assertEquals(1, fake.calls("sendChatAction").size)
    }

    @Test
    fun `a closed limiter does not wait a 429 out`() = runBlocking {
        val limiter = SendLimiter().apply { close() }
        fake.fail("sendMessage", 429, "Too Many Requests: retry after 30", retryAfter = 30)
        val started = TimeSource.Monotonic.markNow()

        val error = assertFailsWith<BotApiException> {
            limiter.message(direct.id) { api.sendMessage(SendMessage(direct.id, "Hi")) }
        }

        assertEquals(RATE_LIMITED, error.failure)
        assertTrue(started.elapsedNow() < 5.seconds)
        assertEquals(1, fake.calls("sendMessage").size)
        assertFalse(fake.botMessages().any())
    }

    private fun entity(type: String, offset: Int, length: Int) = buildJsonObject {
        put("type", type)
        put("offset", offset)
        put("length", length)
    }

    private fun JsonObject.id(): Long = getValue("message_id").jsonPrimitive.long
}
