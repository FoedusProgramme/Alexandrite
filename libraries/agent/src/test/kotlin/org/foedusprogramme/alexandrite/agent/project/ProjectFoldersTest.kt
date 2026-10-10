package org.foedusprogramme.alexandrite.agent.project

import org.foedusprogramme.alexandrite.sdk.chat.AgentId
import org.foedusprogramme.alexandrite.sdk.tool.FloorCheck
import org.foedusprogramme.alexandrite.sdk.tool.HardFloor
import org.foedusprogramme.alexandrite.testkit.testHardFloor
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

class ProjectFoldersTest {
    @TempDir
    lateinit var directory: Path

    private val root: Path get() = directory.toRealPath()
    private val repository: Path get() = root.resolve("repo")
    private val agent = AgentId("coder")

    private fun write(file: Path, text: String = "Be brief.") {
        file.parent.createDirectories()
        file.writeText(text)
    }

    private fun folders(protected: List<Path> = emptyList(), trusted: Set<Path> = emptySet()) =
        ProjectFolders(testHardFloor(protected), trusted::contains)

    private fun Project.found(): List<String> = files.map { folder.relativize(it.found).toString() }

    @Test
    fun `the nearest folder with git is the project, with the instruction files from it down to the touched path`() {
        repository.resolve(".git").createDirectories()
        listOf("AGENTS.md", "CLAUDE.md", "src/AGENTS.md", "src/main/CLAUDE.md", "docs/AGENTS.md")
            .forEach { write(repository.resolve(it)) }
        write(root.resolve("AGENTS.md"))
        write(repository.resolve("src/main/Greeter.kt"))

        val projects = folders().projects(listOf(repository.resolve("src/main/Greeter.kt")), agent)

        assertEquals(listOf(repository), projects.map { it.folder })
        assertEquals(listOf("AGENTS.md", "CLAUDE.md", "src/AGENTS.md", "src/main/CLAUDE.md"), projects.single().found())
    }

    @Test
    fun `touched paths of one project merge into it, each file once`() {
        Files.writeString(repository.createDirectories().resolve(".git"), "gitdir: elsewhere\n")
        listOf("AGENTS.md", "a/AGENTS.md", "b/CLAUDE.md").forEach { write(repository.resolve(it)) }
        Files.createSymbolicLink(repository.resolve("CLAUDE.md"), repository.resolve("AGENTS.md"))

        val projects = folders().projects(
            listOf(repository.resolve("a/x.kt"), repository.resolve("b"), repository.resolve("a/y.kt")),
            agent,
        )

        assertEquals(listOf(repository), projects.map { it.folder })
        assertEquals(listOf("AGENTS.md", "a/AGENTS.md", "b/CLAUDE.md"), projects.single().found())
    }

    @Test
    fun `without git the touched path's own directory is the project, unless a trusted folder covers it`() {
        val notes = root.resolve("notes")
        listOf("AGENTS.md", "week/AGENTS.md").forEach { write(notes.resolve(it)) }
        val touched = listOf(notes.resolve("week/monday.md"))

        val alone = folders().projects(touched, agent)
        val covered = folders(trusted = setOf(notes)).projects(touched, agent)

        assertEquals(listOf(notes.resolve("week") to listOf("AGENTS.md")), alone.map { it.folder to it.found() })
        assertEquals(listOf(notes to listOf("AGENTS.md", "week/AGENTS.md")), covered.map { it.folder to it.found() })
    }

    @Test
    fun `a protected directory is never looked at`() {
        val secret = repository.resolve("secret")
        repository.resolve(".git").createDirectories()
        write(repository.resolve("CLAUDE.md"))
        write(secret.resolve("AGENTS.md"))
        Files.createSymbolicLink(repository.resolve("AGENTS.md"), secret.resolve("AGENTS.md"))
        val vault = root.resolve("vault")
        write(vault.resolve("AGENTS.md"))
        vault.resolve(".git").createDirectories()
        write(vault.resolve("open/AGENTS.md"))

        val projects = folders(protected = listOf(secret, vault.resolve("open"))).projects(
            listOf(secret.resolve("key.txt"), repository.resolve("README.md"), vault.resolve("open/x.md")),
            agent,
        )

        assertEquals(listOf(repository to listOf("CLAUDE.md")), projects.map { it.folder to it.found() })
    }

    @Test
    fun `the walk up stops below a protected directory`() {
        val data = root.resolve("data")
        data.resolve(".git").createDirectories()
        write(data.resolve("AGENTS.md"))
        val workspace = data.resolve("workspace")
        write(workspace.resolve("AGENTS.md"))
        val floor = testHardFloor(listOf(data))
        val carved = object : HardFloor by floor {
            override fun check(path: Path, agent: AgentId?): FloorCheck =
                if (path.startsWith(workspace)) FloorCheck.Allowed(path) else floor.check(path, agent)
        }

        val projects = ProjectFolders(carved) { false }.projects(listOf(workspace.resolve("notes.md")), agent)

        assertEquals(listOf(workspace to listOf("AGENTS.md")), projects.map { it.folder to it.found() })
    }

    @Test
    fun `files with more than one hard link and directories are no instruction files`() {
        repository.resolve(".git").createDirectories()
        write(root.resolve("elsewhere.md"))
        Files.createLink(repository.resolve("AGENTS.md"), root.resolve("elsewhere.md"))
        repository.resolve("CLAUDE.md").createDirectories()

        val projects = folders().projects(listOf(repository.resolve("README.md")), agent)

        assertEquals(listOf(repository to emptyList<String>()), projects.map { it.folder to it.found() })
    }
}
