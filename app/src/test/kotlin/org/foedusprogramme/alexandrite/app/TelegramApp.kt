package org.foedusprogramme.alexandrite.app

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.long
import org.foedusprogramme.alexandrite.agent.AlexandriteAgentIndex
import org.foedusprogramme.alexandrite.channel.telegram.AlexandriteChannelTelegramIndex
import org.foedusprogramme.alexandrite.channel.telegram.fake.FakeBotApi
import org.foedusprogramme.alexandrite.channel.telegram.fake.FakeBotMessage
import org.foedusprogramme.alexandrite.channel.telegram.fake.FakeChat
import org.foedusprogramme.alexandrite.channel.telegram.fake.FakeUser
import org.foedusprogramme.alexandrite.commands.AlexandriteCommandsIndex
import org.foedusprogramme.alexandrite.sdk.chat.AgentChatKey
import org.foedusprogramme.alexandrite.sdk.chat.AgentId
import org.foedusprogramme.alexandrite.sdk.chat.ChannelInstanceId
import org.foedusprogramme.alexandrite.sdk.chat.ChannelType
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.model.ModelRequest
import org.foedusprogramme.alexandrite.sdk.transcript.TextPart
import org.foedusprogramme.alexandrite.sdk.transcript.UserEntry
import org.foedusprogramme.alexandrite.sdk.transcript.UserOrigin
import org.foedusprogramme.alexandrite.sdk.turn.AgentControl
import org.foedusprogramme.alexandrite.store.sqlite.AlexandriteStoreSqliteIndex
import org.foedusprogramme.alexandrite.testkit.MemoryStore
import org.foedusprogramme.alexandrite.testkit.PluginHarness
import org.foedusprogramme.alexandrite.testkit.ScriptedModel
import org.foedusprogramme.alexandrite.tools.AlexandriteToolsIndex
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The app's agent, tools, commands and Telegram plugins over a [FakeBotApi] whose bot has Ada as its admin, Bob as a
 * member and the group Backend, with [model] answering for the agent `coder`.
 */
class TelegramApp : AutoCloseable {
    val fake = FakeBotApi()
    val model = ScriptedModel(models = listOf(ScriptedModel.modelInfo(), ScriptedModel.modelInfo(FAST_MODEL)))
    val store = MemoryStore()
    val ada = FakeUser(ADA, "Ada", "Lovelace", "ada_test")
    val bob = FakeUser(BOB, "Bob", username = "bob_test")
    val adaChat = fake.direct(ada)
    val bobChat = fake.direct(bob)
    val group = fake.group(GROUP, "Backend")

    fun dataDir(directory: Path): Path = directory.resolve("data")

    /**
     * The app with its data and its config file in [directory], whose paired chats are [paired], on the SQLite
     * store when [sqlite] and on [store] otherwise.
     */
    fun builder(
        directory: Path,
        paired: List<Long> = listOf(ADA, BOB, GROUP),
        sqlite: Boolean = false,
    ): PluginHarness.Builder {
        val telegram = AlexandriteChannelTelegramIndex()
        val builder = PluginHarness.builder(AlexandriteAgentIndex())
            .plugin(AlexandriteToolsIndex())
            .plugin(AlexandriteCommandsIndex())
            .plugin(telegram)
            .config(AGENT_CONFIG)
            .config(telegram, telegramConfig(paired))
            .model(model)
            .dataRoot(dataDir(directory))
            .writeConfigFile(directory.toRealPath().resolve("config/alexandrite.json"))
        return if (sqlite) builder.plugin(AlexandriteStoreSqliteIndex()) else builder.store(store)
    }

    fun run(builder: PluginHarness.Builder, block: suspend PluginHarness.Running.() -> Unit) {
        runBlocking { withTimeout(50.seconds) { builder.build().run(block) } }
    }

    /** Has [from] write [text] in [chat], and returns the message's id. */
    fun send(from: FakeUser, chat: FakeChat, text: String): Long = fake.send(from, chat, text).messageId()

    /** The bot's messages in [chat], once there are [count]. */
    fun messages(chat: FakeChat, count: Int): List<FakeBotMessage> =
        fake.await(WAIT) { botMessages(chat.id).takeIf { it.size >= count } }

    /** The bot's messages in [chat], its activity messages left out, once there are [count]. */
    fun bodyMessages(chat: FakeChat, count: Int): List<FakeBotMessage> =
        fake.await(WAIT) { botMessages(chat.id).body().takeIf { it.size >= count } }

    /** The bot's message in [chat] that reads [text], once one does. */
    fun message(chat: FakeChat, text: String): FakeBotMessage =
        fake.await(WAIT) { botMessages(chat.id).firstOrNull { it.text == text } }

    /** Waits until the bot dispatched the first [count] updates and polled again. */
    fun consumed(count: Long) {
        fake.await(WAIT) { confirmedOffset.takeIf { it > count } }
    }

    fun address(chat: FakeChat): ChatAddress = ChatAddress(WORK, "${chat.id}")

    fun key(chat: FakeChat): AgentChatKey = AgentChatKey(CODER, address(chat))

    override fun close() {
        fake.close()
    }

    private fun telegramConfig(paired: List<Long>): String =
        """{"instances": {"work": {"botToken": "${fake.token}", "apiBaseUrl": "${fake.baseUrl}", """ +
            """"adminUserIds": [$ADA], "allowedChats": $paired, "polling": {"timeoutSeconds": 1}, """ +
            """"streaming": {"editIntervalMillis": 0, "groupEditIntervalMillis": 0}}}}"""

    companion object {
        const val ADA = 1001L
        const val BOB = 1002L
        const val GROUP = -1001234500000L
        const val FAST_MODEL = "fast-model"
        const val BOT_MENTION = "@alexandrite_test_bot"

        /** A pairing or link code. */
        val CODE = Regex("[0-9A-Z]{4}-[0-9A-Z]{4}")
        val WORK = ChannelInstanceId(ChannelType("telegram"), "work")
        val CODER = AgentId("coder")
        val WAIT = 10.seconds

        private val AGENT_CONFIG = """
            {
              "previewIntervalMillis": 0,
              "agents": {
                "coder": {
                  "name": "Coder",
                  "model": "scripted/test-model",
                  "reasoning": "low",
                  "channels": {"telegram:work": {}}
                }
              }
            }
        """.trimIndent()
    }
}

/** Waits until the agent lists no turn. */
suspend fun PluginHarness.Running.idle() {
    withTimeout(10.seconds) {
        while (get<AgentControl>().turns(null).isNotEmpty()) delay(10.milliseconds)
    }
}

/** Whether this message is an activity message, folding a stretch of tool calls rather than carrying body text. */
fun FakeBotMessage.isActivity(): Boolean = !text.startsWith("Approval needed") &&
    entities.any { (it as? JsonObject)?.get("type") == JsonPrimitive("expandable_blockquote") }

/** [messages], its activity messages left out. */
fun List<FakeBotMessage>.body(): List<FakeBotMessage> = filterNot { it.isActivity() }

/** The entry's text parts, joined. */
val UserEntry.words: String get() = parts.filterIsInstance<TextPart>().joinToString("") { it.text }

/** The words of the chat message that opened the request's turn. */
val ModelRequest.asked: String
    get() = history.filterIsInstance<UserEntry>().last { it.origin is UserOrigin.FromChat }.words

fun JsonObject.messageId(): Long = (getValue("message_id") as JsonPrimitive).long
