package org.unitedsoftware.alexandrite.agent.permission

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
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
import org.unitedsoftware.alexandrite.sdk.tool.RuleSubject
import org.unitedsoftware.alexandrite.sdk.tool.ToolContext
import org.unitedsoftware.alexandrite.sdk.tool.ToolDefinition
import org.unitedsoftware.alexandrite.sdk.tool.ToolResult
import org.unitedsoftware.alexandrite.sdk.tool.ToolRisk
import org.unitedsoftware.alexandrite.sdk.turn.Admission
import org.unitedsoftware.alexandrite.sdk.turn.AgentControl
import org.unitedsoftware.alexandrite.sdk.turn.ControlRefusedException
import org.unitedsoftware.alexandrite.sdk.turn.TurnOutcome
import org.unitedsoftware.alexandrite.testkit.MemoryStore
import org.unitedsoftware.alexandrite.testkit.PluginHarness
import org.unitedsoftware.alexandrite.testkit.ScriptedModel
import org.unitedsoftware.alexandrite.testkit.recordingTool
import org.unitedsoftware.alexandrite.testkit.testToolDefinition
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class TrustTurnsTest {
    @TempDir
    lateinit var directory: Path

    private var store = MemoryStore()
    private var model = ScriptedModel()
    private val key = AgentChatKey.parse("coder@test:main:chat")
    private val outside: Path get() = directory.toRealPath().resolve("outside").createDirectories()

    /** command.exec as its review shows it: each program untrusted, reaching [outside] when it runs. */
    private val exec = object : ReviewedTool {
        override val definition: ToolDefinition = testToolDefinition("command.exec", ToolRisk.EXEC)

        override suspend fun review(arguments: JsonObject, context: ToolContext): CallReview {
            val argv = arguments.getValue("argv").jsonArray.map { it.jsonPrimitive.content }
            return CallReview(
                preview = ReviewPreview.Command(argv, outside, null, opaque = false),
                rule = RuleSubject("command.exec", argv.take(2), argv.take(2).joinToString(" ")),
                untrusted = true,
            )
        }

        override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
            context.touched(outside)
            context.untrusted("command.exec ran ${arguments.getValue("argv").jsonArray.first().jsonPrimitive.content}")
            return ToolResult("Ran.")
        }
    }

    private fun server(name: String) = recordingTool("mcp.$name.read") { _, context ->
        context.untrusted("output of MCP server $name")
        ToolResult("Read.")
    }

    private val paste = recordingTool("notes.paste") { _, context ->
        context.untrusted("a pasted page")
        ToolResult("Pasted.")
    }

    private fun harness(trust: List<String> = emptyList()): PluginHarness {
        val rules = trust.joinToString(", ") { "\"$it\"" }
        val config = """
            {
              "previewIntervalMillis": 0,
              "agents": {
                "coder": {
                  "model": "scripted/test-model",
                  "channels": {"test:main": {}},
                  "permission": {"trust": [$rules]}
                }
              }
            }
        """.trimIndent()
        return agentHarness(config, store) {
            channel(operators = setOf("operator")).model(model).tool(exec).tool(server("files")).tool(server("web"))
                .tool(paste)
        }
    }

    private fun running(vararg argv: String): String =
        JsonObject(mapOf("argv" to JsonArray(argv.map(::JsonPrimitive)))).toString()

    private suspend fun PluginHarness.Running.ask(): TurnOutcome =
        (channel().receive("Go on.", sender = channel().user("operator")) as Admission.Accepted).ticket.outcome()

    private suspend fun mark(): ConversationTaint? = store.conversations.taint(store.conversations.current(key).id)

    /** What the call that tainted the conversation reported, null when it is not tainted. */
    private suspend fun reported(): String? = mark()?.reason?.substringAfter("reported untrusted content: ")

    private fun fresh() {
        store = MemoryStore()
        model = ScriptedModel()
    }

    // Trust rules.

    @Test
    fun `a declared trust rule keeps a matching exec call and a matching MCP call from tainting, others still taint`() {
        model.reply { toolCall("command.exec", running("git", "log")) }
            .reply { toolCall("mcp.files.read") }
            .reply { text("Done.") }
        val trust = listOf("command.exec(git)", "mcp.files.*")

        harness(trust).execute {
            channel().scriptApprovals(ApprovalAnswer.Allowed(channel().user("operator")))
            ask()
        }
        val trusted = blocking { mark() }
        fresh()
        model.reply { toolCall("mcp.web.read") }.reply { text("Done.") }
        harness(trust).execute { ask() }
        val web = blocking { reported() }
        fresh()
        model.reply { toolCall("command.exec", running("make")) }.reply { text("Done.") }
        harness(trust).execute {
            channel().scriptApprovals(ApprovalAnswer.Allowed(channel().user("operator")))
            ask()
        }
        val make = blocking { reported() }

        assertNull(trusted)
        assertEquals("output of MCP server web", web)
        assertEquals("command.exec ran make", make)
    }

    @Test
    fun `a trust rule granted at runtime keeps matching calls from tainting until the conversation ends`() {
        model.reply { toolCall("command.exec", running("git", "status")) }.reply { text("Done.") }
            .reply { toolCall("command.exec", running("git", "status")) }.reply { text("Done again.") }

        harness().execute {
            val control = get<AgentControl>()
            val operator = channel().user("operator")
            val chat = channel().chat()
            val trusted = control.trust(chat, "command.exec(git)", operator)
            assertEquals(listOf("command.exec(git)"), control.trustRules(chat).map { it.rule })
            assertEquals(false to operator, trusted.declared to trusted.grantedBy)
            assertFailsWith<ControlRefusedException> { control.trust(chat, "git", operator) }
            assertFailsWith<ControlRefusedException> { control.trust(chat, "command.exec(a && b)", operator) }
            channel().scriptApprovals(ApprovalAnswer.Allowed(operator), ApprovalAnswer.Allowed(operator))
            ask()
            assertNull(mark())
            control.newConversation(chat, operator)
            assertEquals(emptyList(), control.trustRules(chat))
            ask()
            assertEquals("command.exec ran git", reported())
        }
    }

    @Test
    fun `trust never clears a taint the conversation has`() {
        model.reply { toolCall("notes.paste") }
            .reply { toolCall("command.exec", running("git", "log")) }
            .reply { text("Done.") }

        harness(listOf("command.exec(git)")).execute {
            channel().scriptApprovals(ApprovalAnswer.Allowed(channel().user("operator"), trust = true))
            ask()
        }

        assertEquals("a pasted page", blocking { reported() })
    }

    // Allow and trust.

    @Test
    fun `an approval answered allow and trust runs the call and leaves the conversation untainted`() {
        model.reply { toolCall("command.exec", running("make")) }.reply { text("Made.") }
        model.reply { toolCall("command.exec", running("make")) }.reply { text("Made again.") }
        model.reply { toolCall("command.exec", running("make")) }.reply { text("Made a third time.") }

        harness().execute {
            val operator = channel().user("operator")
            channel().scriptApprovals(
                ApprovalAnswer.Allowed(operator, trust = true),
                ApprovalAnswer.Allowed(operator),
                ApprovalAnswer.Allowed(operator, trust = true),
            )
            ask()
            val trusted = mark()
            ask()
            val allowed = reported()

            assertNull(trusted)
            assertEquals("command.exec ran make", allowed)
            ask()
            assertEquals(listOf(true, true, false), channel().approvals.map { it.taints })
        }
    }
}
