package org.unitedsoftware.alexandrite.agent.sandbox

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.agent.agentDirectory
import org.unitedsoftware.alexandrite.agent.directory.ConversationDirectories
import org.unitedsoftware.alexandrite.agent.hostPaths
import org.unitedsoftware.alexandrite.agent.settings
import org.unitedsoftware.alexandrite.agent.trust.FolderTrust
import org.unitedsoftware.alexandrite.agent.workspace.Workspaces
import org.unitedsoftware.alexandrite.runtime.ConventionalAgentPlaces
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.testkit.MemoryStore
import org.unitedsoftware.alexandrite.testkit.TestRecords
import org.unitedsoftware.alexandrite.testkit.testHardFloor
import org.unitedsoftware.alexandrite.testkit.testTurn
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.test.Test
import kotlin.test.assertEquals

class WritePlacesTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `own places, trusted folders, a sandbox's allowWrite paths and temp hold an agent's writes, through links`() =
        runTest {
            val root = directory.toRealPath()
            val extra = root.resolve("extra").createDirectories()
            val config = settings(
                """
            {"agents": {
              "main": {"description": "Main.", "sandbox": {"type": "auto", "allowWrite": ["$extra"]}},
              "neko": {"description": "Neko.", "sandbox": {"allowWrite": ["$extra"]}}
            }}
                """.trimIndent(),
            )
            val paths = hostPaths(null, dataRoot = root.resolve("data"))
            val places = ConventionalAgentPlaces(paths)
            val workspaces = Workspaces(paths, places, config)
            val trust = FolderTrust(config, paths, testHardFloor(), TestRecords(MemoryStore()).of()).apply { onStart() }
            val main = testTurn { agent(AgentId("main")) }
            val neko = testTurn { agent(AgentId("neko")) }
            val repository = root.resolve("repo").createDirectories()
            trust.trust(main.agent, repository, null)
            val link = Files.createSymbolicLink(root.resolve("link"), repository)
            val writes =
                WritePlaces(agentDirectory(config), paths, workspaces, ConversationDirectories(places, config), trust)
            val workspace = checkNotNull(workspaces.paths[main.agent])

            val temp = System.getProperty("java.io.tmpdir")
            System.setProperty("java.io.tmpdir", root.resolve("tmp").toString())
            val covered = try {
                listOf(
                    main to workspace.resolve("notes.md"),
                    main to repository.resolve("src/App.kt"),
                    main to link.resolve("src/App.kt"),
                    main to extra.resolve("out.txt"),
                    neko to repository.resolve("src/App.kt"),
                    neko to extra.resolve("out.txt"),
                    main to root.resolve("elsewhere/notes.md"),
                    main to root.resolve("tmp/scratch.txt"),
                ).map { (turn, file) -> writes.covers(turn, file) }
            } finally {
                System.setProperty("java.io.tmpdir", temp)
            }

            assertEquals(listOf(true, true, true, true, false, false, false, true), covered)
        }
}
