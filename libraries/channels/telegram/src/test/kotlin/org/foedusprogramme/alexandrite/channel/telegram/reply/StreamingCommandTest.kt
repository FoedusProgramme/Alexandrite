package org.foedusprogramme.alexandrite.channel.telegram.reply

import kotlinx.coroutines.runBlocking
import org.foedusprogramme.alexandrite.channel.telegram.StreamingConfig
import org.foedusprogramme.alexandrite.channel.telegram.TelegramInstanceConfig
import org.foedusprogramme.alexandrite.channel.telegram.WORK
import org.foedusprogramme.alexandrite.channel.telegram.fake.FAKE_BOT_TOKEN
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.config.Secret
import org.foedusprogramme.alexandrite.testkit.TestChatStates
import org.foedusprogramme.alexandrite.testkit.testChat
import org.foedusprogramme.alexandrite.testkit.testCommand
import org.foedusprogramme.alexandrite.testkit.testCommandContext
import org.foedusprogramme.alexandrite.testkit.testTexts
import kotlin.test.Test
import kotlin.test.assertEquals

class StreamingCommandTest {
    private val choices = StreamingChoices(TestChatStates().of("alexandrite-channel-telegram"))
    private val command = StreamingCommand(choices, testTexts("alexandrite-channel-telegram"))
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
        assertEquals("Replies come as: the bot's setting, the bot's setting.", run(""))
        assertEquals(StreamingMode.EDIT to true, style())

        assertEquals("Replies come as: natural, noquote.", run("natural noquote"))
        assertEquals(StreamingMode.NATURAL to false, style())

        assertEquals("Replies come as: natural, the bot's setting.", run("quote default natural"))
        assertEquals(StreamingMode.NATURAL to true, style())

        run("default")
        assertEquals(StreamingMode.EDIT to true, style())
    }

    @Test
    fun `an unknown word changes nothing, and a chat of another channel is told what the command is for`() {
        run("natural")

        assertEquals("Use /streaming with edit or natural, quote or noquote, or default.", run("loud"))
        assertEquals(StreamingMode.NATURAL to true, style())
        assertEquals("/streaming sets how replies come in a Telegram chat.", run("edit", testChat()))
    }
}
