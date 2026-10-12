package org.foedusprogramme.alexandrite.agent.turn

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.foedusprogramme.alexandrite.agent.agentHarness
import org.foedusprogramme.alexandrite.agent.execute
import org.foedusprogramme.alexandrite.sdk.channel.ApprovalAnswer
import org.foedusprogramme.alexandrite.sdk.channel.ToolCallStatus
import org.foedusprogramme.alexandrite.sdk.channel.TurnEvent
import org.foedusprogramme.alexandrite.sdk.channel.TurnEventKind
import org.foedusprogramme.alexandrite.sdk.channel.rebuild
import org.foedusprogramme.alexandrite.sdk.chat.ToolCallId
import org.foedusprogramme.alexandrite.sdk.runtime.HostPaths
import org.foedusprogramme.alexandrite.sdk.tool.CallReview
import org.foedusprogramme.alexandrite.sdk.tool.ReviewPreview
import org.foedusprogramme.alexandrite.sdk.tool.ReviewedTool
import org.foedusprogramme.alexandrite.sdk.tool.ToolActivity
import org.foedusprogramme.alexandrite.sdk.tool.ToolContext
import org.foedusprogramme.alexandrite.sdk.tool.ToolDefinition
import org.foedusprogramme.alexandrite.sdk.tool.ToolResult
import org.foedusprogramme.alexandrite.sdk.tool.ToolRisk
import org.foedusprogramme.alexandrite.sdk.turn.Admission
import org.foedusprogramme.alexandrite.testkit.MemoryStore
import org.foedusprogramme.alexandrite.testkit.PluginHarness
import org.foedusprogramme.alexandrite.testkit.RecordingChannel
import org.foedusprogramme.alexandrite.testkit.ScriptedModel
import org.foedusprogramme.alexandrite.testkit.recordingTool
import org.junit.jupiter.api.io.TempDir
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
              "tools": {"forMembers": ["test.read", "test.edit", "test.exec"]}
            }
          }
        }
        """.trimIndent(),
        store,
    ) { channel(admins = setOf("admin")).model(model).apply(configure) }

    /** Lets the channel show every kind of turn event. */
    private fun PluginHarness.Running.enableEvents() {
        val capabilities = RecordingChannel.DEFAULT_CAPABILITIES.rebuild { events(TurnEventKind.entries.toSet()) }
        channel().scriptCapabilities(capabilities)
    }

    private fun PluginHarness.Running.ask(text: String = "Go.") =
        channel().receive(text, channel().chat(), channel().user("admin"))

    /** The tool-call events of [turn]'s reply, its usage updates left out. */
    private fun PluginHarness.Running.activityOf(turn: Admission.Accepted): List<TurnEvent> =
        channel().reply(turn.ticket.turn).activity.filterNot { it is TurnEvent.UsageUpdated }

    @Test
    fun `a read call shows as started, running and completed with no content`() {
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
        model.reply { toolCall("test.read", "{\"path\": \"notes.md\"}", id) }.reply { text("Read it.") }

        harness { tool(read) }.execute {
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
    fun `a call's title shows a path under the agent's workspace relative to it, for display only`() {
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
            val workspace = get<HostPaths>().dataRoot.resolve("workspaces/coder")
            val path = workspace.resolve("notes.md")
            val arguments = JsonObject(mapOf("path" to JsonPrimitive("$path"))).toString()
            model.reply { toolCall("test.read", arguments, id) }.reply { text("Read it.") }
            enableEvents()
            val turn = ask()
            (turn as Admission.Accepted).ticket.outcome()

            assertEquals(
                TurnEvent.ToolCallStarted(id, "test.read", ToolActivity.READ, "notes.md"),
                activityOf(turn).first(),
            )
        }
    }

    @Test
    fun `an edit an admin approves shows waiting for approval, then running, then completed with its diff`() {
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

            override suspend fun review(arguments: JsonObject, context: ToolContext): CallReview = CallReview(
                preview = ReviewPreview.Diff(path, "--- a\n+++ b\n@@ -1 +1 @@\n-old\n+new\n"),
                snapshot = "sha256:1",
            )

            override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult =
                ToolResult("Edited.")
        }
        model.reply { toolCall("test.edit", "{\"path\": \"notes.md\"}", id) }.reply { text("Edited it.") }

        harness { tool(edit) }.execute {
            enableEvents()
            channel().scriptApprovals(ApprovalAnswer.Allowed(channel().user("admin")))
            val turn = ask() as Admission.Accepted
            turn.ticket.outcome()

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
        }
    }

    @Test
    fun `a call an admin denies shows waiting for approval, then not run`() {
        val id = ToolCallId("call-1")
        val edit = recordingTool(
            ToolDefinition("test.edit", "Edits.", schema, ToolRisk.WORKSPACE_WRITE, activity = ToolActivity.EDIT),
        ) { _, _ -> ToolResult("Edited.") }
        model.reply { toolCall("test.edit", "{}", id) }.reply { text("Not edited.") }

        harness { tool(edit) }.execute {
            enableEvents()
            channel().scriptApprovals(ApprovalAnswer.Denied(channel().user("admin")))
            val turn = ask() as Admission.Accepted
            turn.ticket.outcome()

            assertEquals(
                listOf(
                    TurnEvent.ToolCallStarted(id, "test.edit", ToolActivity.EDIT, "test.edit"),
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
