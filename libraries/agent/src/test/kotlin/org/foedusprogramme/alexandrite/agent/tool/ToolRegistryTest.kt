package org.foedusprogramme.alexandrite.agent.tool

import org.foedusprogramme.alexandrite.agent.agentHarness
import org.foedusprogramme.alexandrite.agent.assertStartFails
import org.foedusprogramme.alexandrite.agent.execute
import org.foedusprogramme.alexandrite.runtime.StartStage
import org.foedusprogramme.alexandrite.sdk.chat.AgentId
import org.foedusprogramme.alexandrite.sdk.tool.ToolRisk
import org.foedusprogramme.alexandrite.sdk.transcript.ModelRef
import org.foedusprogramme.alexandrite.testkit.RecordingTool
import org.foedusprogramme.alexandrite.testkit.recordingTool
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ToolRegistryTest {
    private val tools = listOf(
        recordingTool("fs.read"),
        recordingTool("fs.glob"),
        recordingTool("fs.secret"),
        recordingTool("fs.write", ToolRisk.WORKSPACE_WRITE),
        recordingTool("notes.find"),
        recordingTool("notes.add", ToolRisk.AGENT_STATE),
        recordingTool("exec.run", ToolRisk.EXEC),
    )

    private val config = """
        {
          "agents": {
            "coder": {
              "description": "Codes.",
              "tools": {"allow": ["fs.*", "notes.find"], "deny": ["fs.secret"], "forMembers": ["fs.g*", "notes.*"]}
            },
            "helper": {"description": "Helps."},
            "reviewer": {"description": "Reviews.", "tools": {"allow": []}}
          }
        }
    """.trimIndent()

    @Test
    fun `an agent is offered the tools its globs allow and do not deny, whatever their risk, sorted by name`() {
        agentHarness(config) { tools.forEach(::tool) }.execute {
            val registry = get<ToolRegistry>()
            fun offered(agent: String) = registry.offered(AgentId(agent)).map { it.definition.name }

            assertEquals(listOf("fs.glob", "fs.read", "fs.write", "notes.find"), offered("coder"))
            assertEquals(
                listOf(
                    "agent.delegate",
                    "agent.jobs",
                    "agent.stop",
                    "exec.run",
                    "fs.glob",
                    "fs.read",
                    "fs.secret",
                    "fs.write",
                    "notes.add",
                    "notes.find",
                    "permission.status",
                    "profile.create",
                    "profile.edit",
                    "profile.list",
                    "profile.remove",
                ),
                offered("helper"),
            )
            assertEquals(emptyList(), offered("reviewer"))
            assertEquals(emptyList(), offered("other"))
            assertSame(tools.first(), registry.tool("fs.read"))
            assertSame(tools.last(), registry.tool("exec.run"))
            assertNull(registry.tool("fs.missing"))
        }
    }

    @Test
    fun `turns offer the agent's tools to a model that takes tools, and members may call those listed for them`() {
        agentHarness(config) { tools.forEach(::tool) }.execute {
            val registry = get<ToolRegistry>()
            val model = ModelRef.parse("scripted/test-model")
            val coder = AgentId("coder")
            fun offered(nativeTools: Boolean) = registry.offered(coder, model, nativeTools).map { it.definition.name }

            assertEquals(listOf("fs.glob", "fs.read", "fs.write", "notes.find"), offered(nativeTools = true))
            assertEquals(emptyList(), offered(nativeTools = false))
            assertEquals(
                listOf("fs.glob", "notes.find"),
                offered(nativeTools = true).filter { registry.permits(coder, admin = false, it) },
            )
            assertTrue(offered(nativeTools = true).all { registry.permits(coder, admin = true, it) })
        }
    }

    @Test
    fun `an agent is offered permission status and the job tools only beside other tools`() {
        val config = """
            {
              "agents": {
                "coder": {"description": "Codes.", "tools": {"allow": ["permission.*", "agent.*"]}},
                "helper": {"description": "Helps.", "tools": {"allow": ["permission.*", "agent.*", "fs.read"]}}
              }
            }
        """.trimIndent()

        agentHarness(config) { tools.forEach(::tool) }.execute {
            val registry = get<ToolRegistry>()
            fun offered(agent: String) = registry.offered(AgentId(agent)).map { it.definition.name }

            assertEquals(emptyList(), offered("coder"))
            assertEquals(
                listOf("agent.delegate", "agent.jobs", "agent.stop", "fs.read", "permission.status"),
                offered("helper"),
            )
        }
    }

    @Test
    fun `only the tools of built-in plugins count as built in`() {
        agentHarness(config) { tools.forEach(::tool) }.execute {
            val registry = get<ToolRegistry>()

            assertEquals(emptyList(), tools.filter(registry::isBuiltIn))
            assertFalse(registry.isBuiltIn(recordingTool("fs.read")))
        }
    }

    @Test
    fun `two tools with one name fail the start`() {
        agentHarness { tool(recordingTool("fs.read")).tool(recordingTool("fs.read", ToolRisk.EXEC)) }.assertStartFails(
            StartStage.GRAPH,
            "Tool 'fs.read' is contributed by both ${RecordingTool::class.java.name} and " +
                "${RecordingTool::class.java.name}: switch off the plugin of one of them.",
        )
    }
}
