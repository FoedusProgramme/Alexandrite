package org.unitedsoftware.alexandrite.app

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.app.TelegramApp.Companion.ADA
import org.unitedsoftware.alexandrite.app.TelegramApp.Companion.WAIT
import org.unitedsoftware.alexandrite.channel.telegram.fake.FakeBotMessage
import org.unitedsoftware.alexandrite.sdk.transcript.ContextPart
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolOutcome
import org.unitedsoftware.alexandrite.sdk.transcript.ToolResultEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UserEntry
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.readBytes
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TelegramFilesTest {
    @TempDir
    lateinit var directory: Path

    private val app = TelegramApp()

    @AfterTest
    fun close() {
        app.close()
    }

    @Test
    fun `a document a user sends is in the agent's inbox, and a file the agent sends back goes out once allowed`() {
        lateinit var notes: Path
        lateinit var asked: FakeBotMessage

        app.run(app.builder(directory)) {
            val conversation = app.store.conversations.current(app.key(app.adaChat)).id
            notes = directory.toRealPath().resolve("data/conversations/coder/$conversation/inbox/notes.txt")
            app.model.reply { toolCall("fs.read", path(notes)) }
                .reply { toolCall("chat.send_file", path(notes, caption = "Your notes, back")) }
                .reply { text(DONE) }

            app.fake.sendDocument(app.ada, app.adaChat, "notes.txt", NOTES, "text/plain", "Send my notes back.")
            asked = app.fake.await(WAIT) { botMessages(ADA).firstOrNull { it.buttons.isNotEmpty() } }
            app.fake.press(app.ada, asked, "Allow")
            app.message(app.adaChat, DONE)
            idle()
        }

        assertContentEquals(NOTES, notes.readBytes())
        val opening = app.model.requests.first().history.filterIsInstance<UserEntry>().last()
        val note = opening.parts.filterIsInstance<ContextPart>().single { it.source == "alexandrite.media" }.text
        assertTrue("Saved to $notes: file \"notes.txt\" (text/plain, ${NOTES.size} bytes)" in note, note)
        assertEquals("Send my notes back.", opening.words)
        val results = app.model.requests.last().history.filterIsInstance<ToolResultEntry>()
        assertEquals(listOf(ToolOutcome.Succeeded, ToolOutcome.Succeeded), results.map { it.outcome })
        assertTrue("Buy milk." in results[0].text, results[0].text)
        val sha256 = MessageDigest.getInstance("SHA-256").digest(NOTES).joinToString("") { "%02x".format(it) }
        assertEquals(
            "Sent notes.txt (text/plain, ${NOTES.size} bytes, SHA-256 $sha256) from $notes to this chat " +
                "(telegram:work:$ADA).",
            results[1].text,
        )
        for (line in listOf("File: $notes", "Size: ${NOTES.size} bytes", "SHA-256: $sha256", "Destination: ")) {
            assertTrue(line in asked.text, asked.text)
        }
        assertTrue(asked.text.endsWith("Caption:\n\nYour notes, back"), asked.text)
        val messages = app.fake.botMessages(ADA)
        val sent = messages.single { it.file != null }
        assertEquals(
            "sendDocument notes.txt text/plain",
            sent.file.let {
                "${it?.method} ${it?.name} ${it?.mediaType}"
            },
        )
        assertContentEquals(NOTES, sent.file?.bytes)
        assertEquals("Your notes, back", sent.text)
        val activity = messages.indexOfFirst { it.isActivity() && "Sen" in it.text }
        assertTrue(activity in 0 until messages.indexOf(sent), messages.joinToString("\n"))
        assertTrue(messages.indexOf(sent) < messages.indexOfFirst { it.text == DONE }, messages.joinToString("\n"))
    }

    private fun path(file: Path, caption: String? = null): String = JsonObject(
        listOfNotNull("path" to JsonPrimitive("$file"), caption?.let { "caption" to JsonPrimitive(it) }).toMap(),
    ).toString()

    private val ToolResultEntry.text: String get() = content.joinToString("") { (it as TextPart).text }

    private companion object {
        val NOTES = "Buy milk.\nCall the plumber.\n".encodeToByteArray()
        const val DONE = "Here are your notes back."
    }
}
