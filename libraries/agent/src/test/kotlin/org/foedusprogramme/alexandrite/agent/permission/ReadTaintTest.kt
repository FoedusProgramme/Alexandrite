package org.foedusprogramme.alexandrite.agent.permission

import kotlinx.coroutines.test.runTest
import org.foedusprogramme.alexandrite.agent.hostPaths
import org.foedusprogramme.alexandrite.agent.project.FolderTrust
import org.foedusprogramme.alexandrite.agent.settings
import org.foedusprogramme.alexandrite.agent.workspace.Workspaces
import org.foedusprogramme.alexandrite.sdk.chat.AgentId
import org.foedusprogramme.alexandrite.sdk.tool.FloorCheck
import org.foedusprogramme.alexandrite.sdk.tool.HardFloor
import org.foedusprogramme.alexandrite.testkit.testRecords
import java.io.IOException
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class ReadTaintTest {
    private val agent = AgentId("coder")

    private val unreadable = object : HardFloor {
        override fun checkRead(path: Path, agent: AgentId?): FloorCheck = throw IOException("Gone.")

        override fun checkWrite(path: Path, agent: AgentId?): FloorCheck = throw IOException("Gone.")

        override fun checkTree(path: Path, agent: AgentId?): FloorCheck = throw IOException("Gone.")
    }

    private fun readTaint(floor: HardFloor): ReadTaint {
        val paths = hostPaths(null)
        val trust = FolderTrust(settings("{}"), paths, floor, testRecords())
        return ReadTaint(floor, trust, Workspaces(paths, settings("{}")), paths)
    }

    @Test
    fun `a path the floor cannot judge counts as untrusted, instead of failing the turn`() = runTest {
        val reason = readTaint(unreadable).reason(agent, "fs.read", listOf(Path.of("/outside/notes.md")))

        assertEquals("fs.read reached a path that could not be checked", reason)
    }
}
