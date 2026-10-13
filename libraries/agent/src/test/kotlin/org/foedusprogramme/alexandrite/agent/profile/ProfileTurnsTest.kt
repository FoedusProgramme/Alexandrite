package org.foedusprogramme.alexandrite.agent.profile

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.foedusprogramme.alexandrite.agent.agentHarness
import org.foedusprogramme.alexandrite.agent.execute
import org.foedusprogramme.alexandrite.sdk.chat.AgentChatKey
import org.foedusprogramme.alexandrite.sdk.tool.ToolResult
import org.foedusprogramme.alexandrite.sdk.tool.ToolRisk
import org.foedusprogramme.alexandrite.sdk.transcript.NotRunReason
import org.foedusprogramme.alexandrite.sdk.transcript.ToolOutcome
import org.foedusprogramme.alexandrite.sdk.transcript.ToolResultEntry
import org.foedusprogramme.alexandrite.sdk.turn.Admission
import org.foedusprogramme.alexandrite.testkit.MemoryStore
import org.foedusprogramme.alexandrite.testkit.PluginHarness
import org.foedusprogramme.alexandrite.testkit.ScriptedModel
import org.foedusprogramme.alexandrite.testkit.recordingTool
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
                  "tools": {"forMembers": ["profile.create"]}
                }
              }
            }
        """.trimIndent()
        return agentHarness(config, store) { channel(admins = setOf("admin")).model(model).tool(fetch) }
    }

    private suspend fun PluginHarness.Running.ask(user: String = "admin") {
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
    fun `a member's call, and one in a tainted operator's turn, wait for an administrator none can reach yet`() {
        model.reply { toolCall("profile.create", create("reviewer")) }.reply { text("Not created.") }
            .reply { toolCall("web.fetch") }.reply { toolCall("profile.create", create("writer")) }
            .reply { text("Not created either.") }

        harness().execute {
            ask(user = "member")
            ask()

            val refused = ToolOutcome.NotRun(NotRunReason.DENIED_BY_POLICY)
            assertEquals(listOf(refused, ToolOutcome.Succeeded, refused), results().map { it.outcome })
        }
    }
}
