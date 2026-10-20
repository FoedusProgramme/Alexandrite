package org.unitedsoftware.alexandrite.agent.permission

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.unitedsoftware.alexandrite.agent.StoreIndex
import org.unitedsoftware.alexandrite.agent.agentHarness
import org.unitedsoftware.alexandrite.agent.control.PendingCommands
import org.unitedsoftware.alexandrite.agent.execute
import org.unitedsoftware.alexandrite.agent.startingInBackground
import org.unitedsoftware.alexandrite.agent.trust.TurnTaint
import org.unitedsoftware.alexandrite.sdk.chat.AgentChatKey
import org.unitedsoftware.alexandrite.sdk.chat.ConversationId
import org.unitedsoftware.alexandrite.sdk.chat.ToolCallId
import org.unitedsoftware.alexandrite.sdk.chat.TurnId
import org.unitedsoftware.alexandrite.sdk.store.ConversationStore
import org.unitedsoftware.alexandrite.sdk.store.ConversationTaint
import org.unitedsoftware.alexandrite.sdk.tool.ToolResult
import org.unitedsoftware.alexandrite.sdk.transcript.UserEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UserOrigin
import org.unitedsoftware.alexandrite.sdk.turn.Admission
import org.unitedsoftware.alexandrite.sdk.turn.ConversationControl
import org.unitedsoftware.alexandrite.sdk.turn.TurnOutcome
import org.unitedsoftware.alexandrite.testkit.MemoryStore
import org.unitedsoftware.alexandrite.testkit.PluginHarness
import org.unitedsoftware.alexandrite.testkit.ScriptedModel
import org.unitedsoftware.alexandrite.testkit.recordingTool
import java.io.IOException
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** How the mark follows what guests write, what background jobs take in, and stores that fail. */
class TaintFlowTest {
    private val store = MemoryStore()
    private val model = ScriptedModel()
    private val key = AgentChatKey.parse("coder@test:main:chat")

    private val paste = recordingTool("notes.paste") { _, context ->
        context.untrusted("a pasted page")
        ToolResult("Pasted.")
    }

    /** Runs as a background job, held until a test releases it. */
    private val build = recordingTool("build.run").hold()

    /** Runs as a background job that reads a feed once [fed] completes and ends once [ended] does. */
    private val fed = CompletableDeferred<Unit>()
    private val ended = CompletableDeferred<Unit>()
    private val feed = recordingTool("feed.read") { _, context ->
        fed.await()
        context.untrusted("a feed")
        ended.await()
        ToolResult("Fed.")
    }

    private fun harness(): PluginHarness = agentHarness(CONFIG, store) {
        channel(operators = setOf("operator")).model(model).tool(paste)
            .tool(startingInBackground(build)).tool(startingInBackground(feed))
    }

    private suspend fun PluginHarness.Running.ask(user: String = "operator"): TurnOutcome =
        (channel().receive("Hi", sender = channel().user(user)) as Admission.Accepted).ticket.outcome()

    private suspend fun conversation(): ConversationId = store.conversations.current(key).id

    private suspend fun mark(): ConversationTaint? = store.conversations.taint(conversation())

    /** How many job results the conversation took in. */
    private suspend fun jobResults(): Int = store.transcripts.entries(conversation()).filterIsInstance<UserEntry>()
        .count { it.origin == UserOrigin.SystemWritten("alexandrite.job") }

    // Guests.

    @Test
    fun `a guest's message taints the conversation it joins, and an operator's does not`() {
        model.reply { text("Hello.") }.reply { text("Hello again.") }

        harness().execute {
            ask()
            val operator = mark()
            ask(user = "guest")

            assertEquals(null, operator)
            assertEquals("a message from someone whom the agent does not trust", mark()?.reason)
        }
    }

    @Test
    fun `a guest's command line that the model reads next taints the conversation first`() {
        model.reply { text("Seen.") }

        harness().execute {
            get<PendingCommands>().record(conversation(), channel().user("guest"), "/context", emptyList())
            ask()

            assertEquals("a command of someone whom the agent does not trust", mark()?.reason)
        }
    }

    // Background jobs.

    @Test
    fun `a job started in a tainted turn does not taint the conversation again once it was untainted`() {
        model.reply { toolCall("notes.paste", "{}") }.reply { toolCall("build.run", "{}") }.reply { text("Started.") }
            .reply { text("Built.") }

        harness().execute {
            ask()
            val before = mark()
            get<ConversationControl>().untaint(channel().chat(), channel().user("operator"))
            build.release()
            withTimeout(5_000) { while (jobResults() < 1) delay(5) }

            assertTrue(before?.reason.orEmpty().endsWith("a pasted page"), "$before")
            assertEquals(null, mark())
        }
    }

    @Test
    fun `a job that takes in untrusted content marks its conversation at once, before its result comes`() {
        model.reply { toolCall("feed.read", "{}", ToolCallId("call-1")) }.reply { text("Started.") }
            .reply { text("Read.") }

        harness().execute {
            ask()
            val before = mark()
            fed.complete(Unit)
            withTimeout(5_000) { while (mark() == null) delay(5) }
            val during = mark()?.reason
            val results = jobResults()
            ended.complete(Unit)
            withTimeout(5_000) { while (jobResults() < 1) delay(5) }

            assertEquals(null, before)
            assertEquals("call call-1 reported untrusted content: a feed", during)
            assertEquals(0, results)
        }
    }

    // A store that fails.

    @Test
    fun `a mark that cannot be stored or read fails the turn`() {
        model.reply { toolCall("notes.paste", "{}") }.reply { text("Pasted.") }
        val failing = Failing(store.conversations)

        agentHarness(CONFIG, null) {
            plugin(StoreIndex(store, failing))
            channel(operators = setOf("operator")).model(model).tool(paste)
        }.execute {
            val unstored = ask()
            failing.reads = true
            val unread = ask()

            assertIs<TurnOutcome.Failed>(unstored)
            assertIs<TurnOutcome.Failed>(unread)
            assertEquals(null, mark())
        }
    }

    // The mark itself.

    @Test
    fun `a mark read at the start is no untrusted content of the turn's own, and listeners hear each one`() {
        val earlier = ConversationTaint(Instant.parse("2026-01-01T00:00:00Z"), "the parent took in a web page")
        val taint = TurnTaint(TurnId("t-1"), key, recorded = earlier)
        val heard = CopyOnWriteArrayList<String>()

        taint.onNoted(heard::add)
        val before = heard.toList()
        taint.note("a feed")
        taint.note("a file")

        assertEquals(emptyList(), before)
        assertEquals("the parent took in a web page", taint.known?.reason)
        assertEquals(listOf("a feed", "a file"), heard)
    }

    /** [store]'s conversations, whose marks cannot be written, nor read while [reads] fail. */
    private class Failing(private val store: ConversationStore) : ConversationStore by store {
        @Volatile
        var reads = false

        override suspend fun markTainted(conversation: ConversationId, reason: String, at: Instant): Boolean =
            throw IOException("Read-only.")

        override suspend fun taint(conversation: ConversationId): ConversationTaint? {
            if (reads) throw IOException("Unreadable.")
            return store.taint(conversation)
        }
    }

    private companion object {
        val CONFIG = """
            {
              "previewIntervalMillis": 0,
              "agents": {"coder": {"model": "scripted/test-model", "channels": {"test:main": {}}}}
            }
        """.trimIndent()
    }
}
