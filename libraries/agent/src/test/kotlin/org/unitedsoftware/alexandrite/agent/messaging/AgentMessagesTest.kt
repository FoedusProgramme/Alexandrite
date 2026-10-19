package org.unitedsoftware.alexandrite.agent.messaging

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import org.unitedsoftware.alexandrite.agent.agentHarness
import org.unitedsoftware.alexandrite.agent.execute
import org.unitedsoftware.alexandrite.agent.job.Jobs
import org.unitedsoftware.alexandrite.sdk.chat.AgentChatKey
import org.unitedsoftware.alexandrite.sdk.chat.TurnKind
import org.unitedsoftware.alexandrite.sdk.tool.ToolResult
import org.unitedsoftware.alexandrite.sdk.tool.ToolRisk
import org.unitedsoftware.alexandrite.sdk.transcript.ContextPart
import org.unitedsoftware.alexandrite.sdk.transcript.NoticeEntry
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolResultEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UserEntry
import org.unitedsoftware.alexandrite.sdk.turn.Admission
import org.unitedsoftware.alexandrite.sdk.turn.TurnOutcome
import org.unitedsoftware.alexandrite.testkit.MemoryStore
import org.unitedsoftware.alexandrite.testkit.RecordingChannel
import org.unitedsoftware.alexandrite.testkit.RequestMatch
import org.unitedsoftware.alexandrite.testkit.ScriptedModel
import org.unitedsoftware.alexandrite.testkit.recordingTool
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
            channel(operators = setOf("operator")).model(model).tool(read).tool(write)
            if (home) channel(name = "other")
        }.execute {
            val admission =
                assertIs<Admission.Accepted>(channel().receive("Go.", channel().chat(), channel().user("operator")))
            assertIs<TurnOutcome.Completed>(admission.ticket.outcome())
            while (model.requests.size < 5) delay(10)
            val remote = model.requests.first { it.instructions.any { part -> part.text == "TARGET" } }
            val remoteTurn = assertNotNull(store.conversations.turn(remote.ids.turn))
            assertEquals(TurnKind.AGENT_MESSAGE, remoteTurn.kind)
            assertEquals(null, remoteTurn.actor)
            assertEquals(emptyList(), write.calls)
            assertEquals(null, store.conversations.taint(remote.ids.conversation))
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
    fun `a tainted sender taints the target, and a target that took in untrusted content taints the sender`() {
        val store = MemoryStore()
        val model = ScriptedModel()
        val sender = RequestMatch("sender") { it.instructions.any { part -> part.text == "SENDER" } }
        val target = RequestMatch("target") { it.instructions.any { part -> part.text == "TARGET" } }
        model.reply(sender) { toolCall("web.fetch") }
            .reply(sender) { toolCall("agent.send", """{"agent":"reviewer","message":"Review this."}""") }
            .reply(sender) { text("Queued.") }
            .reply(target) { text("Reviewed.") }
            .reply(sender) { text("Noted.") }
            .reply(sender) { toolCall("agent.send", """{"agent":"reader","message":"Read the page."}""") }
            .reply(sender) { text("Queued.") }
            .reply(target) { toolCall("web.fetch") }
            .reply(target) { text("It says hello.") }
            .reply(sender) { text("Noted.") }
        val fetch = recordingTool("web.fetch", ToolRisk.NETWORK_READ) { _, context ->
            context.untrusted("a web page")
            ToolResult("Page.")
        }
        agentHarness(
            """{
              "maxConcurrentTurns":1,
              "agents":{
                "coder":{"model":"scripted/test-model","instructions":["SENDER"],"channels":{"test:main":{"default":true}},"tools":{"allow":["web.fetch","agent.send"]}},
                "reviewer":{"model":"scripted/test-model","instructions":["TARGET"],"channels":{"test:main":{}},"tools":{"allow":["test.read"]}},
                "reader":{"model":"scripted/test-model","instructions":["TARGET"],"channels":{"test:main":{}},"tools":{"allow":["web.fetch"],"forGuests":["web.fetch"]}}
              }
            }""",
            store,
        ) {
            channel(
                operators = setOf("operator"),
            ).model(model).tool(fetch).tool(recordingTool("test.read", ToolRisk.READ_ONLY))
        }.execute {
            suspend fun send(chat: String) {
                val admission = channel().receive("Go.", channel().chat(chat), channel().user("operator"))
                assertIs<TurnOutcome.Completed>(assertIs<Admission.Accepted>(admission).ticket.outcome())
            }
            suspend fun taint(key: String) =
                store.conversations.taint(store.conversations.current(AgentChatKey.parse(key)).id)?.reason

            send("chat")
            model.awaitRequests(5)
            assertEquals(
                "a message from agent 'coder', whose conversation took in untrusted content " +
                    "(a call of web.fetch, which reads the network)",
                taint("reviewer@test:main:chat"),
            )
            send("other")
            model.awaitRequests(10)
            while (taint("coder@test:main:other") == null) delay(10)
            assertEquals(
                "a reply from agent 'reader', whose conversation took in untrusted content " +
                    "(a call of web.fetch, which reads the network)",
                taint("coder@test:main:other"),
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
                  "tools":{"allow":["test.read","test.exhaust","agent.delegate","agent.send"],"forGuests":["test.read","test.exhaust","agent.delegate","agent.send"]}
                },
                "helper":{"description":"Reviews privately.","instructions":["CHILD"],"tools":{"allow":["test.exhaust"]}}
              }
            }""",
            store,
        ) {
            channel(
                operators = setOf("operator"),
            ).model(model).tool(recordingTool("test.read", ToolRisk.READ_ONLY)).tool(exhaust)
        }.execute {
            val admission =
                assertIs<Admission.Accepted>(channel().receive("Go.", channel().chat(), channel().user("operator")))
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
            channel(operators = setOf("operator")).model(model).tool(recordingTool("test.read", ToolRisk.READ_ONLY))
        }.execute {
            val admission =
                assertIs<Admission.Accepted>(channel().receive("Go.", channel().chat(), channel().user("operator")))
            assertIs<TurnOutcome.Completed>(admission.ticket.outcome())
            while (model.requests.size < 11) delay(10)
            val recipient = store.conversations.current(AgentChatKey.parse("reviewer@test:main:chat"))
            assertEquals(3, model.requests.count { it.ids.conversation == recipient.id })
            val root = store.conversations.current(AgentChatKey.parse("coder@test:main:chat"))
            val results = store.transcripts.entries(root.id)
                .filterIsInstance<ToolResultEntry>()
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
            channel(operators = setOf("operator")).model(model).tool(recordingTool("test.read", ToolRisk.READ_ONLY))
        }.execute {
            val admission =
                assertIs<Admission.Accepted>(channel().receive("Go.", channel().chat(), channel().user("operator")))
            model.awaitRequests(2)
            assertTrue(admission.ticket.cancel())
            assertEquals(TurnOutcome.Cancelled, admission.ticket.outcome())
            delay(50)
            assertEquals(2, model.requests.size)
            assertEquals(1, channel().replies.size)
        }
    }

    @Test
    fun `a gated call of the target asks the chat even in auto mode, since the message carries no authority`() {
        val model = ScriptedModel()
        val sender = RequestMatch("sender") { it.instructions.any { part -> part.text == "SENDER" } }
        val target = RequestMatch("target") { it.instructions.any { part -> part.text == "TARGET" } }
        model.reply(sender) { toolCall("agent.send", """{"agent":"reviewer","message":"Write notes."}""") }
            .reply(sender) { text("Queued.") }
            .reply(target) { toolCall("fs.write", """{"path":"notes.md","content":"text"}""") }
            .reply(target) { text("Could not write.") }
            .reply(sender) { text("Noted.") }
        val write = recordingTool("fs.write", ToolRisk.WORKSPACE_WRITE)
        agentHarness(
            """{
              "maxConcurrentTurns":1,
              "agents":{
                "coder":{"model":"scripted/test-model","instructions":["SENDER"],"channels":{"test:main":{"default":true}},"tools":{"allow":["test.read","agent.send"]}},
                "reviewer":{
                  "model":"scripted/test-model","instructions":["TARGET"],"channels":{"test:main":{}},
                  "tools":{"allow":["fs.write"],"forGuests":["fs.write"]},"permission":{"mode":"auto"}
                }
              }
            }""",
        ) {
            channel(
                operators = setOf("operator"),
            ).model(model).tool(write).tool(recordingTool("test.read", ToolRisk.READ_ONLY))
        }.execute {
            val admission =
                assertIs<Admission.Accepted>(channel().receive("Go.", channel().chat(), channel().user("operator")))
            assertIs<TurnOutcome.Completed>(admission.ticket.outcome())
            model.awaitRequests(5)
            assertEquals(listOf("fs.write"), channel().approvals.map { it.tool })
            assertEquals(emptyList(), write.calls)
        }
    }

    @Test
    fun `a send the target cannot take gives its round trip back`() {
        val model = ScriptedModel()
        val sender = RequestMatch("sender") { it.instructions.any { part -> part.text == "SENDER" } }
        model.reply(sender) {
            repeat(4) { toolCall("agent.send", """{"agent":"reviewer","message":"Review $it."}""") }
        }.reply(sender) { text("Sent.") }
        repeat(8) { model.reply { text("Done.") } }
        val store = MemoryStore()
        agentHarness(
            """{
              "maxConcurrentTurns":1,
              "queue":{"perKey":1},
              "agents":{
                "coder":{"model":"scripted/test-model","instructions":["SENDER"],"channels":{"test:main":{"default":true}},"tools":{"allow":["test.read","agent.send"]}},
                "reviewer":{"model":"scripted/test-model","channels":{"test:main":{}},"tools":{"allow":["test.read"]}}
              }
            }""",
            store,
        ) {
            channel(operators = setOf("operator")).model(model).tool(recordingTool("test.read", ToolRisk.READ_ONLY))
        }.execute {
            val admission =
                assertIs<Admission.Accepted>(channel().receive("Go.", channel().chat(), channel().user("operator")))
            assertIs<TurnOutcome.Completed>(admission.ticket.outcome())
            val root = store.conversations.current(AgentChatKey.parse("coder@test:main:chat"))
            val texts = store.transcripts.entries(root.id).filterIsInstance<ToolResultEntry>().map { result ->
                result.content.filterIsInstance<TextPart>().joinToString("") { it.text }
            }
            assertEquals(4, texts.size)
            // Without the refund the fourth send would find all three round trips used.
            assertTrue(texts.none { "limit of 3" in it }, texts.toString())
            assertTrue(texts.count { "could not accept" in it } >= 2, texts.toString())
            while (model.requests.size < 2 + 2 * texts.count { "queued" in it }) delay(10)
        }
    }

    @Test
    fun `a restart while the target works tells the sender that no reply will come`() {
        val store = MemoryStore()
        val model = ScriptedModel()
        val sender = RequestMatch("sender") { it.instructions.any { part -> part.text == "SENDER" } }
        val target = RequestMatch("target") { it.instructions.any { part -> part.text == "TARGET" } }
        model.reply(sender) { toolCall("agent.send", """{"agent":"reviewer","message":"Review this."}""") }
            .reply(sender) { text("Queued.") }
            .reply(target) { hang() }
        val config = """{
              "maxConcurrentTurns":2,
              "shutdown":{"turnGraceSeconds":0,"cancelJoinSeconds":2},
              "agents":{
                "coder":{"model":"scripted/test-model","instructions":["SENDER"],"channels":{"test:main":{"default":true}},"tools":{"allow":["test.read","agent.send"]}},
                "reviewer":{"model":"scripted/test-model","instructions":["TARGET"],"channels":{"test:main":{}},"tools":{"allow":["test.read"]}}
              }
            }"""
        val read = recordingTool("test.read", ToolRisk.READ_ONLY)
        agentHarness(config, store) { channel(operators = setOf("operator")).model(model).tool(read) }.execute {
            val admission =
                assertIs<Admission.Accepted>(channel().receive("Go.", channel().chat(), channel().user("operator")))
            assertIs<TurnOutcome.Completed>(admission.ticket.outcome())
            model.awaitRequests(3)
        }
        model.reply(sender) { text("The reviewer was interrupted.") }
        agentHarness(config, store) { channel(operators = setOf("operator")).model(model).tool(read) }.execute {
            val admission =
                assertIs<Admission.Accepted>(channel().receive("Status?", channel().chat(), channel().user("operator")))
            assertIs<TurnOutcome.Completed>(admission.ticket.outcome())
            val notices = model.requests.last().history.filterIsInstance<UserEntry>().flatMap { entry ->
                entry.parts.filterIsInstance<ContextPart>().map { it.text }
            }
            assertTrue(
                notices.any {
                    "your message to agent reviewer was interrupted by a restart, so no reply will come" in it
                },
                notices.toString(),
            )
            val notice = store.transcripts.entries(
                store.conversations.current(AgentChatKey.parse("coder@test:main:chat")).id,
            )
                .filterIsInstance<NoticeEntry>().map { it.text }
            assertTrue(
                "The message to agent reviewer was interrupted by a restart; no reply will come." in notice,
                notice.toString(),
            )
        }
    }

    @Test
    fun `a refunded round trip can be taken again`() {
        val budget = MessageBudget()
        repeat(3) { assertTrue(budget.take()) }
        budget.refund()
        assertTrue(budget.take())
        assertEquals(false, budget.take())
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
