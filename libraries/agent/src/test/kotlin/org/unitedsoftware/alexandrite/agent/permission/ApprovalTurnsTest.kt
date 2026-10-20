package org.unitedsoftware.alexandrite.agent.permission

import kotlinx.serialization.json.JsonObject
import org.unitedsoftware.alexandrite.agent.agentHarness
import org.unitedsoftware.alexandrite.agent.blocking
import org.unitedsoftware.alexandrite.agent.execute
import org.unitedsoftware.alexandrite.agent.logged
import org.unitedsoftware.alexandrite.sdk.channel.ApprovalAnswer
import org.unitedsoftware.alexandrite.sdk.channel.ApprovalRequest
import org.unitedsoftware.alexandrite.sdk.chat.AgentChatKey
import org.unitedsoftware.alexandrite.sdk.chat.ChatSettings
import org.unitedsoftware.alexandrite.sdk.chat.ChatSettingsUpdate
import org.unitedsoftware.alexandrite.sdk.permission.Clearance
import org.unitedsoftware.alexandrite.sdk.permission.ClearanceKind
import org.unitedsoftware.alexandrite.sdk.permission.PermissionMode
import org.unitedsoftware.alexandrite.sdk.store.TurnEndKind
import org.unitedsoftware.alexandrite.sdk.tool.CallReview
import org.unitedsoftware.alexandrite.sdk.tool.ReviewPreview
import org.unitedsoftware.alexandrite.sdk.tool.ReviewedTool
import org.unitedsoftware.alexandrite.sdk.tool.ToolContext
import org.unitedsoftware.alexandrite.sdk.tool.ToolDefinition
import org.unitedsoftware.alexandrite.sdk.tool.ToolResult
import org.unitedsoftware.alexandrite.sdk.tool.ToolRisk
import org.unitedsoftware.alexandrite.sdk.transcript.NotRunReason
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolOutcome
import org.unitedsoftware.alexandrite.sdk.transcript.ToolResultEntry
import org.unitedsoftware.alexandrite.sdk.transcript.TranscriptEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UserEntry
import org.unitedsoftware.alexandrite.sdk.turn.Admission
import org.unitedsoftware.alexandrite.sdk.turn.TurnOutcome
import org.unitedsoftware.alexandrite.testkit.MemoryStore
import org.unitedsoftware.alexandrite.testkit.PluginHarness
import org.unitedsoftware.alexandrite.testkit.RecordingChannel
import org.unitedsoftware.alexandrite.testkit.RequestMatch
import org.unitedsoftware.alexandrite.testkit.ScriptedModel
import org.unitedsoftware.alexandrite.testkit.TestRecords
import org.unitedsoftware.alexandrite.testkit.recordingTool
import org.unitedsoftware.alexandrite.testkit.testToolDefinition
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ApprovalTurnsTest {
    private val store = MemoryStore()
    private val model = ScriptedModel()
    private val key = AgentChatKey.parse("coder@test:main:chat")
    private val cleared = CopyOnWriteArrayList<Clearance?>()
    private val write = recordingTool("fs.write", ToolRisk.WORKSPACE_WRITE) { _, context ->
        cleared += context.clearance
        ToolResult("Written.")
    }

    private fun harness(settings: String = "", configure: PluginHarness.Builder.() -> Unit = {}): PluginHarness =
        agentHarness(
            """
            {
              "previewIntervalMillis": 0$settings,
              "shutdown": {"turnGraceSeconds": 0, "cancelJoinSeconds": 2},
              "agents": {
                "coder": {
                  "model": "scripted/test-model",
                  "channels": {"test:main": {}},
                  "tools": {"forGuests": ["fs.write", "command.exec"]}
                }
              }
            }
            """.trimIndent(),
            store,
        ) { channel(operators = setOf("operator")).model(model).tool(write).apply(configure) }

    private fun PluginHarness.Running.ask(
        text: String = "Write it.",
        user: String = "operator",
        chat: String = "chat",
    ) = channel().receive(text, channel().chat(chat), channel().user(user)) as Admission.Accepted

    private suspend fun entries(): List<TranscriptEntry> =
        store.transcripts.entries(store.conversations.current(key).id)

    private suspend fun results(): List<ToolResultEntry> = entries().filterIsInstance<ToolResultEntry>()

    private fun ToolResultEntry.text(): String = content.joinToString("") { (it as TextPart).text }

    private fun asked(text: String) = RequestMatch("asked \"$text\"") { request ->
        request.history.filterIsInstance<UserEntry>().any { entry ->
            entry.parts.any { (it as? TextPart)?.text?.contains(text) == true }
        }
    }

    // Answers.

    @Test
    fun `an operator's allow runs the call cleared by them, and a denial, an expiry or no approver refuse it`() {
        repeat(4) { model.reply { toolCall("fs.write") }.reply { text("Done.") } }

        harness().execute {
            val operator = channel().user("operator")
            channel().scriptApprovals(
                ApprovalAnswer.Allowed(operator),
                ApprovalAnswer.Denied(operator),
                ApprovalAnswer.Expired,
                ApprovalAnswer.Unavailable("no operator can be reached"),
            )
            repeat(4) { ask().ticket.outcome() }

            val ids = channel().approvals.map { it.id }
            assertEquals(
                listOf(
                    ToolOutcome.Succeeded to "Written.",
                    ToolOutcome.NotRun(NotRunReason.DENIED, ids[1]) to Refusals.DENIED_BY_OPERATOR,
                    ToolOutcome.NotRun(NotRunReason.EXPIRED, ids[2]) to Refusals.EXPIRED,
                    ToolOutcome.NotRun(NotRunReason.DENIED_BY_POLICY) to "[alexandrite:denied by=policy] Not run: no " +
                        "operator can be reached. Do not retry this or an equivalent call; tell the user.",
                ),
                results().map { it.outcome to it.text() },
            )
            assertEquals(listOf(Clearance(ClearanceKind.HUMAN, operator)), cleared.toList())
            assertEquals(4, ids.toSet().size)
        }
    }

    @Test
    fun `an answer of someone who is no operator refuses the call, with a warning`() {
        model.reply { toolCall("fs.write") }.reply { text("Not written.") }
        lateinit var request: ApprovalRequest

        val lines = logged {
            harness().execute {
                channel().scriptApprovals(ApprovalAnswer.Allowed(channel().user("guest")))
                ask().ticket.outcome()
                request = channel().approvals.single()

                assertEquals(
                    ToolOutcome.NotRun(NotRunReason.DENIED_BY_POLICY, request.id) to "[alexandrite:denied " +
                        "by=policy] Not run: no operator answered. Do not retry this or an equivalent call; tell the user.",
                    results().single().let { it.outcome to it.text() },
                )
                assertEquals(emptyList(), write.calls)
            }
        }

        val turn = request.turn.id
        assertEquals(
            listOf(
                "WARN Approval ${request.id} in turn $turn was answered by test:main@guest, who is no operator, so " +
                    "the call is refused",
            ),
            lines.filter { it.startsWith("WARN Approval") },
        )
    }

    @Test
    fun `why a person is asked is logged only at debug`() {
        model.reply { toolCall("fs.write") }.reply { text("Written.") }
        lateinit var request: ApprovalRequest

        val lines = logged {
            harness().execute {
                channel().scriptApprovals(ApprovalAnswer.Allowed(channel().user("operator")))
                ask().ticket.outcome()
                request = channel().approvals.single()
            }
        }

        val turn = request.turn
        assertEquals(
            listOf(
                "INFO Approval ${request.id} of call call-${turn.id}-0-0 of fs.write in turn ${turn.id} of " +
                    "${turn.key} is asked for",
                "DEBUG Approval ${request.id} is asked for because the chat is in ask mode",
            ),
            lines.filter { "is asked for" in it },
        )
    }

    @Test
    fun `the request shows the canonical arguments and the preview escaped, and why a person is asked`() {
        val exec = object : ReviewedTool {
            override val definition: ToolDefinition = testToolDefinition("command.exec", ToolRisk.EXEC)

            override suspend fun review(arguments: JsonObject, context: ToolContext): CallReview = CallReview.builder()
                .preview(ReviewPreview.Command(listOf("rm", "a\u202Eb"), Path.of("/srv/a\u200Bb"), null, true))
                .snapshot("sha256:1")
                .build()

            override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult = ToolResult("Ran.")
        }
        model.reply { toolCall("command.exec", "{\"z\": 1, \"a\": \"x\u2066y\"}") }.reply { text("Asked.") }
        repeat(2) { model.reply { toolCall("command.exec", "{}") }.reply { text("Asked again.") } }

        harness { tool(exec) }.execute {
            ask().ticket.outcome()
            get<ChatSettings>().update(
                channel().chat(),
                ChatSettingsUpdate.builder().mode(PermissionMode.MANUAL).build(),
                channel().user("operator"),
            )
            ask().ticket.outcome()
            ask(user = "guest").ticket.outcome()

            val (first, second, third) = channel().approvals
            assertEquals(
                ApprovalRequest(
                    first.id,
                    first.turn,
                    "command.exec",
                    ToolRisk.EXEC,
                    "{\"a\":\"x\\u2066y\",\"z\":1}",
                    ReviewPreview.Command(listOf("rm", "a\\u202Eb"), Path.of("/srv/a\\u200Bb"), null, true),
                    "the chat is in ask mode",
                ),
                first,
            )
            assertEquals(
                listOf("the chat is in manual mode", "a guest's call"),
                listOf(second, third).map { it.reason },
            )
            assertEquals(channel().user("guest"), third.turn.actor)
        }
    }

    @Test
    fun `a file to send is shown with its path and caption escaped, and its size, hash and destination as they are`() {
        val send = object : ReviewedTool {
            override val definition: ToolDefinition = testToolDefinition("files.send", ToolRisk.EXTERNAL_ACTION)

            override suspend fun review(arguments: JsonObject, context: ToolContext): CallReview = CallReview.builder()
                .preview(
                    ReviewPreview.OutgoingFile(
                        Path.of("/srv/a\u200Bb.pdf"),
                        12,
                        "ab".repeat(32),
                        context.turn.chat,
                        "See\u202Ethis\nfile",
                    ),
                )
                .snapshot("sha256:${"ab".repeat(32)}")
                .build()

            override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult = ToolResult("Sent.")
        }
        model.reply { toolCall("files.send", "{}") }.reply { text("Asked.") }

        harness { tool(send) }.execute {
            ask().ticket.outcome()

            assertEquals(
                ReviewPreview.OutgoingFile(
                    Path.of("/srv/a\\u200Bb.pdf"),
                    12,
                    "ab".repeat(32),
                    channel().chat(),
                    "See\\u202Ethis\nfile",
                ),
                channel().approvals.single().preview,
            )
        }
    }

    // Waiting.

    @Test
    fun `a turn that waits for an approval gives up its permit, so that another chat's turn runs meanwhile`() {
        model.reply(asked("Write it.")) { toolCall("fs.write") }
        model.reply(asked("Hello.")) { text("Hello to you.") }
        model.reply(asked("Write it.")) { text("Written.") }

        harness(""", "maxConcurrentTurns": 1""").execute {
            channel().holdApprovals()
            channel().scriptApprovals(ApprovalAnswer.Allowed(channel().user("operator")))
            val waiting = ask()
            channel().awaitApprovals(1)
            val other = ask("Hello.", chat = "other")

            assertIs<TurnOutcome.Completed>(other.ticket.outcome())
            channel().releaseApprovals()
            assertIs<TurnOutcome.Completed>(waiting.ticket.outcome())

            assertEquals("Hello to you.", channel().reply(other.ticket.turn).completed?.text)
            assertEquals("Written.", channel().reply(waiting.ticket.turn).completed?.text)
            assertEquals(1, write.calls.size)
        }
    }

    @Test
    fun `a follow-up of the turn's principal withdraws the request, declines the call and joins the turn`() {
        model.reply {
            toolCall("fs.write", "{\"n\": 1}")
            toolCall("fs.write", "{\"n\": 2}")
        }.reply { text("Not written, as you asked.") }

        harness().execute {
            channel().holdApprovals()
            val turn = ask()
            val request = channel().awaitApprovals(1).single()
            val followUp = ask("Stop, do not write.")

            assertEquals(TurnOutcome.Absorbed(turn.ticket.turn), followUp.ticket.outcome())
            assertIs<TurnOutcome.Completed>(turn.ticket.outcome())

            val stored = entries()
            assertEquals(
                listOf(
                    ToolOutcome.NotRun(NotRunReason.DECLINED, request.id) to Refusals.DECLINED,
                    ToolOutcome.NotRun(NotRunReason.DECLINED) to Refusals.DECLINED,
                ),
                stored.filterIsInstance<ToolResultEntry>().map { it.outcome to it.text() },
            )
            assertEquals(
                listOf(RecordingChannel.Event.ApprovalWithdrawn(request.id)),
                channel().events.filterIsInstance<RecordingChannel.Event.ApprovalWithdrawn>(),
            )
            assertEquals(listOf(request), channel().approvals)
            val followUpEntry = assertIs<UserEntry>(stored[stored.size - 2])
            assertEquals("Stop, do not write.", (followUpEntry.parts.last() as TextPart).text)
            assertEquals(stored.dropLast(1), model.requests.last().history)
            assertEquals(emptyList(), write.calls)
        }
    }

    @Test
    fun `a cancel during the wait withdraws the request and answers the call as cancelled before it ran`() {
        model.reply { toolCall("fs.write") }

        harness().execute {
            channel().holdApprovals()
            val turn = ask()
            val request = channel().awaitApprovals(1).single()

            assertTrue(turn.ticket.cancel())
            assertEquals(TurnOutcome.Cancelled, turn.ticket.outcome())

            assertEquals(
                ToolOutcome.NotRun(NotRunReason.CANCELLED, request.id) to Refusals.CANCELLED,
                results().single().let { it.outcome to it.text() },
            )
            channel().awaitEvent { it == RecordingChannel.Event.ApprovalWithdrawn(request.id) }
            assertEquals(TurnEndKind.CANCELLED, store.conversations.turn(turn.ticket.turn)?.end)
            assertEquals(emptyMap(), AwaitingCalls(TestRecords(store).of(AGENT)).all())
        }
    }

    @Test
    fun `a shutdown during the wait answers the call as interrupted`() {
        model.reply { toolCall("fs.write") }
        lateinit var turn: Admission.Accepted
        lateinit var request: ApprovalRequest

        harness().execute {
            channel().holdApprovals()
            turn = ask()
            request = channel().awaitApprovals(1).single()
            stop()
        }

        assertEquals(TurnOutcome.ShutDown(replayable = false), blocking { turn.ticket.outcome() })
        assertEquals(
            ToolOutcome.NotRun(NotRunReason.INTERRUPTED, request.id) to Refusals.INTERRUPTED,
            blocking { results() }.single().let { it.outcome to it.text() },
        )
        assertEquals(emptyMap(), blocking { AwaitingCalls(TestRecords(store).of(AGENT)).all() })
    }

    private companion object {
        const val AGENT = "alexandrite-agent"
    }
}
