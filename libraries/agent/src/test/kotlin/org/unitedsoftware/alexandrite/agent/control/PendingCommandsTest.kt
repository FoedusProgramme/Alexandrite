package org.unitedsoftware.alexandrite.agent.control

import org.unitedsoftware.alexandrite.agent.agentHarness
import org.unitedsoftware.alexandrite.agent.blocking
import org.unitedsoftware.alexandrite.agent.execute
import org.unitedsoftware.alexandrite.sdk.chat.AgentChatKey
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.chat.ConversationId
import org.unitedsoftware.alexandrite.sdk.transcript.ContextPart
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.sdk.transcript.TranscriptEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UserEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UserOrigin
import org.unitedsoftware.alexandrite.sdk.turn.Admission
import org.unitedsoftware.alexandrite.sdk.turn.CommandContext
import org.unitedsoftware.alexandrite.sdk.turn.CommandHandler
import org.unitedsoftware.alexandrite.sdk.turn.CommandInvocation
import org.unitedsoftware.alexandrite.sdk.turn.CommandSpec
import org.unitedsoftware.alexandrite.sdk.turn.TurnOutcome
import org.unitedsoftware.alexandrite.testkit.MemoryStore
import org.unitedsoftware.alexandrite.testkit.PluginHarness
import org.unitedsoftware.alexandrite.testkit.ScriptedModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PendingCommandsTest {
    private val store = MemoryStore()
    private val model = ScriptedModel()
    private val commands = TestCommands()
    private val key = AgentChatKey.parse("coder@test:main:chat")

    private fun harness(): PluginHarness = agentHarness(
        """
        {
          "previewIntervalMillis": 0,
          "agents": {"coder": {"model": "scripted/test-model", "channels": {"test:main": {}}}}
        }
        """.trimIndent(),
        store,
    ) { channel(operators = setOf("operator")).model(model).commandHandler(commands).commandHandler(EchoCommands()) }

    private suspend fun conversation(): ConversationId = store.conversations.current(key).id

    private suspend fun stored(conversation: ConversationId? = null): List<TranscriptEntry> =
        store.transcripts.entries(conversation ?: conversation())

    private fun kinds(entries: List<TranscriptEntry>) = entries.map { it::class.simpleName }

    private fun notices(entries: List<TranscriptEntry>): List<String> = entries.filterIsInstance<UserEntry>()
        .filter { it.origin == UserOrigin.SystemWritten(COMMANDS_SOURCE) }
        .map { (it.parts.single() as ContextPart).text }

    /** The text of [entry]'s parts, for a [UserEntry]. */
    private fun texts(entry: TranscriptEntry): String = (entry as? UserEntry)?.parts.orEmpty().joinToString(" ") {
        when (it) {
            is ContextPart -> it.text
            is TextPart -> it.text
            else -> ""
        }
    }

    /** The pending commands kept of each conversation, by conversation id. */
    private fun recorded(): Map<String, String> =
        blocking { store.records.list("alexandrite-agent", "pending_commands") }.associate { it.key to it.value }

    private suspend fun PluginHarness.Running.ask(text: String = "Hi", issuer: ChatUser = channel().user("operator")) {
        assertIs<Admission.Accepted>(channel().receive(text, sender = issuer)).ticket.outcome()
    }

    private suspend fun PluginHarness.Running.runCommand(
        name: String,
        arguments: String = "",
        issuer: ChatUser = channel().user("operator"),
    ) {
        assertIs<Admission.Accepted>(channel().receiveCommand(name, arguments, issuer = issuer)).ticket.outcome()
    }

    @Test
    fun `a command then a message tells the model, right before the opening message`() {
        model.reply { text("Hello.") }

        harness().execute {
            runCommand("echo", "Hi!")
            ask("Hi")
        }

        blocking {
            val entries = stored()
            assertEquals(listOf("UserEntry", "UserEntry", "AssistantEntry"), kinds(entries))
            assertEquals(UserOrigin.SystemWritten(COMMANDS_SOURCE), (entries[0] as UserEntry).origin)
            assertIs<UserOrigin.FromChat>((entries[1] as UserEntry).origin)
            assertTrue("ran /echo Hi!" in notices(entries).single())
            assertEquals(notices(entries), notices(model.requests.single().history))
        }
    }

    @Test
    fun `several commands batch into one entry, the oldest dropped past 20`() {
        harness().execute {
            repeat(25) { runCommand("echo", "$it") }

            val pending = get<PendingCommands>().pending(conversation())
            assertEquals((5..24).map { "/echo $it" }, pending!!.commands.map { it.commandLine })
        }
    }

    @Test
    fun `a hidden reply is never shown to the model`() {
        model.reply { text("Hi.") }

        harness().execute {
            runCommand("secret")
            ask("Hi")
        }

        blocking {
            val notice = notices(stored()).single()
            assertTrue(HIDDEN_REPLY in notice, notice)
            assertTrue("s3cr3t-code" !in notice, notice)
        }
        assertTrue(model.requests.single().history.none { "s3cr3t-code" in texts(it) })
    }

    @Test
    fun `a command run beside a hung turn leaves it untouched, and tells the next turn instead`() {
        model.reply {
            text("Hel")
            hang()
        }.reply { text("Noted.") }

        harness().execute {
            commands.connect(get(), get(), get())
            val channel = channel()
            val operator = channel.user("operator")
            val running = assertIs<Admission.Accepted>(channel.receive("Hi", sender = operator))
            model.awaitRequests(1)
            val cancel = assertIs<Admission.Accepted>(channel.receiveCommand("cancel", issuer = operator))

            assertIs<TurnOutcome.Completed>(cancel.ticket.outcome())
            assertEquals(TurnOutcome.Cancelled, running.ticket.outcome())
            assertEquals(emptyList(), stored())

            ask("Again")
        }

        blocking {
            val entries = stored()
            assertEquals(listOf("UserEntry", "UserEntry", "AssistantEntry"), kinds(entries))
            assertTrue("ran /cancel and was told: \"Cancelled.\"" in notices(entries).single())
        }
    }

    @Test
    fun `a new conversation starts clean, and the sealed one's pending commands are forgotten`() {
        model.reply { text("Hello.") }
        var sealed: ConversationId? = null

        harness().execute {
            commands.connect(get(), get(), get())
            runCommand("echo", "Hi!")
            sealed = conversation()
            runCommand("new")
            ask("Hi")
        }

        assertTrue("$sealed" !in recorded().keys)
        blocking { assertEquals(emptyList(), notices(stored())) }
    }
}

/** `/echo <text>` replies with it, `/secret` tells it a fixed secret hidden from the model. */
private class EchoCommands : CommandHandler {
    override val commands: List<CommandSpec> = listOf("echo", "secret").map {
        CommandSpec.builder(it, "Test command /$it.").build()
    }

    override suspend fun handle(invocation: CommandInvocation, context: CommandContext) {
        when (invocation.name) {
            "echo" -> context.reply(invocation.arguments)
            "secret" -> context.reply("s3cr3t-code", forModel = false)
        }
    }
}
