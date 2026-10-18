package org.unitedsoftware.alexandrite.channel.telegram.reply

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.unitedsoftware.alexandrite.channel.telegram.api.BotApiFailure
import org.unitedsoftware.alexandrite.channel.telegram.logged
import org.unitedsoftware.alexandrite.sdk.channel.Delivery
import org.unitedsoftware.alexandrite.sdk.channel.DeliveryFailure
import org.unitedsoftware.alexandrite.sdk.channel.ReplyEnd
import org.unitedsoftware.alexandrite.sdk.chat.ChannelMessageRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class TelegramReplyTest {
    private fun TestScope.output(streaming: Boolean = true) = Output(this, streaming)

    // Previews.

    @Test
    fun `a segment's previews go out as one message edited in place, coalesced to the latest text`() = runTest {
        val output = output()
        val reply = output.open()

        reply.preview(0, "One")
        advanceTimeBy(100)
        reply.preview(0, "One two")
        reply.preview(0, "One two three")
        advanceTimeBy(2_000)

        assertEquals(listOf("sendMessage One", "editMessageText One two three"), output.shown())
        assertEquals("One two three", output.api.messages[DIRECT to 101L])
    }

    @Test
    fun `a preview never waits for the network`() = runTest {
        val output = output()
        output.api.latency = 10.seconds
        val reply = output.open()

        reply.preview(0, "One")
        advanceTimeBy(100)
        val before = currentTime
        reply.preview(0, "One two")
        reply.startSegment(1)
        reply.preview(1, "Three")

        assertEquals(before, currentTime)
    }

    @Test
    fun `previews are edited a second apart in a direct chat and three seconds apart in a group`() = runTest {
        for ((chat, interval) in listOf(DIRECT to 1_000L, GROUP to 3_000L)) {
            val output = output()
            val reply = output.open(chat)

            for (line in 1..35) {
                reply.preview(0, "Line $line")
                advanceTimeBy(200)
            }
            advanceTimeBy(5_000)

            val calls = output.api.sent.filter { it.method != "sendChatAction" }
            val gaps = calls.zipWithNext { a, b -> b.at - a.at }
            assertTrue(gaps.all { it in interval - 100..interval + 100 }, "$chat: $calls")
            assertEquals("Line 35", calls.last().text)
            reply.abandon(ReplyEnd.CANCELLED)
        }
    }

    @Test
    fun `a longer preview shows the head of its text and an ellipsis`() = runTest {
        val output = output()
        val reply = output.open()

        reply.preview(0, "y".repeat(5_000))
        advanceTimeBy(100)

        val shown = output.api.calls("sendMessage").single().text.orEmpty()
        assertEquals(4_096, shown.length)
        assertEquals("y".repeat(4_095) + "…", shown)
    }

    @Test
    fun `previews render their Markdown`() = runTest {
        val output = output()
        val reply = output.open()

        reply.preview(0, "Reading **Main.kt**")
        advanceTimeBy(100)

        val sent = output.api.calls("sendMessage").single()
        assertEquals("Reading Main.kt", sent.text)
        assertEquals(listOf("bold 8+7"), sent.entities)
    }

    @Test
    fun `a deleted preview starts a new message`() = runTest {
        val output = output()
        val reply = output.open()
        reply.preview(0, "One")
        advanceTimeBy(100)

        output.api.messages.remove(DIRECT to 101L)
        reply.preview(0, "One two")
        advanceTimeBy(3_000)
        val delivery = reply.complete(markdown("One two three"))

        assertEquals(
            listOf(
                "sendMessage One",
                "editMessageText One two",
                "sendMessage One two",
                "editMessageText One two three",
            ),
            output.shown(),
        )
        assertEquals(Delivery.Delivered(listOf(ChannelMessageRef(output.address(), "102"))), delivery)
    }

    @Test
    fun `without streaming previews show nothing and the final goes out as a new message`() = runTest {
        val output = output(streaming = false)
        val reply = output.open()

        reply.preview(0, "One")
        advanceTimeBy(2_000)
        reply.complete(markdown("One two"))

        assertEquals(listOf("sendMessage One two"), output.shown())
    }

    // Segments.

    @Test
    fun `a higher segment freezes the lower ones, whose pending text is shown once`() = runTest {
        val output = output()
        val reply = output.open()
        reply.preview(0, "Reading Main.kt")
        advanceTimeBy(100)

        reply.preview(0, "Reading Main.kt and App.kt")
        reply.startSegment(1)
        reply.preview(1, "It starts")
        val lines = logged {
            reply.preview(0, "Too late")
            advanceTimeBy(5_000)
        }

        assertEquals(
            listOf(
                "sendMessage Reading Main.kt",
                "editMessageText Reading Main.kt and App.kt",
                "sendMessage It starts",
            ),
            output.shown(),
        )
        assertTrue(lines.any { it.startsWith("WARN") && "frozen segment 0" in it }, lines.joinToString("\n"))
    }

    @Test
    fun `a final after a segment with no preview goes out as a new message, the earlier one kept`() = runTest {
        val output = output()
        val reply = output.open()
        reply.preview(0, "Reading Main.kt.")
        advanceTimeBy(100)

        reply.startSegment(1)
        val delivery = reply.complete(markdown("It starts the runtime."))

        assertEquals(listOf("sendMessage Reading Main.kt.", "sendMessage It starts the runtime."), output.shown())
        assertEquals("Reading Main.kt.", output.api.messages[DIRECT to 101L])
        assertEquals(Delivery.Delivered(listOf(ChannelMessageRef(output.address(), "102"))), delivery)
    }

    @Test
    fun `the final shows the pending text of a frozen segment first`() = runTest {
        val output = output()
        val reply = output.open(GROUP)
        reply.preview(0, "One")
        advanceTimeBy(100)

        reply.preview(0, "One two")
        reply.startSegment(1)
        reply.complete(markdown("Three"))

        assertEquals(listOf("sendMessage One", "editMessageText One two", "sendMessage Three"), output.shown())
    }

    // The final message.

    @Test
    fun `the final replaces the current segment's previews in its message`() = runTest {
        val output = output()
        val reply = output.open()
        reply.preview(0, "Hello")
        advanceTimeBy(100)

        val delivery = reply.complete(markdown("Hello **world**"))

        val final = output.api.calls("editMessageText").single()
        assertEquals("Hello world" to listOf("bold 6+5"), final.text to final.entities)
        assertEquals(true, final.previewDisabled)
        assertEquals(Delivery.Delivered(listOf(ChannelMessageRef(output.address(), "101"))), delivery)
    }

    @Test
    fun `a final the preview shows already, or that Telegram finds unchanged, is delivered`() = runTest {
        val output = output()
        val shown = output.open()
        shown.preview(0, "Done.")
        advanceTimeBy(100)
        val unchanged = output.open(DIRECT + 1)
        unchanged.preview(0, "Almost")
        advanceTimeBy(100)
        output.api.fail("editMessageText", BotApiFailure.NOT_MODIFIED)

        val first = shown.complete(markdown("Done."))
        val second = unchanged.complete(markdown("Almost done."))

        assertEquals(Delivery.Delivered(listOf(ChannelMessageRef(output.address(), "101"))), first)
        assertEquals(Delivery.Delivered(listOf(ChannelMessageRef(output.address(DIRECT + 1), "102"))), second)
        assertEquals(listOf(DIRECT + 1), output.api.calls("editMessageText").map { it.chat })
    }

    @Test
    fun `a final whose preview message is gone goes out as a new message`() = runTest {
        val output = output()
        val reply = output.open()
        reply.preview(0, "One")
        advanceTimeBy(100)
        output.api.messages.remove(DIRECT to 101L)

        val delivery = reply.complete(markdown("One two"))

        assertEquals(listOf("sendMessage One", "editMessageText One two", "sendMessage One two"), output.shown())
        assertEquals(Delivery.Delivered(listOf(ChannelMessageRef(output.address(), "102"))), delivery)
    }

    @Test
    fun `a longer final replaces the preview with its first part and sends the others after it, a second apart`() =
        runTest {
            val output = output()
            val reply = output.open()
            reply.preview(0, "Start")
            advanceTimeBy(100)

            val delivery = reply.complete(markdown(THREE_MESSAGES))

            val (first, second, third) = THREE_MESSAGES.split("\n\n")
            assertEquals(
                listOf("sendMessage Start", "editMessageText $first", "sendMessage $second", "sendMessage $third"),
                output.shown(),
            )
            val refs = listOf("101", "102", "103").map { ChannelMessageRef(output.address(), it) }
            assertEquals(Delivery.Delivered(refs), delivery)
            val final = output.api.sent.filter { it.method != "sendChatAction" }.drop(1)
            assertTrue(final.zipWithNext { a, b -> b.at - a.at }.all { it >= 1_000 }, "$final")
        }

    @Test
    fun `the later parts of a final quote the trigger in a group only`() = runTest {
        val output = output()
        val group = output.open(GROUP)
        group.preview(0, "Start")
        advanceTimeBy(100)

        group.complete(markdown(THREE_MESSAGES))
        output.open(DIRECT).complete(markdown(THREE_MESSAGES))

        assertEquals(listOf(41L, 41L, 41L, null, null, null), output.api.calls("sendMessage").map { it.replyTo })
    }

    @Test
    fun `a final of ten messages goes out whole, one of eleven is too long without a call and leaves the previews`() =
        runTest {
            val output = output()
            val reply = output.open()
            reply.preview(0, "Short")
            advanceTimeBy(100)

            val ten = output.open(DIRECT + 1).complete(markdown("x".repeat(40_960)))
            val eleven = reply.complete(markdown("x".repeat(40_961)))

            val sent = output.api.calls("sendMessage").filter { it.chat == DIRECT + 1 }
            assertEquals(10, (ten as Delivery.Delivered).messages.size)
            assertEquals(List(10) { 4_096 }, sent.map { it.text?.length })
            assertEquals(Delivery.NotDelivered(DeliveryFailure.TOO_LONG), eleven)
            val previews = output.api.sent.filter { it.chat == DIRECT && it.method != "sendChatAction" }
            assertEquals(listOf("Short"), previews.map { it.text })
        }

    @Test
    fun `a later part Telegram refuses ends the final as not delivered, naming the parts that arrived`() = runTest {
        val output = output()
        val reply = output.open()
        reply.preview(0, "Start")
        advanceTimeBy(100)
        output.api.fail("sendMessage", BotApiFailure.TRANSIENT)

        val delivery = reply.complete(markdown(THREE_MESSAGES))

        val detail = "only 1 of 3 parts arrived, as messages 101: scripted"
        assertEquals(Delivery.NotDelivered(DeliveryFailure.TRANSIENT, detail, retryable = false), delivery)
        assertEquals(2, output.api.calls("sendMessage").size)
    }

    @Test
    fun `the final waits for the preview call in flight and edits the message it sent`() = runTest {
        val output = output()
        output.api.latency = 2.seconds
        val reply = output.open()
        reply.preview(0, "One")
        advanceTimeBy(100)

        val delivery = reply.complete(markdown("One two"))

        assertEquals(listOf("sendMessage One", "editMessageText One two"), output.shown())
        assertEquals(Delivery.Delivered(listOf(ChannelMessageRef(output.address(), "101"))), delivery)
    }

    @Test
    fun `a transient failure of the final edit is tried once more, of a final send not`() = runTest {
        val output = output()
        val edited = output.open()
        edited.preview(0, "One")
        advanceTimeBy(100)
        output.api.fail("editMessageText", BotApiFailure.TRANSIENT)
        val sent = output.open(DIRECT + 1)

        val first = edited.complete(markdown("One two"))
        output.api.fail("sendMessage", BotApiFailure.TRANSIENT)
        val second = sent.complete(markdown("Three"))

        assertEquals(Delivery.Delivered(listOf(ChannelMessageRef(output.address(), "101"))), first)
        assertEquals(2, output.api.calls("editMessageText").size)
        assertEquals(Delivery.NotDelivered(DeliveryFailure.TRANSIENT, "scripted"), second)
        assertEquals(1, output.api.calls("sendMessage").count { it.chat == DIRECT + 1 })
    }

    @Test
    fun `a final Telegram refuses ends as the delivery failure`() = runTest {
        val output = output()
        output.api.fail("sendMessage", BotApiFailure.FORBIDDEN)

        val delivery = output.open().complete(markdown("Hello"))

        assertEquals(Delivery.NotDelivered(DeliveryFailure.FORBIDDEN, "scripted"), delivery)
    }

    // Quotes and threads.

    @Test
    fun `the first message quotes the trigger in a group only`() = runTest {
        val output = output()
        val group = output.open(GROUP)
        group.preview(0, "One")
        advanceTimeBy(100)
        group.startSegment(1)
        group.complete(markdown("Two"))
        output.open(DIRECT).complete(markdown("Three"))

        assertEquals(listOf(41L, null, null), output.api.calls("sendMessage").map { it.replyTo })
    }

    @Test
    fun `a reply in a forum topic goes to its thread`() = runTest {
        val output = output()
        val reply = output.open(GROUP, thread = 7)
        advanceTimeBy(100)

        reply.complete(markdown("Hello"))

        assertEquals(7L, output.api.calls("sendMessage").single().thread)
        assertEquals(7L, output.api.calls("sendChatAction").first().thread)
    }

    // The end.

    @Test
    fun `an abandoned reply keeps what was shown and drops what was pending`() = runTest {
        val output = output()
        val reply = output.open(GROUP)
        reply.preview(0, "One")
        advanceTimeBy(100)

        reply.preview(0, "One two")
        reply.abandon(ReplyEnd.CANCELLED)
        advanceTimeBy(10_000)

        assertEquals(listOf("sendMessage One"), output.shown())
        assertEquals("One", output.api.messages[GROUP to 101L])
    }

    @Test
    fun `calls after the end are ignored and warned of`() = runTest {
        val output = output()
        val reply = output.open()
        reply.complete(markdown("Done"))

        lateinit var again: Delivery
        val lines = logged {
            reply.preview(0, "More")
            reply.startSegment(1)
            again = reply.complete(markdown("Again"))
            reply.abandon(ReplyEnd.FAILED)
            advanceTimeBy(5_000)
        }

        assertEquals(Delivery.NotDelivered(DeliveryFailure.UNKNOWN), again)
        assertEquals(listOf("sendMessage Done"), output.shown())
        val warnings = lines.filter { it.startsWith("WARN") && "after its reply ended" in it }
        assertEquals(4, warnings.size, lines.joinToString("\n"))
    }

    @Test
    fun `once the channel stops replies and sends deliver nothing`() = runTest {
        val output = output()
        val open = output.open()
        open.preview(0, "One")
        advanceTimeBy(100)

        output.channel.onStop()
        open.preview(0, "One two")
        advanceTimeBy(5_000)

        val transient = Delivery.NotDelivered(DeliveryFailure.TRANSIENT)
        assertEquals(transient, open.complete(markdown("Done")))
        assertEquals(transient, output.open().complete(markdown("Late")))
        assertEquals(transient, output.channel.send(output.address(), markdown("Late")))
        assertEquals(listOf("sendMessage One"), output.shown())
    }

    @Test
    fun `a preview Telegram rate-limits once the limiter closed waits for the next preview`() = runTest {
        val output = output()
        val reply = output.open(GROUP)
        reply.preview(0, "One")
        advanceTimeBy(100)
        output.limiter.close()
        repeat(100) { output.api.fail("editMessageText", BotApiFailure.RATE_LIMITED, retryAfter = 30.seconds) }

        reply.preview(0, "One two")
        advanceTimeBy(10_000)
        val stalled = output.api.calls("editMessageText").map { it.at }
        reply.preview(0, "One two three")
        advanceTimeBy(10_000)

        assertEquals(listOf(3_000L), stalled)
        assertEquals(listOf(3_000L, 10_100L), output.api.calls("editMessageText").map { it.at })
        reply.abandon(ReplyEnd.SHUTDOWN)
    }

    @Test
    fun `a chat that is not paired gets no reply, no typing and no message, and nothing proactive`() = runTest {
        val output = output()
        val stranger = output.address(DIRECT + 2)
        val forbidden = Delivery.NotDelivered(DeliveryFailure.FORBIDDEN, "the chat is not paired")
        val reply = output.open(DIRECT + 2)

        reply.preview(0, "One")
        advanceTimeBy(5_000)

        assertEquals(forbidden, reply.complete(markdown("Done")))
        assertEquals(forbidden, output.channel.send(stranger, markdown("Hello")))
        assertEquals(emptyList(), output.api.sent.map { "$it" })
        assertEquals(false, output.channel.capabilities(stranger).proactive)
        assertEquals(true, output.channel.capabilities(output.address(GROUP, 5)).proactive)
    }

    // Typing.

    @Test
    fun `typing shows from the open every four seconds until the reply ends`() = runTest {
        val output = output()
        val reply = output.open()

        advanceTimeBy(9_000)
        reply.complete(markdown("Done"))
        advanceTimeBy(10_000)

        val typing = output.api.calls("sendChatAction")
        assertEquals(listOf(0L, 4_000L, 8_000L), typing.map { it.at / 1_000 * 1_000 })
    }

    @Test
    fun `one ticker types in a chat however many replies are open`() = runTest {
        val output = output()
        val first = output.open()
        val second = output.open()

        advanceTimeBy(5_000)
        first.abandon(ReplyEnd.CANCELLED)
        advanceTimeBy(4_000)
        val whileOpen = output.api.calls("sendChatAction").size
        second.abandon(ReplyEnd.CANCELLED)
        advanceTimeBy(10_000)

        assertEquals(3, whileOpen)
        assertEquals(3, output.api.calls("sendChatAction").size)
    }

    @Test
    fun `typing stops after thirty minutes and a failed action is not tried again`() = runTest {
        val output = output()
        output.api.fail("sendChatAction", BotApiFailure.RATE_LIMITED, retryAfter = 2.seconds)
        output.open()

        advanceTimeBy(60 * 60_000)

        val typing = output.api.calls("sendChatAction")
        assertEquals(4_000L, typing[1].at / 1_000 * 1_000)
        assertEquals(450, typing.size)
    }
}
