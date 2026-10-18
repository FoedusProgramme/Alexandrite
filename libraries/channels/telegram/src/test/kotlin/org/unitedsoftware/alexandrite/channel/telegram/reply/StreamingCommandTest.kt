package org.unitedsoftware.alexandrite.channel.telegram.reply

import kotlinx.coroutines.runBlocking
import org.unitedsoftware.alexandrite.channel.telegram.StreamingConfig
import org.unitedsoftware.alexandrite.channel.telegram.TelegramBots
import org.unitedsoftware.alexandrite.channel.telegram.TelegramInstanceConfig
import org.unitedsoftware.alexandrite.channel.telegram.WORK
import org.unitedsoftware.alexandrite.channel.telegram.fake.FAKE_BOT_TOKEN
import org.unitedsoftware.alexandrite.sdk.chat.ChannelInstanceId
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.config.Secret
import org.unitedsoftware.alexandrite.testkit.TestChatStates
import org.unitedsoftware.alexandrite.testkit.testChat
import org.unitedsoftware.alexandrite.testkit.testCommand
import org.unitedsoftware.alexandrite.testkit.testCommandContext
import org.unitedsoftware.alexandrite.testkit.testTexts
import kotlin.test.Test
import kotlin.test.assertEquals

class StreamingCommandTest {
    private val choices = StreamingChoices(TestChatStates().of("alexandrite-channel-telegram"))
    private val home = ChannelInstanceId(WORK.type, "home")
    private val bots = TelegramBots().apply {
        started(WORK, StreamingConfig(mode = StreamingMode.EDIT, quote = true))
        started(home, StreamingConfig(mode = StreamingMode.NATURAL, quote = false))
    }
    private val command = StreamingCommand(choices, bots, testTexts("alexandrite-channel-telegram"))
    private val chat = ChatAddress(WORK, "1001")
    private val config = TelegramInstanceConfig(
        Secret(FAKE_BOT_TOKEN),
        streaming = StreamingConfig(mode = StreamingMode.EDIT, quote = true),
    )
    private val styles = ReplyStyles(config, choices)

    private fun run(arguments: String, chat: ChatAddress = this.chat): String = runBlocking {
        val invocation = testCommand("streaming", arguments, chat)
        val context = testCommandContext(invocation)
        command.handle(invocation, context)
        context.replies.single().text
    }

    private fun style(): Pair<StreamingMode, Boolean> = runBlocking { styles.of(chat).let { it.mode to it.quote } }

    @Test
    fun `a chat's choice stands in for the bot's, and default takes it back`() {
        assertEquals("Replies come as: edit (the bot's setting), quote (the bot's setting).", run(""))
        assertEquals(StreamingMode.EDIT to true, style())

        assertEquals("Replies come as: natural (this chat), noquote (this chat).", run("natural noquote"))
        assertEquals(StreamingMode.NATURAL to false, style())

        assertEquals(
            "Replies come as: natural (this chat), quote (the bot's setting).",
            run("quote default natural"),
        )
        assertEquals(StreamingMode.NATURAL to true, style())

        run("default")
        assertEquals(StreamingMode.EDIT to true, style())
    }

    @Test
    fun `the values in force come from the bot that the command came through`() {
        val there = ChatAddress(home, "1001")

        assertEquals("Replies come as: natural (the bot's setting), noquote (the bot's setting).", run("", there))
        assertEquals("Replies come as: edit (this chat), noquote (the bot's setting).", run("edit", there))
    }

    @Test
    fun `an unknown word changes nothing, and a chat of another channel is told what the command is for`() {
        run("natural")

        assertEquals("Use /streaming with edit or natural, quote or noquote, or default.", run("loud"))
        assertEquals(StreamingMode.NATURAL to true, style())
        assertEquals("/streaming sets how replies come in a Telegram chat.", run("edit", testChat()))
    }
}
