package org.unitedsoftware.alexandrite.agent.trust

import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.testkit.testHardFloor
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

class FolderRootsTest {
    @TempDir
    lateinit var directory: Path

    private val agent = AgentId("coder")
    private val home: Path get() = directory.toRealPath().resolve("home")

    private fun roots(): FolderRoots = FolderRoots(testHardFloor(home = home), home)

    @Test
    fun `the candidates run from the folder up to its repository through the folders that mark a project`() {
        val repository = home.resolve("src/repo").also { it.resolve(".git").createDirectories() }
        val module = repository.resolve("module").createDirectories().also {
            it.resolve("package.json").writeText("{}")
        }
        val folder = module.resolve("lib/util").createDirectories()

        val candidates = roots().candidates(folder, agent)

        assertEquals(listOf(folder, module, repository), candidates)
        assertEquals(module, roots().likely(candidates))
        assertEquals(listOf(repository), roots().candidates(repository, agent))
    }

    @Test
    fun `the home folder, the folders above it and the system's are never candidates`() {
        val notes = home.resolve("notes").createDirectories()
        home.resolve("package.json").also { it.parent.createDirectories() }.writeText("{}")

        assertEquals(listOf(notes), roots().candidates(notes, agent))
        assertEquals(notes, roots().likely(listOf(notes)))
        assertEquals(
            listOf(emptyList<Path>(), emptyList(), emptyList(), emptyList()),
            listOf(home, home.parent, Path.of("/etc/ssh"), Path.of("/")).map { roots().candidates(it, agent) },
        )
    }
}
