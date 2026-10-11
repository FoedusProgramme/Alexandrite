package org.foedusprogramme.alexandrite.app

import org.foedusprogramme.alexandrite.app.TelegramApp.Companion.ADA
import org.foedusprogramme.alexandrite.app.TelegramApp.Companion.BOB
import org.foedusprogramme.alexandrite.sdk.tool.ToolRisk
import org.foedusprogramme.alexandrite.sdk.transcript.NotRunReason
import org.foedusprogramme.alexandrite.sdk.transcript.TextPart
import org.foedusprogramme.alexandrite.sdk.transcript.ToolOutcome
import org.foedusprogramme.alexandrite.sdk.transcript.ToolResultEntry
import org.foedusprogramme.alexandrite.testkit.recordingTool
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class TelegramPermissionTest {
    @TempDir
    lateinit var directory: Path

    private val app = TelegramApp()
    private val write = recordingTool("notes.write", ToolRisk.WORKSPACE_WRITE)
    private val modes = "Modes: plan, manual, ask, allowedit, auto, bypass."

    @AfterTest
    fun close() {
        app.close()
    }

    @Test
    fun `in plan mode a gated call is refused with the plan text, and the model describes it instead`() {
        app.model
            .reply { toolCall("notes.write", """{"text": "Buy milk."}""") }
            .reply { text("I would write \"Buy milk.\" to your notes.") }

        app.run(app.builder(directory).tool(write)) {
            app.send(app.ada, app.adaChat, "/mode plan")
            app.message(app.adaChat, "The permission mode of this chat is now plan.")
            app.send(app.ada, app.adaChat, "Note that I need milk.")
            app.message(app.adaChat, "I would write \"Buy milk.\" to your notes.")
            idle()
        }

        val result = app.model.requests.last().history.last() as ToolResultEntry
        assertEquals(ToolOutcome.NotRun(NotRunReason.PLAN_MODE), result.outcome)
        assertEquals(
            "[alexandrite:plan_mode] Not run: this chat is in plan mode. Describe what you would do with " +
                "\"notes.write\" (the exact call and why) instead of doing it. Do not retry.",
            (result.content.single() as TextPart).text,
        )
        assertEquals(emptyList(), write.calls)
    }

    @Test
    fun `a member sees the mode but cannot set it, and an admin's bypass shows in the settings`() {
        app.run(app.builder(directory)) {
            app.send(app.bob, app.bobChat, "/mode")
            app.messages(app.bobChat, 1)
            app.send(app.bob, app.bobChat, "/mode bypass")
            app.messages(app.bobChat, 2)
            app.send(app.ada, app.adaChat, "/mode bypass 2h")
            app.messages(app.adaChat, 1)
            app.send(app.ada, app.adaChat, "/settings")
            app.messages(app.adaChat, 2)
            idle()
        }

        assertEquals(
            listOf("Permission mode: ask (the default). $modes", "Only an admin of this bot may use /mode."),
            app.fake.botMessages(BOB).map { it.text },
        )
        val (set, settings) = app.fake.botMessages(ADA).map { it.text }
        assertEquals("The permission mode of this chat is now bypass for 2 h, then ask.", set)
        assertEquals("Permission mode: bypass (set in this chat) for 2 h more", settings.lines()[5])
    }
}
