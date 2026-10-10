package org.foedusprogramme.alexandrite.channel.telegram.fake

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.foedusprogramme.alexandrite.channel.telegram.api.HttpBotApi
import org.foedusprogramme.alexandrite.channel.telegram.api.SendMessage
import org.foedusprogramme.alexandrite.channel.telegram.api.Update
import org.foedusprogramme.alexandrite.channel.telegram.configOf
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FakeBotApiTest {
    private val fakes = mutableListOf<FakeBotApi>()
    private val apis = mutableListOf<HttpBotApi>()
    private val ada = FakeUser(42, "Ada", username = "ada_test")
    private val grace = FakeUser(43, "Grace")

    @AfterTest
    fun close() {
        apis.forEach(HttpBotApi::close)
        fakes.forEach(FakeBotApi::close)
    }

    private fun fake(canReadAllGroupMessages: Boolean = true): Pair<FakeBotApi, HttpBotApi> {
        val fake = FakeBotApi(canReadAllGroupMessages = canReadAllGroupMessages).also { fakes += it }
        return fake to HttpBotApi(configOf(fake)).also { apis += it }
    }

    private suspend fun HttpBotApi.drain(): List<Update> {
        val updates = getUpdates(null, 100, 0)
        updates.lastOrNull()?.let { getUpdates(it.updateId + 1, 100, 0) }
        return updates
    }

    private fun entities(vararg spans: Triple<String, Int, Int>) = JsonArray(
        spans.map { (type, offset, length) ->
            buildJsonObject {
                put("type", type)
                put("offset", offset)
                put("length", length)
            }
        },
    )

    @Test
    fun `commands and mentions get Telegram's entities, counted in UTF-16 units`() {
        assertEquals(entities(Triple("bot_command", 0, 5)), telegramEntities("/help"))
        assertEquals(
            entities(Triple("bot_command", 3, 24), Triple("mention", 37, 13)),
            telegramEntities("😀 /go@alexandrite_test_bot then ask @someone_else"),
        )
        assertEquals(
            entities(Triple("bot_command", 4, 6), Triple("bot_command", 17, 5)),
            telegramEntities("say /start, then /stop"),
        )
        assertEquals(entities(), telegramEntities("/usr/bin, mail@example.com and @abc"))
    }

    @Test
    fun `a topic message replies to the topic's root unless it replies to another message`() = runBlocking {
        val (fake, api) = fake()
        val forum = fake.group(-1001, "Forum", forum = true)
        val topic = fake.topic(forum, "Builds", ada)
        fake.send(ada, forum, "Is the build green?", topic)
        val answer = api.sendMessage(SendMessage(forum.id, "It is.", messageThreadId = topic.threadId))
        fake.send(ada, forum, "Thanks", topic, replyTo = answer.messageId)

        val messages = api.drain().map { it.message!! }

        assertEquals(listOf(topic.threadId, topic.threadId), messages.map { it.messageThreadId })
        assertTrue(messages.all { it.isTopicMessage })
        assertEquals(listOf(topic.threadId, answer.messageId), messages.map { it.replyToMessage?.messageId })
        assertEquals(topic.threadId, answer.replyToMessage?.messageId)
        assertEquals(topic.threadId, fake.botMessage(forum.id, answer.messageId)?.thread)
        assertEquals("supergroup", messages.first().chat.type)
        assertTrue(messages.first().chat.isForum)
    }

    @Test
    fun `in privacy mode a group sends the bot only commands for it and replies to it`() = runBlocking {
        val (fake, api) = fake(canReadAllGroupMessages = false)
        val group = fake.group(-1002, "Team", supergroup = false)
        val direct = fake.direct(grace)
        val notice = api.sendMessage(SendMessage(group.id, "Deploy done."))
        val texts = listOf(
            "Hello all",
            "@alexandrite_test_bot status?",
            "/status@other_test_bot",
            "/status",
            "/status@ALEXANDRITE_TEST_BOT",
        )
        texts.forEach { fake.send(ada, group, it) }
        fake.send(grace, group, "Nice", replyTo = notice.messageId)
        fake.send(grace, direct, "Hello in private")

        val received = api.drain().map { it.message?.text }

        assertEquals(listOf("/status", "/status@ALEXANDRITE_TEST_BOT", "Nice", "Hello in private"), received)
        assertEquals(false, api.getMe().canReadAllGroupMessages)
    }

    @Test
    fun `a queued message keeps its fields and gets an id and a date`() = runBlocking {
        val (fake, api) = fake()
        val chat = fake.direct(ada)
        fake.send(ada, chat, "First")
        fake.queue(
            buildJsonObject {
                put("from", ada.json())
                put("chat", chat.json())
                put("caption", "A cat")
                putJsonArray("photo") { add(buildJsonObject { put("file_id", "photo-1") }) }
            },
        )

        val message = api.drain().last().message!!

        assertEquals(2L, message.messageId)
        assertEquals("A cat", message.caption)
        assertTrue(message.photo != null && message.text == null)
        assertTrue(message.date > 0)
    }

    @Test
    fun `getUpdates gives at most its limit and confirmed updates are gone`() = runBlocking {
        val (fake, api) = fake()
        val chat = fake.direct(ada)
        repeat(3) { fake.send(ada, chat, "Message $it") }

        val two = api.getUpdates(null, 2, 0)
        val rest = api.getUpdates(two.last().updateId + 1, 2, 0)

        assertEquals(listOf("Message 0", "Message 1"), two.map { it.message?.text })
        assertEquals(listOf("Message 2"), rest.map { it.message?.text })
        assertEquals(1, fake.pendingUpdates)
        assertEquals(two.last().updateId + 1, fake.confirmedOffset)
    }

    @Test
    fun `await returns what the probe finds once the fake gets there`() = runBlocking {
        val (fake, api) = fake()
        val chat = fake.direct(ada)
        val sent = Thread.ofVirtual().start { runBlocking { api.sendMessage(SendMessage(chat.id, "Ping")) } }

        val call = fake.await { calls("sendMessage").firstOrNull() }
        val message = fake.await { botMessages(chat.id).firstOrNull() }
        sent.join()

        assertEquals(chat.id, call.params["chat_id"]!!.jsonPrimitive.long)
        assertEquals("Ping", message.text)
        assertEquals(chat.id, fake.send(ada, chat, "Pong")["chat"]!!.jsonObject["id"]!!.jsonPrimitive.long)
    }
}
