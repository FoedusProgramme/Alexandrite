package org.unitedsoftware.alexandrite.channel.telegram

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.unitedsoftware.alexandrite.channel.telegram.TelegramHarness.Companion.ADA
import org.unitedsoftware.alexandrite.channel.telegram.TelegramHarness.Companion.GROUP
import org.unitedsoftware.alexandrite.channel.telegram.reply.THREE_MESSAGES
import org.unitedsoftware.alexandrite.channel.telegram.reply.markdown
import org.unitedsoftware.alexandrite.sdk.channel.Channel
import org.unitedsoftware.alexandrite.sdk.channel.ChannelDirectory
import org.unitedsoftware.alexandrite.sdk.channel.Delivery
import org.unitedsoftware.alexandrite.sdk.channel.Markup
import org.unitedsoftware.alexandrite.sdk.channel.MessageKind
import org.unitedsoftware.alexandrite.sdk.channel.OutboundMessage
import org.unitedsoftware.alexandrite.sdk.channel.ReplyRequest
import org.unitedsoftware.alexandrite.sdk.channel.ReplySink
import org.unitedsoftware.alexandrite.sdk.chat.ChannelMessageRef
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.testkit.PluginHarness
import org.unitedsoftware.alexandrite.testkit.testTurn
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
            assertEquals(10, telegramChannel().capabilities(chat).maxPartsPerReply)
            assertEquals(1, telegramChannel().partsNeeded(chat, full, Markup.MARKDOWN))
            assertEquals(2, telegramChannel().partsNeeded(chat, full, Markup.PLAIN))
            assertEquals(3, telegramChannel().partsNeeded(chat, THREE_MESSAGES, Markup.MARKDOWN))
            assertEquals(11, telegramChannel().partsNeeded(chat, "x".repeat(40_961), Markup.PLAIN))
        }
    }

    @Test
    fun `a reply longer than one message arrives as its edited preview and two new messages with their entities`() {
        telegram.run(quick()) {
            val chat = telegram.address(ADA)
            val reply = open(chat, fake.send(telegram.ada, telegram.adaChat, "Print the listing.").messageId())
            val code = (1..340).map { "val v%03d = %03d".format(it, it) }
            val answer = "The **listing** follows." + " It is long.".repeat(250) +
                "\n\n```kotlin\n${code.joinToString("\n")}\n```\n\n" +
                "See [the guide](https://example.org/guide) for more." + " More words.".repeat(75)

            reply.preview(0, "The **listing** follows.")
            val preview = fake.await { botMessages(ADA).singleOrNull() }
            val delivery = reply.complete(markdown(answer))

            val (first, second, third) = fake.botMessages(ADA)
            assertEquals(listOf(preview.id, preview.id + 1, preview.id + 2), listOf(first.id, second.id, third.id))
            val refs = listOf(first, second, third).map { ChannelMessageRef(chat, "${it.id}") }
            assertEquals(Delivery.Delivered(refs), delivery)
            assertEquals("The listing follows." + " It is long.".repeat(250), first.text)
            assertEquals("""[{"type":"bold","offset":4,"length":7}]""", first.entities.toString())
            assertEquals(code.take(273).joinToString("\n"), second.text)
            assertEquals(
                """[{"type":"pre","offset":0,"length":4094,"language":"kotlin"}]""",
                second.entities.toString(),
            )
            val outro = "See the guide for more." + " More words.".repeat(75)
            assertEquals(code.drop(273).joinToString("\n") + "\n\n" + outro, third.text)
            assertEquals(
                """[{"type":"pre","offset":0,"length":1004,"language":"kotlin"},""" +
                    """{"type":"text_link","offset":1010,"length":9,"url":"https://example.org/guide"}]""",
                third.entities.toString(),
            )
            assertEquals(listOf(1, 0, 0), listOf(first, second, third).map { it.edits })
            assertEquals(listOf(null, null, null), listOf(first, second, third).map { it.replyTo })
        }
    }

    private fun JsonObject.messageId(): Long = (getValue("message_id") as JsonPrimitive).content.toLong()

    private companion object {
        const val NO_PREVIEW_SPACING = """, "streaming": {"editIntervalMillis": 0, "groupEditIntervalMillis": 0}"""
    }
}
