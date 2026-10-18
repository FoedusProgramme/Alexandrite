package org.foedusprogramme.alexandrite.channel.telegram.reply

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.foedusprogramme.alexandrite.channel.telegram.api.BotApiFailure
import org.foedusprogramme.alexandrite.sdk.channel.Delivery
import org.foedusprogramme.alexandrite.sdk.channel.DeliveryFailure
import org.foedusprogramme.alexandrite.sdk.channel.Markup
import org.foedusprogramme.alexandrite.sdk.channel.MessageKind
import org.foedusprogramme.alexandrite.sdk.channel.OutboundFile
import org.foedusprogramme.alexandrite.sdk.channel.OutboundMessage
import org.foedusprogramme.alexandrite.sdk.channel.ToolCallStatus
import org.foedusprogramme.alexandrite.sdk.channel.TurnEvent
import org.foedusprogramme.alexandrite.sdk.chat.ChannelMessageRef
import org.foedusprogramme.alexandrite.sdk.chat.ToolCallId
import org.foedusprogramme.alexandrite.sdk.tool.ToolActivity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class FileOutputTest {
    private fun TestScope.output() = Output(this)

    private fun file(name: String, type: String, size: Int = 3, asFile: Boolean = false): OutboundFile =
        OutboundFile.builder(name, type, ByteArray(size) { (it % 7).toByte() }).asFile(asFile).build()

    private fun message(caption: String, vararg files: OutboundFile): OutboundMessage =
        OutboundMessage.builder(caption, MessageKind.REPLY).markup(Markup.MARKDOWN).files(files.toList()).build()

    @Test
    fun `the channel takes files of up to 50 MB`() = runTest {
        val output = output()
        val capabilities = output.channel.capabilities(output.address())

        assertEquals(true to 50L * 1024 * 1024, capabilities.files to capabilities.maxFileBytes)
    }

    @Test
    fun `a picture goes as a photo unless asked as a file, anything else as a document, the caption formatted`() =
        runTest {
            val output = output()
            val chat = output.address()

            val photo = output.channel.send(chat, message("The **chart**", file("chart.png", "image/png")))
            output.channel.send(chat, message("", file("chart.png", "image/png", asFile = true)))
            output.channel.send(chat, message("", file("report.pdf", "application/pdf")))
            output.channel.send(chat, message("", file("anim.gif", "image/gif")))

            assertEquals(
                listOf("sendPhoto The chart", "sendDocument null", "sendDocument null", "sendDocument null"),
                output.api.calls().map { "${it.method} ${it.text}" },
            )
            assertEquals(listOf("bold 4+5"), output.api.calls().first().entities)
            assertEquals(
                listOf("chart.png", "chart.png", "report.pdf", "anim.gif"),
                output.api.uploads.map { it.file.name },
            )
            assertEquals(Delivery.Delivered(listOf(ChannelMessageRef(chat, "101"))), photo)
        }

    @Test
    fun `an image over 10 MB, or one Telegram refuses as a photo, goes as a document`() = runTest {
        val output = output()
        val chat = output.address()
        output.api.fail("sendPhoto", BotApiFailure.BAD_REQUEST)

        output.channel.send(chat, message("", file("wide.jpg", "image/jpeg")))
        output.channel.send(chat, message("", file("big.webp", "image/webp", size = 10 * 1024 * 1024 + 1)))

        assertEquals(listOf("sendPhoto", "sendDocument", "sendDocument"), output.api.calls().map { it.method })
    }

    @Test
    fun `a file over 50 MB, an empty file and a caption over 1024 characters are refused before any upload`() =
        runTest {
            val output = output()
            val chat = output.address()

            val dump = file("dump.bin", "application/octet-stream", size = 50 * 1024 * 1024 + 1)
            val large = output.channel.send(chat, message("", dump))
            val empty = output.channel.send(chat, message("", file("empty.txt", "text/plain", size = 0)))
            val long = output.channel.send(chat, message("x".repeat(1025), file("a.txt", "text/plain")))

            assertEquals(
                listOf(DeliveryFailure.TOO_LONG, DeliveryFailure.UNSUPPORTED, DeliveryFailure.TOO_LONG),
                listOf(large, empty, long).map { assertIs<Delivery.NotDelivered>(it).kind },
            )
            assertTrue("1025 characters" in (long as Delivery.NotDelivered).detail.orEmpty(), long.detail)
            assertEquals(emptyList(), output.api.calls())
        }

    @Test
    fun `an upload that hits the rate limit goes once more after the wait`() = runTest {
        val output = output()
        output.api.fail("sendDocument", BotApiFailure.RATE_LIMITED, retryAfter = 3.seconds)

        val delivery = output.channel.send(output.address(), message("", file("a.txt", "text/plain")))

        assertIs<Delivery.Delivered>(delivery)
        val calls = output.api.calls("sendDocument")
        assertEquals(2, calls.size)
        assertTrue(calls[1].at - calls[0].at >= 3_000, "$calls")
    }

    @Test
    fun `a sticker or a poll a tool posts in a group comes after the text the reply holds so far`() = runTest {
        val output = output()
        val reply = output.open(GROUP)
        val chat = output.address(GROUP)
        val sticker = buildJsonObject {
            put("chat_id", GROUP)
            put("sticker", "CAAC")
        }

        reply.preview(0, "Look at this.")
        output.desk.post(chat, "sendSticker", sticker)
        reply.complete(markdown("Look at this."))

        assertEquals(listOf("sendMessage Look at this.", "sendSticker null"), output.shown())
    }

    @Test
    fun `a file sent during a turn comes after the text and the activity shown so far, and before later text`() =
        runTest {
            val output = output()
            val reply = output.open()
            val call = ToolCallId("c1")

            reply.preview(0, "Here is the report.")
            reply.event(TurnEvent.ToolCallStarted(call, "chat.send_file", ToolActivity.SEND, "report.pdf"))
            output.channel.send(output.address(), message("", file("report.pdf", "application/pdf")))
            reply.event(TurnEvent.ToolCallUpdated(call, ToolCallStatus.COMPLETED))
            advanceTimeBy(2_000)
            reply.startSegment(1)
            reply.preview(1, "Anything else?")
            advanceTimeBy(2_000)
            reply.complete(markdown("Anything else?"))

            assertEquals(
                listOf(
                    "sendMessage Here is the report.",
                    "sendMessage Sending 1 file\nreport.pdf",
                    "sendDocument null",
                    "editMessageText Sent 1 file\nreport.pdf",
                    "sendMessage Anything else?",
                ),
                output.shown(),
            )
        }
}
