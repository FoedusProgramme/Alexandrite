package org.unitedsoftware.alexandrite.app

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.app.TelegramApp.Companion.ADA
import org.unitedsoftware.alexandrite.app.TelegramApp.Companion.BOB
import org.unitedsoftware.alexandrite.app.TelegramApp.Companion.WAIT
import org.unitedsoftware.alexandrite.channel.telegram.fake.FakeBotMessage
import org.unitedsoftware.alexandrite.sdk.store.ConversationStore
import org.unitedsoftware.alexandrite.sdk.store.ConversationTaint
import org.unitedsoftware.alexandrite.sdk.tool.ToolRisk
import org.unitedsoftware.alexandrite.sdk.transcript.ContextPart
import org.unitedsoftware.alexandrite.sdk.transcript.NotRunReason
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolOutcome
import org.unitedsoftware.alexandrite.sdk.transcript.ToolResultEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UserEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UserOrigin
import org.unitedsoftware.alexandrite.testkit.recordingTool
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    fun `an admin's gated call waits for their press in the chat, then runs, and the turn answers`() {
        app.model.reply { toolCall("notes.write", """{"text": "Buy milk."}""") }.reply { text("Noted.") }
        lateinit var asked: FakeBotMessage

        app.run(app.builder(directory).tool(write)) {
            app.send(app.ada, app.adaChat, "Note that I need milk.")
            asked = app.fake.await(WAIT) { botMessages(ADA).firstOrNull { it.buttons.isNotEmpty() } }
            val before = write.calls.size
            app.fake.press(app.ada, asked, "Allow")
            app.message(app.adaChat, "Noted.")
            app.fake.await(WAIT) { botMessage(ADA, asked.id)?.takeIf { it.buttons.isEmpty() } }
            idle()

            assertEquals(0, before)
        }

        assertEquals(listOf(listOf("Allow", "Deny")), asked.buttons.map { row -> row.map { it.text } })
        assertTrue(asked.text.startsWith("Approval needed (#"), asked.text)
        assertTrue(
            asked.text.endsWith("Tool: notes.write (workspace_write)\nArguments:\n\n{\"text\":\"Buy milk.\"}"),
            asked.text,
        )
        assertEquals(1, write.calls.size)
        val answered = app.fake.botMessage(ADA, asked.id)!!.text
        assertEquals("Allowed by Ada Lovelace (@ada_test).", answered.substringAfterLast("\n"))
    }

    @Test
    fun `an admin's denial keeps the call from running, and the model reads that an admin denied it`() {
        app.model.reply { toolCall("notes.write", """{"text": "Buy milk."}""") }.reply { text("I did not note it.") }

        app.run(app.builder(directory).tool(write)) {
            app.send(app.ada, app.adaChat, "Note that I need milk.")
            val asked = app.fake.await(WAIT) { botMessages(ADA).firstOrNull { it.buttons.isNotEmpty() } }
            app.fake.press(app.ada, asked, "Deny")
            app.message(app.adaChat, "I did not note it.")
            idle()
        }

        val result = app.model.requests.last().history.last() as ToolResultEntry
        val id = Regex("#([a-z0-9]+)").find(app.fake.botMessages(ADA).body().first().text)!!.groupValues[1]
        assertEquals(ToolOutcome.NotRun(NotRunReason.DENIED, id), result.outcome)
        assertEquals(
            "[alexandrite:denied by=operator] Not run: an operator denied this call. Do not retry it or an " +
                "equivalent call; tell the user and go on without it.",
            (result.content.single() as TextPart).text,
        )
        assertEquals(emptyList(), write.calls)
    }

    @Test
    fun `a reply to an approval request or to the bot's answer to an admin leaves the conversation untainted`() {
        app.model.reply { toolCall("notes.write", """{"text": "Buy milk."}""") }.reply { text("Noted.") }
            .reply { text("It ran.") }
            .reply { text("It did.") }
        lateinit var asked: FakeBotMessage
        lateinit var noted: FakeBotMessage
        var afterRequest: ConversationTaint? = null
        var afterAnswer: ConversationTaint? = null

        app.run(app.builder(directory).tool(write)) {
            app.send(app.ada, app.adaChat, "Note that I need milk.")
            asked = app.fake.await(WAIT) { botMessages(ADA).firstOrNull { it.buttons.isNotEmpty() } }
            app.fake.press(app.ada, asked, "Allow")
            noted = app.message(app.adaChat, "Noted.")
            idle()
            val conversation = get<ConversationStore>().current(app.key(app.adaChat)).id
            app.fake.send(app.ada, app.adaChat, "Did it run?", replyTo = asked.id)
            app.message(app.adaChat, "It ran.")
            idle()
            afterRequest = get<ConversationStore>().taint(conversation)
            app.fake.send(app.ada, app.adaChat, "Really?", replyTo = noted.id)
            app.message(app.adaChat, "It did.")
            idle()
            afterAnswer = get<ConversationStore>().taint(conversation)
        }

        val id = Regex("#([a-z0-9]+)").find(asked.text)!!.groupValues[1]
        assertContains(
            framing("Did it run?"),
            "\nQuoting: Alexandrite Test Bot (verified, message ${asked.id})\n" +
                "> Alexandrite's approval request $id for notes.write (allowed by Ada Lovelace (@ada_test))\n",
        )
        assertNull(afterRequest)
        assertContains(
            framing("Really?"),
            "\nQuoting: Alexandrite Test Bot (verified, message ${noted.id})\n> Noted.\n",
        )
        assertNull(afterAnswer)
    }

    @Test
    fun `a reply to a command's answer leaves the conversation untainted`() {
        app.model.reply { text("Fine.") }
        var taint: ConversationTaint? = ConversationTaint(java.time.Instant.EPOCH, "unread")

        app.run(app.builder(directory)) {
            app.send(app.ada, app.adaChat, "/untaint")
            val answer = app.message(app.adaChat, "No conversation in this chat is tainted.")
            idle()
            app.fake.send(app.ada, app.adaChat, "Test.", replyTo = answer.id)
            app.message(app.adaChat, "Fine.")
            idle()
            taint = get<ConversationStore>().taint(get<ConversationStore>().current(app.key(app.adaChat)).id)
        }

        assertNull(taint)
    }

    /** The framing of the message [text] as the model read it. */
    private fun framing(text: String): String = app.model.requests.first { it.asked == text }.history
        .filterIsInstance<UserEntry>()
        .last { it.origin is UserOrigin.FromChat }
        .parts.filterIsInstance<ContextPart>()
        .first()
        .text

    @Test
    fun `a member's gated call is asked about in the admin's direct chat, where the admin allows it`() {
        app.model.reply { toolCall("notes.write", """{"text": "Buy milk."}""") }.reply { text("Noted.") }

        app.run(app.builder(directory).tool(write).config(GUEST_TOOLS)) {
            app.send(app.bob, app.bobChat, "Note that I need milk.")
            val asked = app.fake.await(WAIT) {
                botMessages(ADA).firstOrNull { it.buttons.isNotEmpty() && it.text.startsWith("Approval needed") }
            }
            app.fake.press(app.ada, asked, "Allow")
            app.message(app.bobChat, "Noted.")
            idle()

            assertTrue("\nRequested by: Bob (@bob_test)\nWhy: a guest's call\n" in asked.text, asked.text)
            assertTrue("\nChat: telegram:work:$BOB\n" in asked.text, asked.text)
        }

        assertEquals(1, write.calls.size)
        assertEquals(listOf("Noted."), app.fake.botMessages(BOB).body().map { it.text })
    }

    @Test
    fun `always allow lets the same command run unasked in the conversation, but not another one or another repo's`() {
        val first = script("first")
        val second = script("second")
        app.model
            .reply { toolCall("command.exec", gradle("test", first)) }.reply { text("Tested.") }
            .reply { toolCall("command.exec", gradle("test", first)) }.reply { text("Tested again.") }
            .reply { toolCall("command.exec", gradle("build", first)) }.reply { text("Not built.") }
            .reply { toolCall("command.exec", gradle("test", second)) }.reply { text("Not tested there.") }
        lateinit var always: String
        lateinit var rules: String

        app.run(app.builder(directory)) {
            app.send(app.ada, app.adaChat, "Run the tests.")
            always = press { it.startsWith("Always allow") }
            app.message(app.adaChat, "Tested.")
            app.send(app.ada, app.adaChat, "Run them again.")
            app.message(app.adaChat, "Tested again.")
            val unasked = app.fake.botMessages(ADA).count { it.text.startsWith("Approval needed") }
            app.send(app.ada, app.adaChat, "Build it.")
            press { it == "Deny" }
            app.message(app.adaChat, "Not built.")
            app.send(app.ada, app.adaChat, "Test the other one.")
            press { it == "Deny" }
            app.message(app.adaChat, "Not tested there.")
            app.send(app.ada, app.adaChat, "/allow")
            rules = app.fake.await(WAIT) { botMessages(ADA).firstOrNull { it.text.startsWith("Allow rules") } }.text
            idle()

            assertEquals(1, unasked)
        }

        assertTrue(always.startsWith("Always allow and trust folder ") && always.endsWith("/first"), always)
        val results = app.model.requests.map { it.history.last() }.filterIsInstance<ToolResultEntry>()
        assertEquals(
            listOf(ToolOutcome.Succeeded, ToolOutcome.Succeeded, NotRunReason.DENIED, NotRunReason.DENIED),
            results.map { (it.outcome as? ToolOutcome.NotRun)?.reason ?: it.outcome },
        )
        assertEquals(
            List(2) {
                "Exit code: 0\nran test"
            },
            results.take(2).map { (it.content.single() as TextPart).text },
        )
        val listed = "Allow rules in force here (revoke one with /allow revoke <number>):\n1. command.exec: " +
            "${first.resolve("gradlew")} test (allowed by Ada Lovelace on "
        assertTrue(rules.startsWith(listed) && rules.endsWith(" until /new)") && "\n2." !in rules, rules)
    }

    @Test
    fun `always allow and trust trusts the folder of a local program for good, allow and mark taints`() {
        val folder = script("first")
        val other = script("second")
        app.model
            .reply { toolCall("command.exec", gradle("test", folder)) }.reply { text("Tested.") }
            .reply { toolCall("command.exec", gradle("test", folder)) }.reply { text("Tested again.") }
            .reply { toolCall("command.exec", gradle("test", other)) }.reply { text("Tested there.") }
        lateinit var buttons: List<List<String>>
        lateinit var answered: String
        var trusted: ConversationTaint? = null
        var again: ConversationTaint? = null
        var marked: ConversationTaint? = null

        app.run(app.builder(directory)) {
            val conversations = get<ConversationStore>()
            suspend fun taint() = conversations.taint(conversations.current(app.key(app.adaChat)).id)
            app.send(app.ada, app.adaChat, "Run the tests.")
            val asked = app.fake.await(WAIT) { botMessages(ADA).firstOrNull { it.buttons.isNotEmpty() } }
            buttons = asked.buttons.map { row -> row.map { it.text } }
            press { it.startsWith("Always allow and trust") }
            app.message(app.adaChat, "Tested.")
            idle()
            answered = app.fake.botMessage(ADA, asked.id)!!.text
            trusted = taint()
            app.send(app.ada, app.adaChat, "Run them again.")
            app.message(app.adaChat, "Tested again.")
            idle()
            again = taint()
            app.send(app.ada, app.adaChat, "Run the other ones.")
            press { it == "Allow, mark tainted" }
            app.message(app.adaChat, "Tested there.")
            idle()
            marked = taint()
        }

        assertEquals(listOf("Allow, mark tainted", "Deny"), buttons.first())
        val trust = buttons[1].single()
        assertTrue(trust.startsWith("Always allow and trust folder ") && trust.endsWith("/first"), trust)
        assertContains(answered.substringAfterLast("\n"), "Allowed by Ada Lovelace (@ada_test), from now on also ")
        assertContains(answered.substringAfterLast("\n"), ", who trusted ")
        assertNull(trusted)
        assertNull(again)
        assertContains(marked?.reason.orEmpty(), other.toString())
    }

    /** A folder [name] beside the app's data holding an executable `gradlew` that echoes its arguments. */
    private fun script(name: String): Path {
        val folder = directory.toRealPath().resolve(name).createDirectories()
        val gradlew = folder.resolve("gradlew")
        gradlew.writeText("#!/bin/sh\necho \"ran $*\"\n")
        assertTrue(gradlew.toFile().setExecutable(true))
        return folder
    }

    private fun gradle(task: String, folder: Path): String =
        JsonObject(mapOf("command" to JsonPrimitive("./gradlew $task"), "cwd" to JsonPrimitive("$folder"))).toString()

    /** Presses the button that [chosen] picks on the approval request in Ada's chat and returns its text. */
    private fun press(chosen: (String) -> Boolean): String {
        val asked = app.fake.await(WAIT) { botMessages(ADA).firstOrNull { it.buttons.isNotEmpty() } }
        val button = asked.buttons.flatten().first { chosen(it.text) }.text
        app.fake.press(app.ada, asked, button)
        app.fake.await(WAIT) { botMessage(ADA, asked.id)?.takeIf { it.buttons.isEmpty() } }
        return button
    }

    @Test
    fun `a chat's permission mode survives a new conversation`() {
        app.run(app.builder(directory)) {
            app.send(app.ada, app.adaChat, "/mode plan")
            app.message(app.adaChat, "The permission mode of this chat is now plan.")
            app.send(app.ada, app.adaChat, "/new")
            app.message(app.adaChat, "A new conversation with coder has started.")
            app.send(app.ada, app.adaChat, "/mode")
            app.message(app.adaChat, "Permission mode: plan (set in this chat). $modes")
        }
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
        assertEquals("Permission mode: bypass (set in this chat) for 2 h more", settings.lines()[6])
    }

    private companion object {
        val GUEST_TOOLS = """
            {
              "previewIntervalMillis": 0,
              "agents": {
                "coder": {
                  "name": "Coder",
                  "model": "scripted/test-model",
                  "channels": {"telegram:work": {}},
                  "tools": {"forGuests": ["notes.write"]}
                }
              }
            }
        """.trimIndent()
    }
}
