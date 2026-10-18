package org.unitedsoftware.alexandrite.agent.permission

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.agent.agentHarness
import org.unitedsoftware.alexandrite.agent.execute
import org.unitedsoftware.alexandrite.agent.tool.ToolRegistry
import org.unitedsoftware.alexandrite.sdk.chat.AgentChatKey
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
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
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

class PersonaEditTurnsTest {
    @TempDir
    lateinit var directory: Path

    private val store = MemoryStore()
    private val model = ScriptedModel()
    private val root: Path get() = directory.toRealPath()
    private val configFile: Path get() = root.resolve("config/alexandrite.json")
    private val persona: Path get() = configFile.resolveSibling("agents/coder/PERSONA.md")
    private val fetch = recordingTool("web.fetch", ToolRisk.NETWORK_READ) { _, context ->
        context.untrusted("a web page")
        ToolResult("Page.")
    }

    private fun harness(others: String = ""): PluginHarness {
        persona.parent.createDirectories()
        persona.writeText("I like tea.\n")
        Files.setLastModifiedTime(persona, FileTime.from(Instant.parse("2026-01-01T00:00:00Z")))
        val config = """
            {
              "previewIntervalMillis": 0,
              "agents": {
                "coder": {
                  "model": "scripted/test-model",
                  "channels": {"test:main": {}},
                  "tools": {"forMembers": ["persona.edit"]}
                }$others
              }
            }
        """.trimIndent()
        return agentHarness(config, store) {
            channel(admins = setOf("admin")).model(model).tool(fetch)
            configFile(configFile).dataRoot(root.resolve("data"))
        }
    }

    private suspend fun PluginHarness.Running.ask(user: String = "admin") {
        (channel().receive("Hi", sender = channel().user(user)) as Admission.Accepted).ticket.outcome()
    }

    private suspend fun results(): List<ToolResultEntry> {
        val conversation = store.conversations.current(AgentChatKey.parse("coder@test:main:chat")).id
        return store.transcripts.entries(conversation).filterIsInstance<ToolResultEntry>()
    }

    private fun edit(old: String, new: String): String = JsonObject(
        mapOf(
            "file" to JsonPrimitive("PERSONA.md"),
            "operation" to JsonPrimitive("replace"),
            "old" to JsonPrimitive(old),
            "new" to JsonPrimitive(new),
        ),
    ).toString()

    @Test
    fun `an operator's edit of the persona reaches the prompt of the next turn`() {
        model.reply { toolCall("persona.edit", edit("tea", "coffee")) }.reply { text("Noted.") }
            .reply { text("Coffee it is.") }

        harness().execute {
            ask()
            ask()

            assertEquals(listOf(ToolOutcome.Succeeded), results().map { it.outcome })
        }

        val instructions = model.requests.map { request -> request.instructions.map { it.text } }
        assertEquals(listOf(true, true, false), instructions.map { "I like tea.\n" in it })
        assertEquals(listOf(false, false, true), instructions.map { "I like coffee.\n" in it })
        assertEquals("I like coffee.\n", persona.readText())
    }

    @Test
    fun `every agent of a host with a config file is offered the tool, its persona directory made by its first edit`() {
        harness(""", "helper": {"description": "Helps."}""").execute {
            val tools = get<ToolRegistry>()

            val offered = listOf("coder", "helper").map { id ->
                "persona.edit" in
                    tools.offered(AgentId(id)).map { it.definition.name }
            }

            assertEquals(listOf(true, true), offered)
            assertEquals(false, Files.exists(persona.parent.resolveSibling("helper")))
        }
    }

    @Test
    fun `a member's edit and one in a tainted operator's turn wait for an administrator, whom none can reach yet`() {
        model.reply { toolCall("persona.edit", edit("tea", "coffee")) }.reply { text("Not edited.") }
            .reply { toolCall("web.fetch") }.reply { toolCall("persona.edit", edit("tea", "cake")) }
            .reply { text("Not edited either.") }

        harness().execute {
            ask(user = "member")
            ask()

            val refused = ToolOutcome.NotRun(NotRunReason.DENIED_BY_POLICY)
            assertEquals(listOf(refused, ToolOutcome.Succeeded, refused), results().map { it.outcome })
        }

        assertEquals("I like tea.\n", persona.readText())
    }
}
