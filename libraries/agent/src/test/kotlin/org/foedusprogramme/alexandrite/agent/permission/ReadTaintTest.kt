package org.foedusprogramme.alexandrite.agent.permission

import kotlinx.coroutines.test.runTest
import org.foedusprogramme.alexandrite.agent.directory.ConversationDirectories
import org.foedusprogramme.alexandrite.agent.hostPaths
import org.foedusprogramme.alexandrite.agent.project.FolderTrust
import org.foedusprogramme.alexandrite.agent.settings
import org.foedusprogramme.alexandrite.agent.workspace.Workspaces
import org.foedusprogramme.alexandrite.runtime.ConventionalAgentPlaces
import org.foedusprogramme.alexandrite.sdk.chat.AgentId
import org.foedusprogramme.alexandrite.sdk.chat.ConversationId
import org.foedusprogramme.alexandrite.sdk.tool.FloorCheck
import org.foedusprogramme.alexandrite.sdk.tool.HardFloor
import org.foedusprogramme.alexandrite.testkit.testRecords
import org.foedusprogramme.alexandrite.testkit.testTurn
import java.io.IOException
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class ReadTaintTest {
    private val agent = AgentId("coder")
    private val turn = testTurn { agent(agent) }

    private val unreadable = object : HardFloor {
        override fun checkRead(path: Path, agent: AgentId?, conversation: ConversationId?): FloorCheck =
            throw IOException("Gone.")

        override fun checkWrite(path: Path, agent: AgentId?, conversation: ConversationId?): FloorCheck =
            throw IOException("Gone.")

        override fun checkTree(path: Path, agent: AgentId?, conversation: ConversationId?): FloorCheck =
            throw IOException("Gone.")
    }

    private fun readTaint(floor: HardFloor): ReadTaint {
        val paths = hostPaths(null)
        val trust = FolderTrust(settings("{}"), paths, floor, testRecords())
        return ReadTaint(
            floor,
            trust,
            Workspaces(paths, ConventionalAgentPlaces(paths), settings("{}")),
            ConventionalAgentPlaces(paths),
            ConversationDirectories(ConventionalAgentPlaces(paths), settings("{}")),
        )
    }

    @Test
    fun `a path the floor cannot judge counts as untrusted, instead of failing the turn`() = runTest {
        val reason = readTaint(unreadable).reason(turn, "fs.read", listOf(Path.of("/outside/notes.md")))

        assertEquals("fs.read reached a path that could not be checked", reason)
    }
}
