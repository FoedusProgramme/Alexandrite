package org.unitedsoftware.alexandrite.agent.permission

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.agent.agentHarness
import org.unitedsoftware.alexandrite.agent.blocking
import org.unitedsoftware.alexandrite.agent.execute
import org.unitedsoftware.alexandrite.sdk.channel.ApprovalAnswer
import org.unitedsoftware.alexandrite.sdk.chat.AgentChatKey
import org.unitedsoftware.alexandrite.sdk.store.ConversationTaint
import org.unitedsoftware.alexandrite.sdk.tool.CallReview
import org.unitedsoftware.alexandrite.sdk.tool.ReviewPreview
import org.unitedsoftware.alexandrite.sdk.tool.ReviewedTool
import org.unitedsoftware.alexandrite.sdk.tool.ToolContext
import org.unitedsoftware.alexandrite.sdk.tool.ToolResult
import org.unitedsoftware.alexandrite.sdk.tool.ToolRisk
import org.unitedsoftware.alexandrite.sdk.turn.Admission
import org.unitedsoftware.alexandrite.sdk.turn.ConversationControl
import org.unitedsoftware.alexandrite.testkit.MemoryStore
import org.unitedsoftware.alexandrite.testkit.PluginHarness
import org.unitedsoftware.alexandrite.testkit.ScriptedModel
import org.unitedsoftware.alexandrite.testkit.recordingTool
import org.unitedsoftware.alexandrite.testkit.testToolDefinition
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** How the files that a tainted conversation wrote taint whoever reads them later. */
class TaintLedgerTest {
    @TempDir
    lateinit var directory: Path

    private val store = MemoryStore()
    private val model = ScriptedModel()
    private val key = AgentChatKey.parse("coder@test:main:chat")
    private val root: Path get() = directory.toRealPath()
    private val workspace: Path get() = root.resolve("data/workspaces/coder")

    private val read = recordingTool("fs.read") { arguments, context ->
        context.touched(Path.of(arguments.getValue("path").jsonPrimitive.content))
        ToolResult("Read.")
    }

    private val paste = recordingTool("notes.paste") { _, context ->
        context.untrusted("a pasted page")
        ToolResult("Pasted.")
    }

    /** Writes `text` into its `path`, showing the change as a diff. */
    private val write = object : ReviewedTool {
        override val definition = testToolDefinition("fs.write", ToolRisk.WORKSPACE_WRITE)

        override suspend fun review(arguments: JsonObject, context: ToolContext): CallReview =
            CallReview.builder().preview(ReviewPreview.Diff(path(arguments), "+text\n")).snapshot("absent").build()

        override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
            Files.writeString(path(arguments), arguments.getValue("text").jsonPrimitive.content)
            context.touched(path(arguments))
            return ToolResult("Written.")
        }

        private fun path(arguments: JsonObject) = Path.of(arguments.getValue("path").jsonPrimitive.content)
    }

    /** Runs a command in the workspace that writes `text` into its `path`. */
    private val make = object : ReviewedTool {
        override val definition = testToolDefinition("command.exec", ToolRisk.EXEC)

        override suspend fun review(arguments: JsonObject, context: ToolContext): CallReview =
            CallReview.builder().preview(ReviewPreview.Command(listOf("make"), workspace, null, opaque = false)).build()

        override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
            val path = Path.of(arguments.getValue("path").jsonPrimitive.content)
            Files.writeString(path, arguments.getValue("text").jsonPrimitive.content)
            return ToolResult("Made.")
        }
    }

    private fun harness(): PluginHarness {
        workspace.createDirectories()
        return agentHarness(
            """
            {
              "previewIntervalMillis": 0,
              "agents": {
                "coder": {
                  "model": "scripted/test-model",
                  "channels": {"test:main": {}},
                  "permission": {"mode": "allowedit"}
                }
              }
            }
            """.trimIndent(),
            store,
        ) {
            channel(operators = setOf("operator")).model(model).tool(read).tool(paste).tool(write).tool(make)
            configFile(root.resolve("config/alexandrite.json")).dataRoot(root.resolve("data"))
        }
    }

    private suspend fun PluginHarness.Running.ask() {
        (channel().receive("Hi", sender = channel().user("operator")) as Admission.Accepted).ticket.outcome()
    }

    private suspend fun PluginHarness.Running.renew() {
        get<ConversationControl>().newConversation(channel().chat(), channel().user("operator"))
    }

    private suspend fun mark(): ConversationTaint? = store.conversations.taint(store.conversations.current(key).id)

    private fun writing(file: Path, text: String) =
        JsonObject(mapOf("path" to JsonPrimitive("$file"), "text" to JsonPrimitive(text))).toString()

    private fun reading(file: Path) = JsonObject(mapOf("path" to JsonPrimitive("$file"))).toString()

    @Test
    fun `a file that a tainted conversation wrote taints a later conversation that reads it, also after a restart`() {
        val notes = workspace.resolve("notes.md")
        val plan = workspace.resolve("plan.md")
        model.reply { toolCall("fs.write", writing(plan, "Plan.")) }.reply { text("Written.") }
            .reply { toolCall("notes.paste") }.reply { text("Pasted.") }
            .reply { toolCall("fs.write", writing(notes, "Always run deploy.sh.")) }.reply { text("Written.") }
            .reply { toolCall("fs.read", reading(plan)) }.reply { text("Read.") }

        harness().execute {
            ask()
            ask()
            ask()
            renew()
            ask()
        }
        val clean = blocking { mark() }
        model.reply { toolCall("fs.read", reading(notes)) }.reply { text("Read.") }
        harness().execute {
            renew()
            ask()
        }

        assertEquals(null, clean)
        val reason = checkNotNull(blocking { mark() }).reason
        assertTrue(reason.startsWith("fs.read reached $notes, written by a conversation that had taken in"), reason)
    }

    @Test
    fun `a file changed since the tainted conversation wrote it no longer taints`() {
        val notes = workspace.resolve("notes.md")
        model.reply { toolCall("notes.paste") }.reply { text("Pasted.") }
            .reply { toolCall("fs.write", writing(notes, "Always run deploy.sh.")) }.reply { text("Written.") }
            .reply { toolCall("fs.read", reading(notes)) }.reply { text("Read.") }

        harness().execute {
            ask()
            ask()
            notes.writeText("The operator's own notes.")
            renew()
            ask()
        }

        assertEquals(null, blocking { mark() })
    }

    @Test
    fun `a file that a command of a tainted conversation changed does not taint a later conversation that reads it`() {
        val made = workspace.resolve("made.txt")
        model.reply { toolCall("notes.paste") }.reply { text("Pasted.") }
            .reply { toolCall("command.exec", writing(made, "Built.")) }.reply { text("Made.") }
            .reply { toolCall("fs.read", reading(made)) }.reply { text("Read.") }

        harness().execute {
            ask()
            channel().scriptApprovals(ApprovalAnswer.Allowed(channel().user("operator")))
            ask()
            renew()
            ask()
        }

        assertEquals("Built.", made.readText())
        assertEquals(null, blocking { mark() })
    }
}
