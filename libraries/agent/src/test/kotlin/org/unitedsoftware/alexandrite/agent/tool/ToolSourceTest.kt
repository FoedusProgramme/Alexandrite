package org.unitedsoftware.alexandrite.agent.tool

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.unitedsoftware.alexandrite.agent.agentHarness
import org.unitedsoftware.alexandrite.agent.execute
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.tool.Tool
import org.unitedsoftware.alexandrite.sdk.tool.ToolResult
import org.unitedsoftware.alexandrite.sdk.tool.ToolSource
import org.unitedsoftware.alexandrite.sdk.transcript.ContextPart
import org.unitedsoftware.alexandrite.sdk.transcript.UserEntry
import org.unitedsoftware.alexandrite.sdk.turn.Admission
import org.unitedsoftware.alexandrite.sdk.turn.TurnOutcome
import org.unitedsoftware.alexandrite.testkit.MemoryStore
import org.unitedsoftware.alexandrite.testkit.PluginHarness
import org.unitedsoftware.alexandrite.testkit.ScriptedModel
import org.unitedsoftware.alexandrite.testkit.recordingTool
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** A plugin's tools that come and go while the agent runs. */
class ToolSourceTest {
    private val store = MemoryStore()
    private val model = ScriptedModel()
    private val ping = recordingTool("remote.ping") { _, _ -> ToolResult("pong") }
    private val pong = recordingTool("remote.pong")
    private val secret = recordingTool("remote.secret")
    private val read = recordingTool("fs.read")

    private val source = object : ToolSource {
        override val namespace = "remote"
        val current = MutableStateFlow<List<Tool>>(listOf(ping))
        override val tools: StateFlow<List<Tool>> = current
    }

    private fun harness(): PluginHarness = agentHarness(
        """
        {
          "previewIntervalMillis": 0,
          "agents": {
            "coder": {
              "model": "scripted/test-model",
              "channels": {"test:main": {}},
              "tools": {"allow": ["*"], "deny": ["remote.secret"]}
            }
          }
        }
        """.trimIndent(),
        store,
    ) { channel(operators = setOf("operator")).model(model).tool(read).toolSource(source) }

    private suspend fun PluginHarness.Running.ask() {
        val admission = channel().receive("Go.", sender = channel().user("operator"))
        assertEquals(TurnOutcome.Completed::class, (admission as Admission.Accepted).ticket.outcome()::class)
    }

    private fun offeredRemote(request: Int): List<String> =
        model.requests[request].tools.map { it.name }.filter { it.startsWith("remote") }

    @Test
    fun `a source's tools are offered and called, and a turn after a change is told what changed`() {
        model.reply { toolCall("remote.ping", "{}") }.reply { text("Pinged.") }.reply { text("Seen.") }

        harness().execute {
            ask()
            source.current.value = listOf(pong, secret)
            ask()

            assertEquals(1, ping.calls.size)
            assertEquals(listOf("remote.ping"), offeredRemote(0))
            assertEquals(listOf("remote.pong"), offeredRemote(2))
            val notice = model.requests[2].history.filterIsInstance<UserEntry>().flatMap { it.parts }
                .filterIsInstance<ContextPart>().single { it.text.contains("[alexandrite:tools]") }
            assertContains(notice.text, "remote.pong")
            assertContains(notice.text, "remote.ping")
        }
    }

    @Test
    fun `a source's tool outside its namespace or of a contributed tool's name is not offered`() {
        val stray = recordingTool("other.tool")
        val clash = recordingTool("fs.read")

        harness().execute {
            source.current.value = listOf(ping, stray, clash)
            val registry = get<ToolRegistry>()

            val offered = registry.offered(AgentId("coder")).map { it.definition.name }
            assertEquals(true, "remote.ping" in offered && "other.tool" !in offered)
            assertEquals(read, registry.tool("fs.read"))
            assertEquals(ping, registry.tool("remote.ping"))
            assertNull(registry.tool("other.tool"))
            assertFalse(registry.isBuiltIn(ping))
            assertEquals(registry.pluginOf(read), registry.pluginOf(ping))
            assertEquals(true, registry.isSourced("remote.anything"))
        }
    }
}
