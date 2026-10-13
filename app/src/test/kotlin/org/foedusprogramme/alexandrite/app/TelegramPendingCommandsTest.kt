package org.foedusprogramme.alexandrite.app

import org.foedusprogramme.alexandrite.sdk.transcript.ContextPart
import org.foedusprogramme.alexandrite.sdk.transcript.UserEntry
import org.foedusprogramme.alexandrite.sdk.transcript.UserOrigin
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TelegramPendingCommandsTest {
    @TempDir
    lateinit var directory: Path

    private val app = TelegramApp()

    @AfterTest
    fun close() {
        app.close()
    }

    @Test
    fun `a mode change then a message tells the model about it, before the message`() {
        app.model.reply { text("Hi there.") }

        app.run(app.builder(directory)) {
            app.send(app.ada, app.adaChat, "/mode allowedit")
            app.message(app.adaChat, "The permission mode of this chat is now allowedit.")
            app.send(app.ada, app.adaChat, "Hi")
            app.message(app.adaChat, "Hi there.")
            idle()
        }

        val request = app.model.requests.single { it.asked == "Hi" }
        val entries = request.history
        val commandsIndex = entries.indexOfFirst {
            it is UserEntry && it.origin == UserOrigin.SystemWritten("alexandrite.commands")
        }
        val messageIndex = entries.indexOfFirst { it is UserEntry && it.origin is UserOrigin.FromChat }

        assertTrue(commandsIndex in entries.indices, "no commands notice in $entries")
        assertEquals(commandsIndex + 1, messageIndex)
        assertEquals(
            "[alexandrite:commands]\n" +
                "Commands run in this chat since your last turn (they need no answer of their own):\n" +
                "- Ada Lovelace (operator) ran /mode allowedit and was told: \"The permission mode of this chat " +
                "is now allowedit.\"\n" +
                "[/alexandrite:commands]",
            ((entries[commandsIndex] as UserEntry).parts.single() as ContextPart).text,
        )
    }
}
