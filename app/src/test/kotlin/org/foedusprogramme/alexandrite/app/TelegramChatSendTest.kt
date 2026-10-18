package org.foedusprogramme.alexandrite.app

import org.foedusprogramme.alexandrite.app.TelegramApp.Companion.ADA
import org.foedusprogramme.alexandrite.app.TelegramApp.Companion.BOT_MENTION
import org.foedusprogramme.alexandrite.app.TelegramApp.Companion.GROUP
import org.foedusprogramme.alexandrite.sdk.transcript.ContextPart
import org.foedusprogramme.alexandrite.sdk.transcript.TextPart
import org.foedusprogramme.alexandrite.sdk.transcript.ToolResultEntry
import org.foedusprogramme.alexandrite.sdk.transcript.UserEntry
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `chat.send` over a fake Bot API: an admin in a group has the bot write to their direct chat. */
class TelegramChatSendTest {
    @TempDir
    lateinit var directory: Path

    private val app = TelegramApp()

    @AfterTest
    fun close() {
        app.close()
    }

    @Test
    fun `an admin's group turn lists the chats and writes to the admin's direct chat, which learns of it`() {
        app.model.reply { text("Hello, Ada.") }
            .reply { toolCall("chat.list", "{}") }
            .reply { toolCall("chat.send", """{"to": "telegram:work:$ADA", "text": "The *build* passed."}""") }
            .reply { text("Told you directly.") }
            .reply { text("You are welcome.") }

        app.run(app.builder(directory)) {
            app.send(app.ada, app.adaChat, "Hi")
            app.message(app.adaChat, "Hello, Ada.")
            app.send(app.ada, app.group, "$BOT_MENTION tell me in private when the build passes")
            app.message(app.group, "Told you directly.")
            idle()
            app.send(app.ada, app.adaChat, "Thanks")
            app.message(app.adaChat, "You are welcome.")
            idle()
        }

        val direct = app.fake.botMessages(ADA).map { it.text }
        assertEquals(listOf("Hello, Ada.", "The build passed.", "You are welcome."), direct)
        assertTrue(app.fake.botMessages(GROUP).none { it.buttons.isNotEmpty() })
        val listed = app.model.requests[2].history.filterIsInstance<ToolResultEntry>().last()
        assertEquals(
            "The chats you can send to:\n- telegram:work:$GROUP \"Backend\" (this chat)\n- telegram:work:$ADA",
            listed.content.joinToString("") { (it as TextPart).text },
        )
        val told = app.model.requests.last().history.filterIsInstance<UserEntry>()
            .flatMap { it.parts }.filterIsInstance<ContextPart>().single { it.source == "alexandrite.sent" }
        assertContains(told.text, "from your conversation in telegram:work:$GROUP:\n  > The *build* passed.")
    }
}
