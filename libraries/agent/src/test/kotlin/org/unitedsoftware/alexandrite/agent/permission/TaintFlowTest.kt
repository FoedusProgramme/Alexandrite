package org.unitedsoftware.alexandrite.agent.permission

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.unitedsoftware.alexandrite.agent.StoreIndex
import org.unitedsoftware.alexandrite.agent.agentHarness
import org.unitedsoftware.alexandrite.agent.blocking
import org.unitedsoftware.alexandrite.agent.control.PendingCommands
import org.unitedsoftware.alexandrite.agent.execute
import org.unitedsoftware.alexandrite.agent.startingInBackground
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
import java.time.Clock
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
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

    private fun harness(): PluginHarness = agentHarness(
        """
        {
          "previewIntervalMillis": 0,
          "agents": {"coder": {"model": "scripted/test-model", "channels": {"test:main": {}}}}
        }
        """.trimIndent(),
        store,
    ) {
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
            assertEquals("a message from someone who is no operator", mark()?.reason)
        }
    }

    @Test
    fun `a guest's command line that the model reads next taints the conversation first`() {
        model.reply { text("Seen.") }

        harness().execute {
            get<PendingCommands>().record(conversation(), channel().user("guest"), "/context", emptyList())
            ask()

            assertEquals("a command of someone who is no operator", mark()?.reason)
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

    @Test
    fun `a sub-agent run of a tainted turn counts as tainted even when no mark can be stored`() {
        val seen = CopyOnWriteArrayList<Boolean>()
        val probe = recordingTool("probe.taint") { _, context ->
            seen += context.tainted
            ToolResult("Probed.")
        }
        model.reply { toolCall("notes.paste", "{}") }
            .reply { toolCall("agent.delegate", """{"profile": "prober", "task": "Probe."}""") }
            .reply { toolCall("probe.taint", "{}") }
            .reply { text("Probed.") }
            .reply { text("Done.") }
        val config = """
            {
              "previewIntervalMillis": 0,
              "agents": {
                "coder": {"model": "scripted/test-model", "channels": {"test:main": {}}},
                "prober": {"description": "Probes.", "instructions": ["Probe."], "tools": {"allow": ["probe.taint"]}}
              }
            }
        """.trimIndent()

        agentHarness(config, null) {
            plugin(StoreIndex(store, Failing(store.conversations)))
            channel(operators = setOf("operator")).model(model).tool(paste).tool(probe)
        }.execute { ask() }

        assertEquals(listOf(true), seen)
        assertEquals(null, blocking { mark() })
    }

    // The mark itself.

    @Test
    fun `a mark that cannot be stored is carried by the conversation's later turns until it is stored or cleared`() {
        val failing = Failing(store.conversations)
        val unstored = UnstoredTaints()
        val taints = ConversationTaints(failing, Clock.systemUTC(), unstored)
        blocking {
            val conversation = conversation()
            val first = taints.open(TurnId("t-1"), key, conversation)
            first.mark("a web page")
            val second = taints.open(TurnId("t-2"), key, conversation)
            val carried = second.tainted to second.known?.reason
            failing.fails = false
            second.record()
            val third = taints.open(TurnId("t-3"), key, conversation)

            assertEquals(true to "a web page", carried)
            assertEquals(null, second.noted)
            assertEquals("a web page", store.conversations.taint(conversation)?.reason)
            assertEquals(true to null, third.tainted to unstored[conversation])
            failing.fails = true
            third.recordLate("a feed")
            assertEquals("a feed", unstored.cleared(conversation)?.reason)
            assertEquals(null, unstored[conversation])
        }
    }

    @Test
    fun `a mark taken over from above is no untrusted content of the turn's own, and listeners hear each one`() {
        val taint = TurnTaint(TurnId("t-1"), key)
        val heard = CopyOnWriteArrayList<String>()

        taint.carry("the parent took in a web page")
        taint.onNoted(heard::add)
        val carried = taint.tainted to taint.noted
        taint.note("a feed")
        taint.note("a file")

        assertEquals(true to null, carried)
        assertEquals("the parent took in a web page", taint.known?.reason)
        assertEquals("a feed", taint.noted?.reason)
        assertEquals(listOf("a feed", "a file"), heard)
    }

    /** [store]'s conversations, whose marks cannot be written while [fails]. */
    private class Failing(private val store: ConversationStore) : ConversationStore by store {
        @Volatile
        var fails = true

        override suspend fun markTainted(conversation: ConversationId, reason: String, at: Instant): Boolean {
            if (fails) throw IOException("Read-only.")
            return store.markTainted(conversation, reason, at)
        }
    }
}
