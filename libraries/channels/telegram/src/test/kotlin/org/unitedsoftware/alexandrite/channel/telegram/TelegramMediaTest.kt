package org.unitedsoftware.alexandrite.channel.telegram

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.unitedsoftware.alexandrite.channel.telegram.TelegramHarness.Companion.ADA
import org.unitedsoftware.alexandrite.sdk.channel.Channel
import org.unitedsoftware.alexandrite.sdk.channel.ChannelDirectory
import org.unitedsoftware.alexandrite.sdk.channel.Delivery
import org.unitedsoftware.alexandrite.sdk.channel.IncomingMessage
import org.unitedsoftware.alexandrite.sdk.channel.MessageKind
import org.unitedsoftware.alexandrite.sdk.channel.OutboundFile
import org.unitedsoftware.alexandrite.sdk.channel.OutboundMessage
import org.unitedsoftware.alexandrite.sdk.transcript.MediaKind
import org.unitedsoftware.alexandrite.sdk.turn.Submission
import org.unitedsoftware.alexandrite.testkit.PluginHarness
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

class TelegramMediaTest {
    private val telegram = TelegramHarness()
    private val fake = telegram.fake
    private val pdf = "%PDF-1.7 report".encodeToByteArray()

    @AfterTest
    fun close() {
        telegram.close()
    }

    private suspend fun messages(count: Int): List<IncomingMessage> =
        telegram.submitted(count).map { assertIs<Submission.Message>(it).message }

    private fun PluginHarness.Running.telegramChannel(): Channel = checkNotNull(get<ChannelDirectory>().channel(WORK))

    @Test
    fun `a document reaches the agent as an attachment, downloaded through the Bot API only when read`() {
        lateinit var message: IncomingMessage
        var read = ByteArray(0)

        telegram.run {
            fake.sendDocument(telegram.ada, telegram.adaChat, "report.pdf", pdf, "application/pdf", "Summarize this")
            message = messages(1).single()
            val untouched = fake.calls("getFile").size
            read = message.media.single().content.read(1_000)
            assertEquals(0, untouched)
        }

        val attachment = message.media.single()
        assertEquals("Summarize this", message.text)
        assertEquals(
            listOf(MediaKind.FILE, "application/pdf", pdf.size.toLong(), "report.pdf", false),
            listOf(attachment.kind, attachment.mediaType, attachment.size, attachment.name, attachment.fromQuote),
        )
        assertContentEquals(pdf, read)
        assertEquals(1, fake.calls("getFile").size)
        assertEquals(1, fake.calls("download").size)
    }

    @Test
    fun `an album is one message with its caption and every photo, and a message sent after it follows it`() {
        telegram.run {
            fake.sendPhoto(telegram.ada, telegram.adaChat, byteArrayOf(1), "Two views", mediaGroup = "g1")
            fake.sendPhoto(telegram.ada, telegram.adaChat, byteArrayOf(2), mediaGroup = "g1")
            fake.send(telegram.ada, telegram.adaChat, "Which is better?")

            val (album, question) = messages(2)
            assertEquals("Two views", album.text)
            assertEquals(listOf(listOf<Byte>(1), listOf<Byte>(2)), album.media.map { it.content.read(10).toList() })
            assertEquals("Which is better?", question.text)
        }

        assertEquals(2, telegram.submitter.submissions.size)
    }

    @Test
    fun `an album that a stop cuts short is handed to the agent before the stop`() {
        telegram.run {
            fake.sendPhoto(telegram.ada, telegram.adaChat, byteArrayOf(1), "Before the stop", mediaGroup = "g2")
            telegram.consumed(1)
        }

        val message = assertIs<Submission.Message>(telegram.submitter.submissions.single()).message
        assertEquals("Before the stop" to 1, message.text to message.media.size)
    }

    @Test
    fun `a file past the 20 MB a bot may download is never downloaded`() {
        lateinit var message: IncomingMessage

        telegram.run {
            val id = fake.file(ByteArray(10), size = 21L * 1024 * 1024)
            fake.queue(
                buildJsonObject {
                    put("from", telegram.ada.json())
                    put("chat", telegram.adaChat.json())
                    put(
                        "document",
                        buildJsonObject {
                            put("file_id", id)
                            put("file_unique_id", "unique-$id")
                            put("file_name", "dump.zip")
                            put("file_size", 21L * 1024 * 1024)
                        },
                    )
                },
            )
            message = messages(1).single()
        }

        val attachment = message.media.single()
        assertEquals("larger than the 20 MB that a bot may download from Telegram", attachment.unavailable)
        assertEquals("application/octet-stream", attachment.mediaType)
        assertEquals(emptyList(), fake.calls("getFile"))
    }

    @Test
    fun `no log line of a run that receives, fails to download and sends files holds the bot's token`() {
        val secret = fake.token.substringAfter(':')

        val lines = runBlocking {
            logged {
                telegram.run {
                    fake.sendDocument(telegram.ada, telegram.adaChat, "report.pdf", pdf, "application/pdf", "Look")
                    val attachment = messages(1).single().media.single()
                    attachment.content.read(1_000)
                    fake.respond("download", 502, "<html>/file/bot${fake.token}/documents is down</html>")
                    assertNull(runCatching { attachment.content.read(1_000) }.getOrNull())
                    val file = OutboundFile.builder("summary.txt", "text/plain", "Short.".encodeToByteArray()).build()
                    val message = OutboundMessage.builder("", MessageKind.REPLY).files(listOf(file)).build()
                    assertIs<Delivery.Delivered>(telegramChannel().send(telegram.address(ADA), message))
                }
            }
        }

        assertEquals("summary.txt", fake.botMessages(ADA).single().file?.name)
        for (line in lines) assertFalse(secret in line, line)
    }
}
