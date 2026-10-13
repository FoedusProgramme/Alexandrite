package org.foedusprogramme.alexandrite.agent.messaging

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import org.foedusprogramme.alexandrite.agent.agentHarness
import org.foedusprogramme.alexandrite.agent.execute
import org.foedusprogramme.alexandrite.agent.job.Jobs
import org.foedusprogramme.alexandrite.sdk.chat.AgentChatKey
import org.foedusprogramme.alexandrite.sdk.chat.TurnKind
import org.foedusprogramme.alexandrite.sdk.tool.ToolResult
import org.foedusprogramme.alexandrite.sdk.tool.ToolRisk
import org.foedusprogramme.alexandrite.sdk.transcript.TextPart
import org.foedusprogramme.alexandrite.sdk.transcript.ToolResultEntry
import org.foedusprogramme.alexandrite.sdk.transcript.UserEntry
import org.foedusprogramme.alexandrite.sdk.turn.Admission
import org.foedusprogramme.alexandrite.sdk.turn.TurnOutcome
import org.foedusprogramme.alexandrite.testkit.MemoryStore
import org.foedusprogramme.alexandrite.testkit.RecordingChannel
import org.foedusprogramme.alexandrite.testkit.RequestMatch
import org.foedusprogramme.alexandrite.testkit.ScriptedModel
import org.foedusprogramme.alexandrite.testkit.recordingTool
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AgentMessagesTest {
    @Test
    fun `message and reply run internally untrusted without a chat reply or authority`() = verifyDelivery(false)

    @Test
    fun `message falls back to target home on another channel instance`() = verifyDelivery(true)

    private fun verifyDelivery(home: Boolean) {
        val store = MemoryStore()
        val model = ScriptedModel()
        val sender = RequestMatch("sender") { it.instructions.any { part -> part.text == "SENDER" } }
        val target = RequestMatch("target") { it.instructions.any { part -> part.text == "TARGET" } }
        model.reply(sender) { toolCall("agent.send", """{"agent":"reviewer","message":"Review this."}""") }
            .reply(sender) { text("Queued.") }
            .reply(target) { toolCall("fs.write", """{"path":"notes.md","content":"bad"}""") }
            .reply(target) { text("Reviewed.") }
            .reply(sender) { text("Internal acknowledgement.") }
        val read = recordingTool("test.read", ToolRisk.READ_ONLY)
        val write = recordingTool("fs.write", ToolRisk.WORKSPACE_WRITE)
        val targetChannel = if (home) "\"test:other\":{\"home\":\"home\"}" else "\"test:main\":{}"
        agentHarness(
            """{
              "maxConcurrentTurns":1,
              "agents":{
                "coder":{"model":"scripted/test-model","instructions":["SENDER"],"channels":{"test:main":{"default":true}},"tools":{"allow":["test.read","agent.send"]}},
                "reviewer":{"model":"scripted/test-model","instructions":["TARGET"],"channels":{$targetChannel},"tools":{"allow":["test.read","agent.send","fs.write"]}}
              }
            }""",
            store,
        ) {
            channel(admins = setOf("admin")).model(model).tool(read).tool(write)
            if (home) channel(name = "other")
        }.execute {
            val admission =
                assertIs<Admission.Accepted>(channel().receive("Go.", channel().chat(), channel().user("admin")))
            assertIs<TurnOutcome.Completed>(admission.ticket.outcome())
            while (model.requests.size < 5) delay(10)
            val remote = model.requests.first { it.instructions.any { part -> part.text == "TARGET" } }
            val remoteTurn = assertNotNull(store.conversations.turn(remote.ids.turn))
            assertEquals(TurnKind.AGENT_MESSAGE, remoteTurn.kind)
            assertEquals(null, remoteTurn.actor)
            assertEquals(emptyList(), write.calls)
            assertNotNull(store.conversations.taint(remote.ids.conversation))
            val response = model.requests.last()
            assertTrue(
                response.history.filterIsInstance<UserEntry>().last().parts.filterIsInstance<TextPart>().any {
                    "Reviewed." in
                        it.text
                },
            )
            assertEquals(1, channel().replies.size)
            assertEquals("Queued.", channel().reply(admission.ticket.turn).completed?.text)
            assertEquals(
                AgentChatKey.parse(if (home) "reviewer@test:other:home" else "reviewer@test:main:chat"),
                remoteTurn.key,
            )
        }
    }

    @Test
    fun `background delegation from an internal message keeps progress and the job result out of the chat`() {
        val store = MemoryStore()
        val model = ScriptedModel()
        val release = CompletableDeferred<Unit>()
        val sender = RequestMatch("sender") { it.instructions.any { part -> part.text == "SENDER" } }
        val target = RequestMatch("target") { it.instructions.any { part -> part.text == "TARGET" } }
        val child = RequestMatch("child") { it.instructions.any { part -> part.text == "CHILD" } }
        model.reply(sender) { toolCall("agent.send", """{"agent":"reviewer","message":"Review this."}""") }
            .reply(sender) { text("Queued.") }
            .reply(target) {
                toolCall("agent.delegate", """{"profile":"helper","task":"Review internally.","background":true}""")
            }
            .reply(target) { text("Working internally.") }
            .reply(child) { toolCall("test.exhaust", "{}") }
            .reply(child) {
                pause { release.await() }
                text("Private child report.")
            }
            .reply(sender) { text("Internal acknowledgement.") }
            .reply(target) { toolCall("agent.send", """{"agent":"coder","message":"Bypass budget."}""") }
            .reply(target) { text("Private job acknowledgement.") }
        val exhaust = recordingTool("test.exhaust", ToolRisk.READ_ONLY) { _, _ ->
            val budget = assertNotNull(currentCoroutineContext()[MessageBudget])
            assertTrue(budget.take())
            assertTrue(budget.take())
            ToolResult("Budget consumed.")
        }
        agentHarness(
            """{
              "maxConcurrentTurns":1,
              "agents":{
                "coder":{
                  "model":"scripted/test-model","instructions":["SENDER"],
                  "channels":{"test:main":{"default":true}},"tools":{"allow":["test.read","agent.send"]}
                },
                "reviewer":{
                  "model":"scripted/test-model","instructions":["TARGET"],"channels":{"test:main":{}},
                  "tools":{"allow":["test.read","test.exhaust","agent.delegate","agent.send"],"forMembers":["test.read","test.exhaust","agent.delegate","agent.send"]}
                },
                "helper":{"description":"Reviews privately.","instructions":["CHILD"],"tools":{"allow":["test.exhaust"]}}
              }
            }""",
            store,
        ) {
            channel(
                admins = setOf("admin"),
            ).model(model).tool(recordingTool("test.read", ToolRisk.READ_ONLY)).tool(exhaust)
        }.execute {
            val admission =
                assertIs<Admission.Accepted>(channel().receive("Go.", channel().chat(), channel().user("admin")))
            assertIs<TurnOutcome.Completed>(admission.ticket.outcome())
            while (model.requests.size < 7) delay(10)
            assertEquals(1, channel().replies.size)
            release.complete(Unit)
            while (model.requests.size < 9 || get<Jobs>().list(channel().chat()).isNotEmpty()) delay(10)
            val jobTurn = assertNotNull(store.conversations.turn(model.requests.last().ids.turn))
            assertEquals(TurnKind.JOB, jobTurn.kind)
            while (store.conversations.turn(jobTurn.id)?.endedAt == null) delay(10)
            val results = store.transcripts.turnEntries(jobTurn.id).filterIsInstance<ToolResultEntry>()
            assertTrue(results.single().outcome.isError)
            assertTrue(results.single().content.filterIsInstance<TextPart>().any { "limit of 3" in it.text })
            assertEquals(1, channel().replies.size)
            assertEquals("Queued.", channel().reply(admission.ticket.turn).completed?.text)
            assertTrue(channel().events.none { it is RecordingChannel.Event.Sent })
        }
    }

    @Test
    fun `four sends from one root enqueue only three requests and their internal replies`() {
        val store = MemoryStore()
        val model = ScriptedModel()
        repeat(4) {
            model.reply { toolCall("agent.send", """{"agent":"reviewer","message":"Review this."}""") }
        }
        repeat(7) { model.reply { text("Done.") } }
        agentHarness(
            """{
              "maxConcurrentTurns":1,
              "agents":{
                "coder":{"model":"scripted/test-model","channels":{"test:main":{"default":true}},"tools":{"allow":["test.read","agent.send"]}},
                "reviewer":{"model":"scripted/test-model","channels":{"test:main":{}},"tools":{"allow":["test.read","agent.send"]}}
              }
            }""",
            store,
        ) {
            channel(admins = setOf("admin")).model(model).tool(recordingTool("test.read", ToolRisk.READ_ONLY))
        }.execute {
            val admission =
                assertIs<Admission.Accepted>(channel().receive("Go.", channel().chat(), channel().user("admin")))
            assertIs<TurnOutcome.Completed>(admission.ticket.outcome())
            while (model.requests.size < 11) delay(10)
            val recipient = store.conversations.current(AgentChatKey.parse("reviewer@test:main:chat"))
            assertEquals(3, model.requests.count { it.ids.conversation == recipient.id })
            val root = store.conversations.current(AgentChatKey.parse("coder@test:main:chat"))
            val results = store.transcripts.entries(root.id)
                .filterIsInstance<org.foedusprogramme.alexandrite.sdk.transcript.ToolResultEntry>()
            assertEquals(4, results.size)
            assertTrue(results.last().outcome.isError)
            assertTrue(results.last().content.filterIsInstance<TextPart>().any { "limit of 3" in it.text })
            assertEquals(1, channel().replies.size)
        }
    }

    @Test
    fun `cancelling the root drops a queued internal message`() {
        val model = ScriptedModel()
        model.reply { toolCall("agent.send", """{"agent":"reviewer","message":"Review this."}""") }
            .reply { hang() }
        agentHarness(
            """{
              "maxConcurrentTurns":1,
              "agents":{
                "coder":{"model":"scripted/test-model","channels":{"test:main":{"default":true}},"tools":{"allow":["test.read","agent.send"]}},
                "reviewer":{"model":"scripted/test-model","channels":{"test:main":{}},"tools":{"allow":["test.read","agent.send"]}}
              }
            }""",
        ) {
            channel(admins = setOf("admin")).model(model).tool(recordingTool("test.read", ToolRisk.READ_ONLY))
        }.execute {
            val admission =
                assertIs<Admission.Accepted>(channel().receive("Go.", channel().chat(), channel().user("admin")))
            model.awaitRequests(2)
            assertTrue(admission.ticket.cancel())
            assertEquals(TurnOutcome.Cancelled, admission.ticket.outcome())
            delay(50)
            assertEquals(2, model.requests.size)
            assertEquals(1, channel().replies.size)
        }
    }

    @Test
    fun `budget is shared and allows only three round trips`() {
        val budget = MessageBudget()
        assertTrue(budget.take())
        assertTrue(budget.take())
        assertTrue(budget.take())
        assertEquals(false, budget.take())
    }
}
