package org.foedusprogramme.alexandrite.agent.tool

import org.foedusprogramme.alexandrite.agent.agentHarness
import org.foedusprogramme.alexandrite.agent.assertStartFails
import org.foedusprogramme.alexandrite.agent.execute
import org.foedusprogramme.alexandrite.agent.logged
import org.foedusprogramme.alexandrite.runtime.StartStage
import org.foedusprogramme.alexandrite.sdk.chat.AgentId
import org.foedusprogramme.alexandrite.sdk.tool.ToolRisk
import org.foedusprogramme.alexandrite.testkit.RecordingTool
import org.foedusprogramme.alexandrite.testkit.recordingTool
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

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
            "coder": {"tools": {"allow": ["fs.*", "notes.find"], "deny": ["fs.secret"]}},
            "helper": {},
            "reviewer": {"tools": {"allow": []}}
          }
        }
    """.trimIndent()

    @Test
    fun `an agent is offered the read-only tools its globs allow and do not deny, sorted by name`() {
        agentHarness(config) { tools.forEach(::tool) }.execute {
            val registry = get<ToolRegistry>()
            fun offered(agent: String) = registry.offered(AgentId(agent)).map { it.definition.name }

            assertEquals(listOf("fs.glob", "fs.read", "notes.find"), offered("coder"))
            assertEquals(listOf("fs.glob", "fs.read", "fs.secret", "notes.find"), offered("helper"))
            assertEquals(emptyList(), offered("reviewer"))
            assertEquals(emptyList(), offered("other"))
            assertSame(tools.first(), registry.tool("fs.read"))
            assertSame(tools.last(), registry.tool("exec.run"))
            assertNull(registry.tool("fs.missing"))
        }
    }

    @Test
    fun `the start logs which selected tools are withheld until the permission layer exists`() {
        val lines = logged { agentHarness(config) { tools.forEach(::tool) }.execute {} }

        val until = "only READ_ONLY tools are offered until the permission layer exists"
        assertEquals(
            listOf(
                "INFO Agent 'coder' is not offered fs.write (WORKSPACE_WRITE): $until",
                "INFO Agent 'helper' is not offered exec.run (EXEC), fs.write (WORKSPACE_WRITE), notes.add " +
                    "(AGENT_STATE): $until",
            ),
            lines.filter { "is not offered" in it },
        )
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
