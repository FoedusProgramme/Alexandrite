package org.unitedsoftware.alexandrite.app

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.app.TelegramApp.Companion.ADA
import org.unitedsoftware.alexandrite.app.TelegramApp.Companion.BOB
import org.unitedsoftware.alexandrite.app.TelegramApp.Companion.BOT_MENTION
import org.unitedsoftware.alexandrite.app.TelegramApp.Companion.GROUP
import org.unitedsoftware.alexandrite.app.TelegramApp.Companion.WAIT
import org.unitedsoftware.alexandrite.channel.telegram.fake.FakeBotMessage
import org.unitedsoftware.alexandrite.channel.telegram.fake.FakeChat
import org.unitedsoftware.alexandrite.sdk.transcript.NotRunReason
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolOutcome
import org.unitedsoftware.alexandrite.sdk.transcript.ToolResultEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UserEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UserOrigin
import org.unitedsoftware.alexandrite.testkit.PluginHarness
import org.unitedsoftware.alexandrite.testkit.RequestMatch
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TelegramCodingTest {
    @TempDir
    lateinit var directory: Path

    private val app = TelegramApp()

    private val repository: Path get() = directory.toRealPath().resolve("repo")
    private val greeter: Path get() = repository.resolve("src/Greeter.kt")
    private val check: Path get() = repository.resolve("check.sh")

    @BeforeTest
    fun writeRepository() {
        repository.resolve(".git").createDirectories()
        repository.resolve("AGENTS.md").writeText(INSTRUCTIONS)
        greeter.parent.createDirectories()
        greeter.writeText(GREETER)
        check.writeText(CHECK)
        assertTrue(check.toFile().setExecutable(true))
    }

    @AfterTest
    fun close() {
        app.close()
    }

    @Test
    fun `an admin's direct chat has the agent edit a file and run the check, each after the admin allowed it`() {
        app.model.reply { toolCall("fs.read", read()) }
            .reply { toolCall("fs.edit", edit()) }
            .reply { toolCall("command.exec", runCheck()) }
            .reply(CHECK_PASSED) { text(DONE) }
        lateinit var edit: FakeBotMessage
        lateinit var exec: FakeBotMessage
        var unchanged = false

        app.run(builder()) {
            app.send(app.ada, app.adaChat, ASK)
            edit = request()
            unchanged = greeter.readText() == GREETER
            answer(edit, "Allow")
            exec = request()
            answer(exec, "Allow, mark tainted")
            app.message(app.adaChat, DONE)
            idle()
        }

        assertTrue(unchanged, "The file changed before the admin allowed the edit.")
        assertTrue(edit.text.startsWith("Approval needed (#"), edit.text)
        assertTrue("\nWhy: the chat is in ask mode\n" in edit.text, edit.text)
        val diff = "--- $greeter\n+++ $greeter\n$HUNK"
        assertTrue(edit.text.endsWith("Tool: fs.edit (workspace_write)\nFile: $greeter\n\n$diff"), edit.text)
        assertEquals(listOf(listOf("Allow", "Deny")), edit.buttons.map { row -> row.map { it.text } })
        val command = "Tool: command.exec (exec)\nDirectory: $repository\nProgram: $check\n" +
            "Sandbox: none, the program runs on the host with your full rights\n\n./check.sh"
        assertTrue(exec.text.endsWith(command), exec.text)
        assertEquals(listOf("Allow, mark tainted", "Deny"), exec.buttons.first().map { it.text })
        assertEquals(
            listOf(
                "Allowed by Ada Lovelace (@ada_test).",
                "Allowed by Ada Lovelace (@ada_test); the conversation is marked tainted.",
            ),
            listOf(edit, exec).map { app.fake.botMessage(ADA, it.id)!!.text.substringAfterLast("\n") },
        )
        assertEquals(listOf(edit.id, exec.id), app.fake.botMessages(ADA).body().dropLast(1).map { it.id })
        assertEquals(
            listOf(
                "fs.read" to ToolOutcome.Succeeded,
                "fs.edit" to ToolOutcome.Succeeded,
                "command.exec" to ToolOutcome.Succeeded,
            ),
            results().map { it.toolName to it.outcome },
        )
        assertEquals(
            listOf("Edited $greeter: +1 -1 lines.", "Exit code: 0\ncheck passed"),
            results().drop(1).map { it.text },
        )
        assertEquals(EDITED, greeter.readText())
        val loaded = app.model.requests[1].history.filterIsInstance<UserEntry>().single { it.isProject() }
        assertEquals(INSTRUCTIONS, loaded.words)
    }

    @Test
    fun `in allowedit mode the edit runs unasked and only the check waits for the admin`() {
        app.model.reply { toolCall("fs.read", read()) }
            .reply { toolCall("fs.edit", edit()) }
            .reply { toolCall("command.exec", runCheck()) }
            .reply(CHECK_PASSED) { text(DONE) }
        lateinit var exec: FakeBotMessage
        var edited = false

        app.run(builder()) {
            app.send(app.ada, app.adaChat, "/mode allowedit")
            app.message(app.adaChat, "The permission mode of this chat is now allowedit.")
            app.send(app.ada, app.adaChat, ASK)
            exec = request()
            edited = greeter.readText() == EDITED
            answer(exec, "Allow, mark tainted")
            app.message(app.adaChat, DONE)
            idle()
        }

        assertTrue(edited, "The edit had not run when the check was asked about.")
        assertTrue("\nWhy: the chat is in allowedit mode\n" in exec.text, exec.text)
        assertTrue("\nTrusting covers what the program prints: ./check.sh\nTool: command.exec (exec)\n" in exec.text)
        assertEquals(1, app.fake.botMessages(ADA).count { it.text.startsWith("Approval needed") })
        assertEquals(List(3) { ToolOutcome.Succeeded }, results().map { it.outcome })
        assertEquals(EDITED, greeter.readText())
    }

    @Test
    fun `in plan mode the edit and the check are refused unasked, and the model describes them instead`() {
        app.model.reply { toolCall("fs.read", read()) }
            .reply { toolCall("fs.edit", edit()) }
            .reply { toolCall("command.exec", runCheck()) }
            .reply { text(PLANNED) }

        app.run(builder()) {
            app.send(app.ada, app.adaChat, "/mode plan")
            app.message(app.adaChat, "The permission mode of this chat is now plan.")
            app.send(app.ada, app.adaChat, ASK)
            app.message(app.adaChat, PLANNED)
            idle()
        }

        assertEquals(
            listOf(
                "fs.read" to ToolOutcome.Succeeded,
                "fs.edit" to ToolOutcome.NotRun(NotRunReason.PLAN_MODE),
                "command.exec" to ToolOutcome.NotRun(NotRunReason.PLAN_MODE),
            ),
            results().map { it.toolName to it.outcome },
        )
        assertEquals(
            listOf("The permission mode of this chat is now plan.", PLANNED),
            app.fake.botMessages().body().map { it.text },
        )
        assertEquals(GREETER, greeter.readText())
    }

    @Test
    fun `a member's direct chat may call no write tool, so nothing is asked and nothing changes`() {
        app.model.reply { toolCall("fs.edit", edit()) }
            .reply { toolCall("command.exec", runCheck()) }
            .reply { text(REFUSED) }

        app.run(builder()) {
            app.send(app.bob, app.bobChat, ASK)
            app.message(app.bobChat, REFUSED)
            idle()
        }

        assertEquals(
            listOf(
                ToolOutcome.NotRun(NotRunReason.NOT_PERMITTED) to "This turn may not call 'fs.edit'.",
                ToolOutcome.NotRun(NotRunReason.NOT_PERMITTED) to "This turn may not call 'command.exec'.",
            ),
            results().map { it.outcome to it.text },
        )
        assertEquals(listOf(REFUSED), app.fake.botMessages(BOB).body().map { it.text })
        assertEquals(
            listOf(TelegramApp.OFFERED),
            app.fake.botMessages(ADA).map {
                it.text.substringBefore(" (#")
            },
        )
        assertEquals(GREETER, greeter.readText())
    }

    @Test
    fun `in a group an admin's request waits in the group, where a member's press only gets a toast`() {
        app.model.reply { toolCall("fs.read", read()) }
            .reply { toolCall("fs.edit", edit()) }
            .reply { text(EDIT_DONE) }
        lateinit var asked: FakeBotMessage
        lateinit var toast: String
        var waiting = false

        app.run(builder()) {
            app.send(app.ada, app.group, "$BOT_MENTION $ASK")
            asked = request(app.group)
            val query = app.fake.press(app.bob, asked, "Allow")
            toast = app.fake.await(WAIT) { answers.firstOrNull { it.query == query }?.text }
            waiting = app.fake.botMessage(GROUP, asked.id)!!.buttons.isNotEmpty() && greeter.readText() == GREETER
            answer(asked, "Allow")
            app.message(app.group, EDIT_DONE)
            idle()
        }

        assertEquals("Only an admin of this bot can answer.", toast)
        assertTrue(waiting, "A member's press answered the request.")
        assertTrue("\nChat: Backend (telegram:work:$GROUP)\nRequested by: Ada Lovelace (@ada_test)\n" in asked.text)
        assertEquals(
            "Allowed by Ada Lovelace (@ada_test).",
            app.fake.botMessage(GROUP, asked.id)!!.text.substringAfterLast("\n"),
        )
        assertEquals(emptyList(), app.fake.botMessages(ADA))
        assertEquals(listOf(ToolOutcome.Succeeded, ToolOutcome.Succeeded), results().map { it.outcome })
        assertEquals(EDITED, greeter.readText())
    }

    /** The app, whose agent trusts the repository. */
    private fun builder(): PluginHarness.Builder = app.builder(directory).config(
        """
        {
          "previewIntervalMillis": 0,
          "trustedFolders": [${JsonPrimitive("$repository")}],
          "agents": {
            "coder": {"name": "Coder", "model": "scripted/test-model", "channels": {"telegram:work": {}}}
          }
        }
        """.trimIndent(),
    )

    /** The approval request that waits for an answer in [chat], once there is one. */
    private fun request(chat: FakeChat = app.adaChat): FakeBotMessage =
        app.fake.await(WAIT) { botMessages(chat.id).firstOrNull { it.buttons.isNotEmpty() } }

    /** Has Ada press [button] on [asked] and waits until the request shows its outcome. */
    private fun answer(asked: FakeBotMessage, button: String) {
        app.fake.press(app.ada, asked, button)
        app.fake.await(WAIT) { botMessage(asked.chat, asked.id)?.takeIf { it.buttons.isEmpty() } }
    }

    /** The tool results that the model's last request holds. */
    private fun results(): List<ToolResultEntry> = app.model.requests.last().history.filterIsInstance<ToolResultEntry>()

    private fun read(): String = JsonObject(mapOf("path" to JsonPrimitive("$greeter"))).toString()

    private fun edit(): String {
        val change = JsonObject(mapOf("old" to JsonPrimitive(GREETING), "new" to JsonPrimitive(HELLO)))
        return JsonObject(mapOf("path" to JsonPrimitive("$greeter"), "edits" to JsonArray(listOf(change)))).toString()
    }

    private fun runCheck(): String =
        JsonObject(mapOf("command" to JsonPrimitive("./check.sh"), "cwd" to JsonPrimitive("$repository"))).toString()

    private val ToolResultEntry.text: String get() = content.joinToString("") { (it as TextPart).text }

    private fun UserEntry.isProject(): Boolean = (origin as? UserOrigin.SystemWritten)?.source == "alexandrite.project"

    private companion object {
        const val ASK = "Make greet say hello, then run the check."
        const val DONE = "greet says hello now, and ./check.sh passes."
        const val EDIT_DONE = "greet says hello now."
        const val PLANNED = "I would replace the greeting in Greeter.kt and then run ./check.sh."
        const val REFUSED = "I may not change files for you."
        const val INSTRUCTIONS = "Run ./check.sh after every change.\n"
        const val GREETING = "\"Hi, \$name.\""
        const val HELLO = "\"Hello, \$name!\""
        const val GREETER = "package demo\n\nfun greet(name: String) = $GREETING\n"
        const val EDITED = "package demo\n\nfun greet(name: String) = $HELLO\n"
        const val HUNK = "@@ -1,3 +1,3 @@\n package demo\n \n-fun greet(name: String) = $GREETING\n" +
            "+fun greet(name: String) = $HELLO"
        val CHECK = """
            #!/bin/sh
            if grep -qF 'fun greet(name: String) = $HELLO' src/Greeter.kt; then
                echo "check passed"
            else
                echo "check failed: greet does not say hello"
                exit 1
            fi
        """.trimIndent() + "\n"

        /** The request that follows a run of the check that passed. */
        val CHECK_PASSED = RequestMatch("the check passed") { request ->
            val last = request.history.filterIsInstance<ToolResultEntry>().lastOrNull()
            last?.toolName == "command.exec" && last.outcome == ToolOutcome.Succeeded
        }
    }
}
