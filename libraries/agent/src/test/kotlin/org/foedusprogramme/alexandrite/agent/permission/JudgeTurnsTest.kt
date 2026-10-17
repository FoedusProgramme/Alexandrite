package org.foedusprogramme.alexandrite.agent.permission

import kotlinx.serialization.json.JsonObject
import org.foedusprogramme.alexandrite.agent.agentHarness
import org.foedusprogramme.alexandrite.agent.execute
import org.foedusprogramme.alexandrite.agent.turn.Interceptor
import org.foedusprogramme.alexandrite.sdk.channel.ApprovalAnswer
import org.foedusprogramme.alexandrite.sdk.channel.IncomingMessage
import org.foedusprogramme.alexandrite.sdk.chat.AgentChatKey
import org.foedusprogramme.alexandrite.sdk.chat.ForwardKind
import org.foedusprogramme.alexandrite.sdk.chat.ForwardOrigin
import org.foedusprogramme.alexandrite.sdk.chat.Quote
import org.foedusprogramme.alexandrite.sdk.chat.TurnKind
import org.foedusprogramme.alexandrite.sdk.hook.HookDecision
import org.foedusprogramme.alexandrite.sdk.permission.Clearance
import org.foedusprogramme.alexandrite.sdk.permission.ClearanceKind
import org.foedusprogramme.alexandrite.sdk.tool.CallReview
import org.foedusprogramme.alexandrite.sdk.tool.ReviewPreview
import org.foedusprogramme.alexandrite.sdk.tool.ReviewedTool
import org.foedusprogramme.alexandrite.sdk.tool.ToolContext
import org.foedusprogramme.alexandrite.sdk.tool.ToolDefinition
import org.foedusprogramme.alexandrite.sdk.tool.ToolResult
import org.foedusprogramme.alexandrite.sdk.tool.ToolRisk
import org.foedusprogramme.alexandrite.sdk.transcript.EndpointId
import org.foedusprogramme.alexandrite.sdk.transcript.NotRunReason
import org.foedusprogramme.alexandrite.sdk.transcript.TextPart
import org.foedusprogramme.alexandrite.sdk.transcript.ToolOutcome
import org.foedusprogramme.alexandrite.sdk.transcript.ToolResultEntry
import org.foedusprogramme.alexandrite.sdk.transcript.UserEntry
import org.foedusprogramme.alexandrite.sdk.turn.Admission
import org.foedusprogramme.alexandrite.sdk.turn.InitiatedTurn
import org.foedusprogramme.alexandrite.sdk.turn.TurnInitiation
import org.foedusprogramme.alexandrite.sdk.turn.TurnPoints
import org.foedusprogramme.alexandrite.testkit.MemoryStore
import org.foedusprogramme.alexandrite.testkit.PluginHarness
import org.foedusprogramme.alexandrite.testkit.ScriptedModel
import org.foedusprogramme.alexandrite.testkit.recordingTool
import org.foedusprogramme.alexandrite.testkit.testToolDefinition
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class JudgeTurnsTest {
    private val store = MemoryStore()
    private val model = ScriptedModel()
    private val judge = ScriptedModel(EndpointId("judge"))
    private val key = AgentChatKey.parse("coder@test:main:chat")
    private val cleared = CopyOnWriteArrayList<Clearance?>()
    private val write = recordingTool("fs.write", ToolRisk.WORKSPACE_WRITE) { _, context ->
        cleared += context.clearance
        ToolResult("Written.")
    }

    private fun harness(permission: String = "", configure: PluginHarness.Builder.() -> Unit = {}): PluginHarness =
        agentHarness(
            """
            {
              "previewIntervalMillis": 0,
              "agents": {
                "coder": {
                  "model": "scripted/test-model",
                  "channels": {"test:main": {}},
                  "tools": {"forMembers": ["fs.write"]},
                  "permission": {"mode": "auto", "judge": "judge/test-model"$permission}
                }
              }
            }
            """.trimIndent(),
            store,
        ) { channel(admins = setOf("admin")).model(model).model(judge).tool(write).apply(configure) }

    private fun PluginHarness.Running.ask(
        text: String = "Write the notes.",
        user: String = "admin",
        block: IncomingMessage.Builder.() -> Unit = {},
    ) = channel().receive(text, channel().chat(), channel().user(user), block) as Admission.Accepted

    private fun judgeAnswers(vararg verdicts: String) {
        for (verdict in verdicts) judge.reply { text(verdict) }
    }

    /** The text that the judge's model was asked last. */
    private fun asked(): String =
        ((judge.requests.last().history.single() as UserEntry).parts.single() as TextPart).text

    private suspend fun results(): List<ToolResultEntry> =
        store.transcripts.entries(store.conversations.current(key).id).filterIsInstance<ToolResultEntry>()

    private fun ToolResultEntry.text(): String = content.joinToString("") { (it as TextPart).text }

    @Test
    fun `the judge reads the call and the operator's own words, never a quote, a forward, a tool result or a reply`() {
        val read = recordingTool("fs.read") { _, _ -> ToolResult("Tool output.") }.hold()
        model.reply {
            text("Let me look first.")
            toolCall("fs.read")
        }.reply {
            text("Now I write it.")
            toolCall("fs.write", """{"path": "notes.md", "content": "hello"}""")
        }.reply { text("Written.") }
        judgeAnswers("""{"verdict":"allow","reason":"asked for"}""")

        harness { tool(read) }.execute {
            val turn = ask("Write hello into notes.md.") { quote(Quote.builder("Quoted words.").build()) }
            read.awaitCalls(1)
            ask("Use the root folder.")
            ask("Forwarded words.") { forwarded(ForwardOrigin(ForwardKind.USER, "Someone", null, null)) }
            read.release()
            turn.ticket.outcome()

            assertEquals(
                """
                What the operator typed in this turn, one block per message:
                <<<
                Write hello into notes.md.
                >>>
                <<<
                Use the root folder.
                >>>

                The conversation took in untrusted content: a quote that its channel does not vouch for

                The call:
                Tool: fs.write
                Risk: WORKSPACE_WRITE
                Arguments (canonical JSON):
                | {"content":"hello","path":"notes.md"}
                """.trimIndent(),
                asked(),
            )
            val turnRequest = model.requests.last().toString()
            for (unseen in listOf("Quoted words.", "Forwarded words.", "Tool output.", "Let me look first.")) {
                assertContains(turnRequest, unseen)
                assertEquals(false, unseen in judge.requests.single().toString(), unseen)
            }
            assertEquals(listOf(Clearance(ClearanceKind.JUDGE)), cleared.toList())
        }
    }

    @Test
    fun `the judge's allow runs the call cleared by it, its deny refuses it by policy and its ask asks an admin`() {
        repeat(3) { model.reply { toolCall("fs.write") }.reply { text("Done.") } }
        judgeAnswers(
            """{"verdict":"allow"}""",
            """{"verdict":"deny","reason":"It is unrelated."}""",
            """{"verdict":"ask","reason":"It is unclear."}""",
        )

        harness().execute {
            channel().scriptApprovals(ApprovalAnswer.Denied(channel().user("admin")))
            repeat(3) { ask().ticket.outcome() }

            val request = channel().approvals.single()
            assertEquals("the judge is unsure: It is unclear", request.reason)
            assertEquals(
                listOf(
                    ToolOutcome.Succeeded to "Written.",
                    ToolOutcome.NotRun(NotRunReason.DENIED_BY_POLICY) to "[alexandrite:denied by=policy] Not run: " +
                        "the judge refused it: It is unrelated. Do not retry this or an equivalent call; tell the " +
                        "user.",
                    ToolOutcome.NotRun(NotRunReason.DENIED, request.id) to Refusals.DENIED_BY_ADMIN,
                ),
                results().map { it.outcome to it.text() },
            )
            assertEquals(listOf(Clearance(ClearanceKind.JUDGE)), cleared.toList())
        }
    }

    @Test
    fun `a member's or an automation's call asks an admin without reaching the judge`() {
        repeat(2) { model.reply { toolCall("fs.write") }.reply { text("Asked.") } }

        harness().execute {
            val admin = channel().user("admin")
            channel().scriptApprovals(ApprovalAnswer.Denied(admin), ApprovalAnswer.Denied(admin))
            ask(user = "member").ticket.outcome()
            val reminder = InitiatedTurn.builder(key, TurnKind.REMINDER, "Write the notes.").build()
            (get<TurnInitiation>().initiate("beat", reminder) as Admission.Accepted).ticket.outcome()

            assertEquals(
                listOf("a member's call", "a call of a turn that nobody sent"),
                channel().approvals.map { it.reason },
            )
            assertEquals(emptyList(), judge.requests)
        }
    }

    @Test
    fun `the judge may allow a program that runs code the floor cannot see`() {
        val exec = object : ReviewedTool {
            override val definition: ToolDefinition = testToolDefinition("command.exec", ToolRisk.EXEC)

            override suspend fun review(arguments: JsonObject, context: ToolContext): CallReview = CallReview(
                preview = ReviewPreview.Command(
                    listOf("sh", "-c", "ls | wc -l"),
                    Path.of("/srv"),
                    Path.of("/bin/sh"),
                    opaque = true,
                ),
            )

            override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
                cleared += context.clearance
                return ToolResult("3")
            }
        }
        model.reply { toolCall("command.exec", """{"argv": ["sh", "-c", "ls | wc -l"]}""") }.reply { text("Three.") }
        judgeAnswers("""{"verdict":"allow"}""")

        harness { tool(exec) }.execute {
            ask("Count the files with sh -c 'ls | wc -l'.").ticket.outcome()

            assertContains(asked(), "Program: /bin/sh\nOpaque: yes\nCommand line:\n| sh -c 'ls | wc -l'")
            assertEquals(listOf(Clearance(ClearanceKind.JUDGE)), cleared.toList())
        }
    }

    @Test
    fun `the judge decides a call that a hook requires approval for`() {
        val read = recordingTool("fs.read") { _, context ->
            cleared += context.clearance
            ToolResult("Read.")
        }
        val careful =
            Interceptor(TurnPoints.TOOL_BEFORE) { HookDecision.Replace(it.withRequireApproval("Look first.")) }
        model.reply { toolCall("fs.read") }.reply { text("Read it.") }
        judgeAnswers("""{"verdict":"allow"}""")

        harness { tool(read).hook(careful) }.execute {
            ask("Read the notes.").ticket.outcome()

            assertContains(asked(), "Risk: READ_ONLY\nA hook requires approval: Look first.\n")
            assertEquals(listOf(Clearance(ClearanceKind.JUDGE)), cleared.toList())
        }
    }

    @Test
    fun `after three asks the judge is left out for the tool in the chat until an admin allows a call of it`() {
        repeat(5) { model.reply { toolCall("fs.write") }.reply { text("Done.") } }
        judgeAnswers(*Array(3) { """{"verdict":"ask"}""" }, """{"verdict":"allow"}""")

        harness().execute {
            val admin = channel().user("admin")
            val denied = ApprovalAnswer.Denied(admin)
            channel().scriptApprovals(denied, denied, denied, ApprovalAnswer.Allowed(admin))
            repeat(5) { ask().ticket.outcome() }

            assertEquals(
                List(3) { "the judge is unsure" } + "the judge was unsure about fs.write 3 times in a row in this chat",
                channel().approvals.map { it.reason },
            )
            assertEquals(4, judge.requests.size)
            assertEquals(
                listOf(Clearance(ClearanceKind.HUMAN, admin), Clearance(ClearanceKind.JUDGE)),
                cleared.toList(),
            )
        }
    }

    @Test
    fun `a restart forgets the asks in a row`() {
        repeat(5) { model.reply { toolCall("fs.write") }.reply { text("Done.") } }
        judgeAnswers(*Array(3) { """{"verdict":"ask"}""" }, """{"verdict":"allow"}""")

        harness().execute {
            channel().scriptApprovals(*Array(4) { ApprovalAnswer.Denied(channel().user("admin")) })
            repeat(4) { ask().ticket.outcome() }
        }
        harness().execute { ask().ticket.outcome() }

        assertEquals(4, judge.requests.size)
        assertEquals(listOf(Clearance(ClearanceKind.JUDGE)), cleared.toList())
    }

    @Test
    fun `a judge that does not answer in time leaves the call to an admin`() {
        model.reply { toolCall("fs.write") }.reply { text("Asked.") }
        judge.reply { hang() }

        harness(""", "judgeTimeoutSeconds": 1""").execute {
            channel().scriptApprovals(ApprovalAnswer.Allowed(channel().user("admin")))
            ask().ticket.outcome()

            assertEquals("the judge did not answer within 1 s", channel().approvals.single().reason)
            assertEquals(listOf(Clearance(ClearanceKind.HUMAN, channel().user("admin"))), cleared.toList())
        }
    }

    @Test
    fun `a call within the configured length goes to the judge, a longer one to an admin`() {
        val room = 60 - "fs.write".length - """{"content":"","path":"notes.md"}""".length
        for (length in listOf(room, room + 1)) {
            model.reply { toolCall("fs.write", """{"path": "notes.md", "content": "${"a".repeat(length)}"}""") }
                .reply { text("Done.") }
        }
        judgeAnswers("""{"verdict":"allow"}""")

        harness(""", "judgeMaxChars": 60""").execute {
            channel().scriptApprovals(ApprovalAnswer.Denied(channel().user("admin")))
            repeat(2) { ask().ticket.outcome() }

            assertEquals(
                listOf("the call is too long for the judge to see whole"),
                channel().approvals.map { it.reason },
            )
            assertEquals(1, judge.requests.size)
            assertEquals(listOf(Clearance(ClearanceKind.JUDGE)), cleared.toList())
        }
    }
}
