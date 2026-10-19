package org.unitedsoftware.alexandrite.agent.profile

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.unitedsoftware.alexandrite.agent.agentHarness
import org.unitedsoftware.alexandrite.agent.execute
import org.unitedsoftware.alexandrite.sdk.chat.AgentChatKey
import org.unitedsoftware.alexandrite.sdk.tool.ToolResult
import org.unitedsoftware.alexandrite.sdk.tool.ToolRisk
import org.unitedsoftware.alexandrite.sdk.transcript.NotRunReason
import org.unitedsoftware.alexandrite.sdk.transcript.ToolOutcome
import org.unitedsoftware.alexandrite.sdk.transcript.ToolResultEntry
import org.unitedsoftware.alexandrite.sdk.turn.Admission
import org.unitedsoftware.alexandrite.testkit.MemoryStore
import org.unitedsoftware.alexandrite.testkit.PluginHarness
import org.unitedsoftware.alexandrite.testkit.ScriptedModel
import org.unitedsoftware.alexandrite.testkit.recordingTool
import kotlin.test.Test
import kotlin.test.assertEquals

/** How `profile.create`'s risk, PERSISTENT_STATE, is gated by who calls it and whether the conversation is tainted. */
class ProfileTurnsTest {
    private val store = MemoryStore()
    private val model = ScriptedModel()
    private val fetch = recordingTool("web.fetch", ToolRisk.NETWORK_READ) { _, context ->
        context.untrusted("a web page")
        ToolResult("Page.")
    }

    private fun harness(): PluginHarness {
        val config = """
            {
              "previewIntervalMillis": 0,
              "agents": {
                "coder": {
                  "model": "scripted/test-model",
                  "channels": {"test:main": {}},
                  "tools": {"forGuests": ["profile.create"]}
                }
              }
            }
        """.trimIndent()
        return agentHarness(config, store) { channel(operators = setOf("operator")).model(model).tool(fetch) }
    }

    private suspend fun PluginHarness.Running.ask(user: String = "operator") {
        (channel().receive("Hi", sender = channel().user(user)) as Admission.Accepted).ticket.outcome()
    }

    private suspend fun results(): List<ToolResultEntry> {
        val conversation = store.conversations.current(AgentChatKey.parse("coder@test:main:chat")).id
        return store.transcripts.entries(conversation).filterIsInstance<ToolResultEntry>()
    }

    private fun create(id: String): String = JsonObject(
        mapOf(
            "id" to JsonPrimitive(id),
            "name" to JsonPrimitive("Reviewer"),
            "description" to JsonPrimitive("Reviews code."),
            "instructions" to JsonPrimitive("Be strict."),
        ),
    ).toString()

    @Test
    fun `a clean operator's turn creates the profile without asking anyone`() {
        model.reply { toolCall("profile.create", create("reviewer")) }.reply { text("Done.") }

        harness().execute {
            ask()

            assertEquals(listOf(ToolOutcome.Succeeded), results().map { it.outcome })
        }
    }

    @Test
    fun `a guest's call, and one in a tainted operator's turn, wait for an operator none can reach yet`() {
        model.reply { toolCall("profile.create", create("reviewer")) }.reply { text("Not created.") }
            .reply { toolCall("web.fetch") }.reply { toolCall("profile.create", create("writer")) }
            .reply { text("Not created either.") }

        harness().execute {
            ask(user = "guest")
            ask()

            val refused = ToolOutcome.NotRun(NotRunReason.DENIED_BY_POLICY)
            assertEquals(listOf(refused, ToolOutcome.Succeeded, refused), results().map { it.outcome })
        }
    }
}
