package org.foedusprogramme.alexandrite.channel.telegram.reply

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.foedusprogramme.alexandrite.channel.telegram.WORK
import org.foedusprogramme.alexandrite.channel.telegram.api.BotApiFailure
import org.foedusprogramme.alexandrite.channel.telegram.i18n.TextKeys
import org.foedusprogramme.alexandrite.channel.telegram.logged
import org.foedusprogramme.alexandrite.sdk.channel.Delivery
import org.foedusprogramme.alexandrite.sdk.channel.DeliveryFailure
import org.foedusprogramme.alexandrite.sdk.channel.MessageKind
import org.foedusprogramme.alexandrite.sdk.channel.OutboundMessage
import org.foedusprogramme.alexandrite.sdk.chat.ChannelMessageRef
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.runtime.StopKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class OutboxTest {
    @Test
    fun `a message goes out with its Markdown as entities, link previews off, in its thread`() = runTest {
        val output = Output(this)
        val topic = output.address(GROUP, thread = 7)

        val delivery = output.outbox.send(topic, markdown("Read **Main.kt**"))

        val sent = output.api.calls("sendMessage").single()
        assertEquals("Read Main.kt" to listOf("bold 5+7"), sent.text to sent.entities)
        assertEquals(7L to true, sent.thread to sent.previewDisabled)
        assertEquals(Delivery.Delivered(listOf(ChannelMessageRef(topic, "101"))), delivery)
    }

    @Test
    fun `a message quotes what it replies to in a group only`() = runTest {
        val output = Output(this)
        val group = output.address(GROUP)
        val direct = output.address(DIRECT)

        output.outbox.send(group, reply("In the group", ChannelMessageRef(group, "41")))
        output.outbox.send(direct, reply("In private", ChannelMessageRef(direct, "42")))
        output.outbox.send(group, reply("Elsewhere", ChannelMessageRef(direct, "43")))

        assertEquals(listOf(41L, null, null), output.api.calls("sendMessage").map { it.replyTo })
    }

    @Test
    fun `a message longer than one Telegram message is too long without a call`() = runTest {
        val output = Output(this)

        val long = output.outbox.send(output.address(), markdown("x".repeat(4_097)))
        val full = output.outbox.send(output.address(), markdown("**${"x".repeat(4_096)}**"))

        assertEquals(Delivery.NotDelivered(DeliveryFailure.TOO_LONG), long)
        assertTrue(full is Delivery.Delivered)
        assertEquals(listOf(4_096), output.api.calls("sendMessage").map { it.text?.length })
    }

    @Test
    fun `Telegram's failures map to deliveries, the unknown ones warned of`() = runTest {
        val output = Output(this)
        val cases = listOf(
            BotApiFailure.TOO_LONG to DeliveryFailure.TOO_LONG,
            BotApiFailure.CHAT_GONE to DeliveryFailure.CHAT_GONE,
            BotApiFailure.FORBIDDEN to DeliveryFailure.FORBIDDEN,
            BotApiFailure.TRANSIENT to DeliveryFailure.TRANSIENT,
            BotApiFailure.BAD_REQUEST to DeliveryFailure.UNKNOWN,
            BotApiFailure.PROTOCOL to DeliveryFailure.UNKNOWN,
        )

        val lines = logged {
            for ((failure, expected) in cases) {
                output.api.fail("sendMessage", failure)
                val delivery = output.outbox.send(output.address(), markdown("Hello"))
                assertEquals(Delivery.NotDelivered(expected, "scripted"), delivery, "$failure")
            }
        }

        assertEquals(cases.size, output.api.calls("sendMessage").size)
        assertEquals(2, lines.count { it.startsWith("WARN") && "could not deliver a message" in it })
    }

    @Test
    fun `a rate limit is waited out once, then ends as rate limited with Telegram's wait`() = runTest {
        val output = Output(this)
        repeat(2) { output.api.fail("sendMessage", BotApiFailure.RATE_LIMITED, retryAfter = 5.seconds) }

        val delivery = output.outbox.send(output.address(), markdown("Hello"))

        val expected = Delivery.NotDelivered(DeliveryFailure.RATE_LIMITED, "scripted", retryAfter = 5.seconds)
        assertEquals(expected, delivery)
        assertEquals(listOf(0L, 5_000L), output.api.calls("sendMessage").map { it.at })
    }

    @Test
    fun `a chat that became a supergroup is gone, and its new id is warned of`() = runTest {
        val output = Output(this)
        output.api.fail("sendMessage", BotApiFailure.CHAT_GONE, migrateTo = -1009876543210L)

        val lines = logged { output.outbox.send(output.address(GROUP), markdown("Hello")) }

        assertTrue(lines.any { it.startsWith("WARN") && "became the supergroup -1009876543210" in it }, "$lines")
    }

    @Test
    fun `a token Telegram no longer accepts stops the runtime`() = runTest {
        val output = Output(this)
        output.api.fail("sendMessage", BotApiFailure.UNAUTHORIZED)

        val delivery = output.outbox.send(output.address(), markdown("Hello"))

        assertEquals(Delivery.NotDelivered(DeliveryFailure.UNKNOWN, "scripted"), delivery)
        assertEquals(listOf(StopKind.FAILURE), output.control.requests.map { it.kind })
    }

    @Test
    fun `entities Telegram refuses go once more as plain text, warned of once`() = runTest {
        val output = Output(this)

        val lines = logged {
            repeat(2) {
                output.api.fail("sendMessage", BotApiFailure.ENTITIES_REJECTED)
                val delivery = output.outbox.send(output.address(), markdown("**Bold** move"))
                assertTrue(delivery is Delivery.Delivered, "$delivery")
            }
        }

        val sent = output.api.calls("sendMessage").map { it.text to it.entities }
        val formatted = "Bold move" to listOf("bold 0+4")
        val plain = "Bold move" to emptyList<String>()
        assertEquals(listOf(formatted, plain, formatted, plain), sent)
        assertEquals(1, lines.count { it.startsWith("WARN") && "plain text" in it })
    }

    @Test
    fun `an address that names no Telegram chat is not delivered`() = runTest {
        val output = Output(this)

        val delivery = output.outbox.send(ChatAddress(WORK, "general"), markdown("Hello"))

        assertEquals(Delivery.NotDelivered(DeliveryFailure.CHAT_GONE), delivery)
        assertEquals(emptyList(), output.api.sent)
    }

    @Test
    fun `a notice goes out as plain text and quotes its trigger in a group`() = runTest {
        val output = Output(this)
        val group = output.address(GROUP)

        output.outbox.notice(group, ChannelMessageRef(group, "41"), TextKeys.QUEUE_FULL, "capacity" to 20)

        val sent = output.api.calls("sendMessage").single()
        assertEquals(
            "Too many messages are waiting for an answer here (20 at most), so this one was not taken. " +
                "Send it again later.",
            sent.text,
        )
        assertEquals(41L to emptyList<String>(), sent.replyTo to sent.entities)
    }

    private fun reply(text: String, to: ChannelMessageRef): OutboundMessage =
        OutboundMessage.builder(text, MessageKind.NOTICE).replyTo(to).build()
}
