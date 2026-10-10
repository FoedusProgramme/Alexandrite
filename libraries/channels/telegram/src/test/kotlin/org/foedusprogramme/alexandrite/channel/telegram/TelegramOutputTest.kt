package org.foedusprogramme.alexandrite.channel.telegram

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.foedusprogramme.alexandrite.channel.telegram.TelegramHarness.Companion.ADA
import org.foedusprogramme.alexandrite.channel.telegram.TelegramHarness.Companion.GROUP
import org.foedusprogramme.alexandrite.channel.telegram.reply.markdown
import org.foedusprogramme.alexandrite.sdk.channel.Channel
import org.foedusprogramme.alexandrite.sdk.channel.ChannelDirectory
import org.foedusprogramme.alexandrite.sdk.channel.Delivery
import org.foedusprogramme.alexandrite.sdk.channel.DeliveryFailure
import org.foedusprogramme.alexandrite.sdk.channel.Markup
import org.foedusprogramme.alexandrite.sdk.channel.MessageKind
import org.foedusprogramme.alexandrite.sdk.channel.OutboundMessage
import org.foedusprogramme.alexandrite.sdk.channel.ReplyRequest
import org.foedusprogramme.alexandrite.sdk.channel.ReplySink
import org.foedusprogramme.alexandrite.sdk.chat.ChannelMessageRef
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.testkit.PluginHarness
import org.foedusprogramme.alexandrite.testkit.testTurn
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class TelegramOutputTest {
    private val telegram = TelegramHarness()
    private val fake = telegram.fake

    @AfterTest
    fun close() {
        telegram.close()
    }

    private fun quick(): PluginHarness.Builder =
        telegram.builder("work" to telegram.instance(more = NO_PREVIEW_SPACING))

    private fun PluginHarness.Running.telegramChannel(): Channel = checkNotNull(get<ChannelDirectory>().channel(WORK))

    /** Opens the reply of a turn in [chat] that answers its message [trigger], as the agent does at dequeue. */
    private suspend fun PluginHarness.Running.open(chat: ChatAddress, trigger: Long): ReplySink =
        telegramChannel().openReply(ReplyRequest(testTurn(chat = chat), ChannelMessageRef(chat, "$trigger")))

    @Test
    fun `a direct message's reply streams into one message edited in place and ends with the final text`() {
        telegram.run(quick()) {
            val chat = telegram.address(ADA)
            val asked = fake.send(telegram.ada, telegram.adaChat, "What does Main.kt do?")
            val reply = open(chat, asked.messageId())

            val typing = fake.await { calls("sendChatAction").firstOrNull() }
            reply.preview(0, "Main.kt starts")
            val message = fake.await { botMessages(ADA).singleOrNull() }
            reply.preview(0, "Main.kt starts the **runtime**")
            fake.await { botMessage(ADA, message.id)?.takeIf { it.edits == 1 } }
            val delivery = reply.complete(markdown("Main.kt starts the **runtime** and waits for a stop."))

            val final = fake.botMessages(ADA).single()
            assertEquals("Main.kt starts the runtime and waits for a stop.", final.text)
            assertEquals("""[{"type":"bold","offset":19,"length":7}]""", final.entities.toString())
            assertEquals(2 to null, final.edits to final.replyTo)
            assertEquals(Delivery.Delivered(listOf(ChannelMessageRef(chat, "${final.id}"))), delivery)
            assertEquals(JsonPrimitive(ADA), typing.params["chat_id"])
            assertEquals(JsonPrimitive("typing"), typing.params["action"])
        }
    }

    @Test
    fun `a two-round tool turn leaves round 0's message as it was and answers in a second message`() {
        telegram.run(quick()) {
            val chat = telegram.address(ADA)
            val reply = open(chat, fake.send(telegram.ada, telegram.adaChat, "Read Main.kt").messageId())

            reply.preview(0, "Let me read Main.kt.")
            fake.await { botMessages(ADA).singleOrNull() }
            reply.startSegment(1)
            reply.preview(1, "Main.kt starts")
            fake.await { botMessages(ADA).getOrNull(1) }
            reply.complete(markdown("Main.kt starts the runtime."))

            val (round0, answer) = fake.botMessages(ADA)
            assertEquals("Let me read Main.kt." to 0, round0.text to round0.edits)
            assertEquals("Main.kt starts the runtime." to 1, answer.text to answer.edits)
        }
    }

    @Test
    fun `a final too long for one message is not delivered and leaves the preview`() {
        telegram.run(quick()) {
            val chat = telegram.address(ADA)
            val reply = open(chat, fake.send(telegram.ada, telegram.adaChat, "Print everything").messageId())

            reply.preview(0, "Working on it")
            fake.await { botMessages(ADA).singleOrNull() }
            val delivery = reply.complete(markdown("x".repeat(5_000)))

            assertEquals(Delivery.NotDelivered(DeliveryFailure.TOO_LONG), delivery)
            assertEquals(listOf("Working on it" to 0), fake.botMessages(ADA).map { it.text to it.edits })
            assertEquals(0, fake.calls("editMessageText").size)
        }
    }

    @Test
    fun `a reply in a group quotes the message it answers`() {
        telegram.run(quick()) {
            val chat = telegram.address(GROUP)
            val asked = fake.send(telegram.bob, telegram.group, "@alexandrite_test_bot what is new?")

            open(chat, asked.messageId()).complete(markdown("Nothing yet."))

            val answer = fake.botMessages(GROUP).single()
            assertEquals("Nothing yet." to asked.messageId(), answer.text to answer.replyTo)
        }
    }

    @Test
    fun `a message sent outside a reply goes out as plain text, and the channel counts parts after rendering`() {
        telegram.run(quick()) {
            val chat = telegram.address(ADA)
            val notice = OutboundMessage.builder("Use **/new** to start over.", MessageKind.NOTICE).build()

            val delivery = telegramChannel().send(chat, notice)

            val sent = fake.botMessages(ADA).single()
            assertEquals("Use **/new** to start over.", sent.text)
            assertEquals(Delivery.Delivered(listOf(ChannelMessageRef(chat, "${sent.id}"))), delivery)
            val full = "**${"x".repeat(4_096)}**"
            assertEquals(1, telegramChannel().capabilities(chat).maxPartsPerReply)
            assertEquals(1, telegramChannel().partsNeeded(chat, full, Markup.MARKDOWN))
            assertEquals(2, telegramChannel().partsNeeded(chat, full, Markup.PLAIN))
        }
    }

    private fun JsonObject.messageId(): Long = (getValue("message_id") as JsonPrimitive).content.toLong()

    private companion object {
        const val NO_PREVIEW_SPACING = """, "streaming": {"editIntervalMillis": 0, "groupEditIntervalMillis": 0}"""
    }
}
