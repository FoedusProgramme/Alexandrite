package org.unitedsoftware.alexandrite.agent.workspace

import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.agent.agentHarness
import org.unitedsoftware.alexandrite.agent.execute
import org.unitedsoftware.alexandrite.agent.logged
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class WorkspacesTest {
    @TempDir
    lateinit var directory: Path

    private val data: Path get() = directory.resolve("data")
    private val configFile: Path get() = directory.resolve("config/alexandrite.json")

    private val agents: String get() {
        val notes = JsonPrimitive("${directory.resolve("notes")}/./a/..")
        return """
            {
              "agents": {
                "coder": {"description": "Codes."},
                "helper": {"description": "Helps.", "workspace": "helper-files"},
                "notes": {"description": "Notes.", "workspace": $notes}
              }
            }
        """.trimIndent()
    }

    @Test
    fun `each agent's workspace is its configured path or one of its own in the data root`() {
        agentHarness(agents) { dataRoot(data).configFile(configFile) }.execute {
            assertEquals(
                mapOf(
                    AgentId("coder") to data.resolve("workspaces/coder"),
                    AgentId("helper") to configFile.resolveSibling("helper-files"),
                    AgentId("notes") to directory.resolve("notes"),
                ),
                get<Workspaces>().paths,
            )
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `a workspace is created owner-only when it is first opened, never at the start`() {
        agentHarness(agents) { dataRoot(data).configFile(configFile) }.execute {
            val workspaces = get<Workspaces>()
            val coder = data.resolve("workspaces/coder")

            assertFalse(Files.exists(coder.parent))
            val lines = logged {
                repeat(2) { assertEquals(coder, workspaces.open(AgentId("coder"))) }
            }

            assertEquals(listOf("INFO Created the workspace $coder of agent 'coder'"), lines)
            assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(coder)))
            assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(coder.parent)))
            assertFalse(Files.exists(configFile.resolveSibling("helper-files")))
            assertEquals(null, workspaces.open(AgentId("other")))
        }
    }
}
