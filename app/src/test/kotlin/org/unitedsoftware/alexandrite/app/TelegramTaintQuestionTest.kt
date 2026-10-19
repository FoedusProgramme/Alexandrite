package org.unitedsoftware.alexandrite.app

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.app.TelegramApp.Companion.ADA
import org.unitedsoftware.alexandrite.app.TelegramApp.Companion.BOB
import org.unitedsoftware.alexandrite.app.TelegramApp.Companion.BOT_MENTION
import org.unitedsoftware.alexandrite.app.TelegramApp.Companion.OFFERED
import org.unitedsoftware.alexandrite.app.TelegramApp.Companion.WAIT
import org.unitedsoftware.alexandrite.channel.telegram.fake.FakeBotMessage
import org.unitedsoftware.alexandrite.channel.telegram.fake.FakeChat
import org.unitedsoftware.alexandrite.sdk.store.ConversationStore
import org.unitedsoftware.alexandrite.sdk.store.ConversationTaint
import org.unitedsoftware.alexandrite.sdk.transcript.ToolOutcome
import org.unitedsoftware.alexandrite.sdk.transcript.ToolResultEntry
import org.unitedsoftware.alexandrite.sdk.turn.TrustControl
import org.unitedsoftware.alexandrite.testkit.PluginHarness
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TelegramTaintQuestionTest {
    @TempDir
    lateinit var directory: Path

    private val app = TelegramApp()

    /** A repository outside the trusted places, with a file in it. */
    private val project: Path get() = directory.toRealPath().resolve("clone")
    private val readme: Path get() = project.resolve("README.md")

    @AfterTest
    fun close() {
        app.close()
    }

    private fun writeProject() {
        project.resolve(".git").createDirectories()
        readme.writeText("# Clone\n")
    }

    private fun reading(path: Path): String = JsonObject(mapOf("path" to JsonPrimitive("$path"))).toString()

    private suspend fun PluginHarness.Running.taint(chat: FakeChat = app.adaChat): ConversationTaint? {
        val conversations = get<ConversationStore>()
        return conversations.taint(conversations.current(app.key(chat)).id)
    }

    private fun question(): FakeBotMessage = app.fake.await(WAIT) {
        botMessages(ADA).firstOrNull { it.buttons.isNotEmpty() }
    }

    private fun answer(asked: FakeBotMessage, button: String) {
        app.fake.press(app.ada, asked, button)
        app.fake.await(WAIT) { botMessage(asked.chat, asked.id)?.takeIf { it.buttons.isEmpty() } }
    }

    @Test
    fun `a read outside the trusted places asks, and trusting the project adds the folder and keeps it clean`() {
        writeProject()
        app.model.reply { toolCall("fs.read", reading(readme)) }.reply { text("It is a clone.") }
            .reply { toolCall("fs.read", reading(readme)) }.reply { text("Still a clone.") }
        lateinit var asked: FakeBotMessage
        var clean: ConversationTaint? = null
        var still: ConversationTaint? = null
        var trusted = emptyList<Path>()

        app.run(app.builder(directory)) {
            app.send(app.ada, app.adaChat, "What is this clone?")
            asked = question()
            answer(asked, asked.buttons.flatten().single { it.text.startsWith("Allow and trust") }.text)
            app.message(app.adaChat, "It is a clone.")
            idle()
            clean = taint()
            app.send(app.ada, app.adaChat, "Read it again.")
            app.message(app.adaChat, "Still a clone.")
            idle()
            still = taint()
            trusted = get<TrustControl>().folders().map { it.path }
        }

        val buttons = asked.buttons.map { row -> row.map { it.text } }
        assertEquals(listOf("Allow, mark tainted", "Deny"), buttons.first())
        assertTrue(buttons[1].single().startsWith("Allow and trust"), "$buttons")
        assertContains(asked.text, "$project")
        assertContains(asked.text, "Trusting a folder also trusts whatever is written in it later")
        assertNull(clean)
        assertNull(still)
        assertEquals(listOf(project), trusted)
        assertEquals(1, app.fake.botMessages(ADA).count { it.text.startsWith("Approval needed") })
    }

    @Test
    fun `allow and mark runs the read and taints the conversation, with no trust granted`() {
        writeProject()
        app.model.reply { toolCall("fs.read", reading(readme)) }.reply { text("It is a clone.") }
        var marked: ConversationTaint? = null
        var trusted = emptyList<Path>()

        app.run(app.builder(directory)) {
            app.send(app.ada, app.adaChat, "What is this clone?")
            answer(question(), "Allow, mark tainted")
            app.message(app.adaChat, "It is a clone.")
            idle()
            marked = taint()
            trusted = get<TrustControl>().folders().map { it.path }
        }

        assertEquals("fs.read reached $project, a folder that is not trusted", marked?.reason)
        assertEquals(emptyList(), trusted)
        val result = app.model.requests.last().history.filterIsInstance<ToolResultEntry>().single()
        assertEquals(ToolOutcome.Succeeded, result.outcome)
    }

    @Test
    fun `in bypass mode the read runs without a question and marks the conversation silently`() {
        writeProject()
        app.model.reply { toolCall("fs.read", reading(readme)) }.reply { text("It is a clone.") }
        var marked: ConversationTaint? = null

        app.run(app.builder(directory)) {
            app.send(app.ada, app.adaChat, "/mode bypass")
            app.messages(app.adaChat, 1)
            app.send(app.ada, app.adaChat, "What is this clone?")
            app.message(app.adaChat, "It is a clone.")
            idle()
            marked = taint()
        }

        assertEquals("fs.read reached $project, a folder that is not trusted", marked?.reason)
        assertEquals(emptyList(), app.fake.botMessages(ADA).filter { it.buttons.isNotEmpty() })
    }

    @Test
    fun `a member whom the channel trusts writes in a group without tainting it, and asks no operator`() {
        app.model.reply { text("Hello, Bob.") }
        var clean: ConversationTaint? = null

        app.run(app.builder(directory, trusted = listOf(BOB))) {
            app.send(app.bob, app.group, "$BOT_MENTION hello")
            app.message(app.group, "Hello, Bob.")
            idle()
            clean = taint(app.group)
        }

        assertNull(clean)
        assertEquals(emptyList(), app.fake.botMessages(ADA))
    }

    @Test
    fun `a member's message marks the group, and trusting them from the offer clears it and keeps it clean`() {
        app.model.reply { text("Hello, Bob.") }.reply { text("Hello again.") }
        var clean: ConversationTaint? = null
        lateinit var listed: String

        app.run(app.builder(directory)) {
            app.send(app.bob, app.group, "$BOT_MENTION hello")
            val offer = app.fake.await(WAIT) {
                botMessages(ADA).firstOrNull { it.text.startsWith(OFFERED) && it.buttons.isNotEmpty() }
            }
            answer(offer, offer.buttons.flatten().single { it.text.startsWith("Trust") }.text)
            app.message(app.group, "Hello, Bob.")
            idle()
            withTimeout(WAIT) { while (taint(app.group) != null) delay(10) }
            app.send(app.bob, app.group, "$BOT_MENTION hello again")
            app.message(app.group, "Hello again.")
            idle()
            clean = taint(app.group)
            app.send(app.ada, app.adaChat, "/trust")
            listed = app.fake.await(WAIT) { botMessages(ADA).lastOrNull { "No folder is trusted." in it.text } }.text
        }

        assertNull(clean)
        assertEquals(1, app.fake.botMessages(ADA).count { it.text.startsWith(OFFERED) })
        assertContains(listed, "Bob (@bob_test) (trusted by Ada Lovelace on ")
    }

    @Test
    fun `an operator trusts a member by replying to their message with trust, and untrusts them by name`() {
        lateinit var trusted: String
        lateinit var untrusted: String
        var people = emptyList<String>()

        app.run(app.builder(directory)) {
            val written = app.send(app.bob, app.group, "Lunch at noon?")
            app.consumed(1)
            app.fake.send(app.ada, app.group, "/trust", replyTo = written)
            trusted = app.fake.await(WAIT) { botMessages(app.group.id).lastOrNull { it.text.startsWith("Bob") } }.text
            people = get<TrustControl>().people(app.address(app.group)).map { it.person.displayName }
            app.send(app.ada, app.group, "/untrust @bob_test")
            untrusted = app.fake.await(WAIT) {
                botMessages(app.group.id).lastOrNull { it.text.startsWith("Bob") && it.text != trusted }
            }.text
        }

        assertEquals(listOf("Bob"), people)
        assertContains(trusted, "is trusted")
        assertContains(untrusted, "is no longer trusted")
    }
}
