package org.unitedsoftware.alexandrite.agent.turn

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.agent.agentHarness
import org.unitedsoftware.alexandrite.agent.execute
import org.unitedsoftware.alexandrite.sdk.channel.ApprovalAnswer
import org.unitedsoftware.alexandrite.sdk.channel.ToolCallStatus
import org.unitedsoftware.alexandrite.sdk.channel.TurnEvent
import org.unitedsoftware.alexandrite.sdk.channel.TurnEventKind
import org.unitedsoftware.alexandrite.sdk.channel.rebuild
import org.unitedsoftware.alexandrite.sdk.chat.ToolCallId
import org.unitedsoftware.alexandrite.sdk.runtime.HostPaths
import org.unitedsoftware.alexandrite.sdk.tool.CallReview
import org.unitedsoftware.alexandrite.sdk.tool.ReviewPreview
import org.unitedsoftware.alexandrite.sdk.tool.ReviewedTool
import org.unitedsoftware.alexandrite.sdk.tool.ToolActivity
import org.unitedsoftware.alexandrite.sdk.tool.ToolContext
import org.unitedsoftware.alexandrite.sdk.tool.ToolDefinition
import org.unitedsoftware.alexandrite.sdk.tool.ToolResult
import org.unitedsoftware.alexandrite.sdk.tool.ToolRisk
import org.unitedsoftware.alexandrite.sdk.turn.Admission
import org.unitedsoftware.alexandrite.testkit.MemoryStore
import org.unitedsoftware.alexandrite.testkit.PluginHarness
import org.unitedsoftware.alexandrite.testkit.RecordingChannel
import org.unitedsoftware.alexandrite.testkit.ScriptedModel
import org.unitedsoftware.alexandrite.testkit.recordingTool
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class TurnActivityTest {
    @TempDir
    lateinit var directory: Path

    private val store = MemoryStore()
    private val model = ScriptedModel()

    private val schema = JsonObject(mapOf("type" to JsonPrimitive("object")))

    private fun harness(configure: PluginHarness.Builder.() -> Unit = {}): PluginHarness = agentHarness(
        """
        {
          "previewIntervalMillis": 0,
          "shutdown": {"turnGraceSeconds": 0, "cancelJoinSeconds": 2},
          "agents": {
            "coder": {
              "model": "scripted/test-model",
              "channels": {"test:main": {}},
              "tools": {"forGuests": ["test.read", "test.edit", "test.exec"]}
            }
          }
        }
        """.trimIndent(),
        store,
    ) { channel(operators = setOf("operator")).model(model).apply(configure) }

    /** Lets the channel show every kind of turn event. */
    private fun PluginHarness.Running.enableEvents() {
        val capabilities = RecordingChannel.DEFAULT_CAPABILITIES.rebuild { events(TurnEventKind.entries.toSet()) }
        channel().scriptCapabilities(capabilities)
    }

    private fun PluginHarness.Running.ask(text: String = "Go.") =
        channel().receive(text, channel().chat(), channel().user("operator"))

    /** The tool-call events of [turn]'s reply, its usage updates left out. */
    private fun PluginHarness.Running.activityOf(turn: Admission.Accepted): List<TurnEvent> =
        channel().reply(turn.ticket.turn).activity.filterNot { it is TurnEvent.UsageUpdated }

    @Test
    fun `a read call shows as started, titled relative to the workspace, running and completed with no content`() {
        val id = ToolCallId("call-1")
        val definition = ToolDefinition(
            "test.read",
            "Reads.",
            schema,
            ToolRisk.READ_ONLY,
            activity = ToolActivity.READ,
            titleArguments = listOf("path"),
        )
        val read = recordingTool(definition) { _, _ -> ToolResult("the file's text") }

        harness { dataRoot(directory).tool(read) }.execute {
            val path = get<HostPaths>().dataRoot.resolve("workspaces/coder/notes.md")
            val arguments = JsonObject(mapOf("path" to JsonPrimitive("$path"))).toString()
            model.reply { toolCall("test.read", arguments, id) }.reply { text("Read it.") }
            enableEvents()
            val turn = ask()
            (turn as Admission.Accepted).ticket.outcome()

            assertEquals(
                listOf(
                    TurnEvent.ToolCallStarted(id, "test.read", ToolActivity.READ, "notes.md"),
                    TurnEvent.ToolCallUpdated(id, ToolCallStatus.RUNNING),
                    TurnEvent.ToolCallUpdated(id, ToolCallStatus.COMPLETED),
                ),
                activityOf(turn),
            )
        }
    }

    @Test
    fun `an edit an operator approves shows waiting for approval, running and its diff, a denied one not run`() {
        val id = ToolCallId("call-1")
        val path = Path.of("/srv/notes.md")
        val edit = object : ReviewedTool {
            override val definition: ToolDefinition = ToolDefinition(
                "test.edit",
                "Edits.",
                schema,
                ToolRisk.WORKSPACE_WRITE,
                activity = ToolActivity.EDIT,
                titleArguments = listOf("path"),
            )

            override suspend fun review(arguments: JsonObject, context: ToolContext): CallReview = CallReview.builder()
                .preview(ReviewPreview.Diff(path, "--- a\n+++ b\n@@ -1 +1 @@\n-old\n+new\n"))
                .snapshot("sha256:1")
                .build()

            override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult =
                ToolResult("Edited.")
        }
        model.reply { toolCall("test.edit", "{\"path\": \"notes.md\"}", id) }.reply { text("Edited it.") }
            .reply { toolCall("test.edit", "{\"path\": \"notes.md\"}", ToolCallId("call-2")) }
            .reply { text("Not edited.") }

        harness { tool(edit) }.execute {
            enableEvents()
            val operator = channel().user("operator")
            channel().scriptApprovals(ApprovalAnswer.Allowed(operator), ApprovalAnswer.Denied(operator))
            val turn = ask() as Admission.Accepted
            turn.ticket.outcome()
            val denied = ask() as Admission.Accepted
            denied.ticket.outcome()

            assertEquals(
                listOf(
                    TurnEvent.ToolCallStarted(id, "test.edit", ToolActivity.EDIT, "notes.md"),
                    TurnEvent.ToolCallUpdated(id, ToolCallStatus.WAITING_APPROVAL),
                    TurnEvent.ToolCallUpdated(id, ToolCallStatus.RUNNING),
                    TurnEvent.ToolCallUpdated(
                        id,
                        ToolCallStatus.COMPLETED,
                        TurnEvent.Content.Diff(path, "--- a\n+++ b\n@@ -1 +1 @@\n-old\n+new\n"),
                    ),
                ),
                activityOf(turn),
            )
            assertEquals(
                listOf(ToolCallStatus.WAITING_APPROVAL, ToolCallStatus.NOT_RUN),
                activityOf(denied).drop(1).map { (it as TurnEvent.ToolCallUpdated).status },
            )
        }
    }

    @Test
    fun `a third-party edit that claims the conversation-directory exemption waits for approval`() {
        val id = ToolCallId("call-1")
        val path = Path.of("/srv/conversation/notes.md")
        val edit = object : ReviewedTool {
            override val definition: ToolDefinition = ToolDefinition(
                "test.edit",
                "Edits.",
                schema,
                ToolRisk.WORKSPACE_WRITE,
                activity = ToolActivity.EDIT,
                titleArguments = listOf("path"),
            )

            override suspend fun review(arguments: JsonObject, context: ToolContext): CallReview = CallReview.builder()
                .preview(ReviewPreview.Diff(path, "--- a\n+++ b\n@@ -1 +1 @@\n-old\n+new\n"))
                .snapshot("sha256:1")
                .exempt(true)
                .build()

            override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult =
                ToolResult("Edited.")
        }
        model.reply { toolCall("test.edit", "{\"path\": \"notes.md\"}", id) }.reply { text("Edited it.") }

        harness { tool(edit) }.execute {
            enableEvents()
            val turn = ask() as Admission.Accepted
            turn.ticket.outcome()

            assertEquals(
                listOf(
                    TurnEvent.ToolCallStarted(id, "test.edit", ToolActivity.EDIT, "notes.md"),
                    TurnEvent.ToolCallUpdated(id, ToolCallStatus.WAITING_APPROVAL),
                    TurnEvent.ToolCallUpdated(id, ToolCallStatus.NOT_RUN),
                ),
                activityOf(turn),
            )
        }
    }

    @Test
    fun `a run shows completed with the tail of its output`() {
        val id = ToolCallId("call-1")
        val exec = recordingTool(
            ToolDefinition(
                "test.exec",
                "Runs.",
                schema,
                ToolRisk.READ_ONLY,
                activity = ToolActivity.EXECUTE,
                titleArguments = listOf("command"),
            ),
        ) { _, _ -> ToolResult("Exit code: 0\nhello") }
        model.reply { toolCall("test.exec", "{\"command\": \"echo hello\"}", id) }.reply { text("Ran it.") }

        harness { tool(exec) }.execute {
            enableEvents()
            val turn = ask() as Admission.Accepted
            turn.ticket.outcome()

            assertEquals(
                listOf(
                    TurnEvent.ToolCallStarted(id, "test.exec", ToolActivity.EXECUTE, "echo hello"),
                    TurnEvent.ToolCallUpdated(id, ToolCallStatus.RUNNING),
                    TurnEvent.ToolCallUpdated(
                        id,
                        ToolCallStatus.COMPLETED,
                        TurnEvent.Content.Output("Exit code: 0\nhello"),
                    ),
                ),
                activityOf(turn),
            )
        }
    }

    @Test
    fun `a channel whose capabilities ask for no events sees none`() {
        val id = ToolCallId("call-1")
        val read = recordingTool(
            ToolDefinition("test.read", "Reads.", schema, ToolRisk.READ_ONLY, activity = ToolActivity.READ),
        ) { _, _ -> ToolResult("text") }
        model.reply { toolCall("test.read", "{}", id) }.reply { text("Read it.") }

        harness { tool(read) }.execute {
            val turn = ask() as Admission.Accepted
            turn.ticket.outcome()

            assertEquals(emptyList(), channel().reply(turn.ticket.turn).activity)
        }
    }
}
