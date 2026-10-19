package org.unitedsoftware.alexandrite.agent.permission

import kotlinx.coroutines.test.runTest
import org.unitedsoftware.alexandrite.agent.directory.ConversationDirectories
import org.unitedsoftware.alexandrite.agent.hostPaths
import org.unitedsoftware.alexandrite.agent.project.FolderTrust
import org.unitedsoftware.alexandrite.agent.settings
import org.unitedsoftware.alexandrite.agent.workspace.Workspaces
import org.unitedsoftware.alexandrite.runtime.ConventionalAgentPlaces
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ConversationId
import org.unitedsoftware.alexandrite.sdk.tool.FloorCheck
import org.unitedsoftware.alexandrite.sdk.tool.HardFloor
import org.unitedsoftware.alexandrite.testkit.testRecords
import org.unitedsoftware.alexandrite.testkit.testTurn
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
            NoTaintLedger,
        )
    }

    @Test
    fun `a path the floor cannot judge counts as untrusted, instead of failing the turn`() = runTest {
        val reason = readTaint(unreadable).reason(turn, "fs.read", listOf(Path.of("/outside/notes.md")))

        assertEquals("fs.read reached a path that could not be checked", reason)
    }
}
