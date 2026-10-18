package org.foedusprogramme.alexandrite.agent.project

import org.foedusprogramme.alexandrite.agent.skill.skillFiles
import org.foedusprogramme.alexandrite.internal.path.hardLinks
import org.foedusprogramme.alexandrite.sdk.chat.AgentId
import org.foedusprogramme.alexandrite.sdk.tool.FloorCheck
import org.foedusprogramme.alexandrite.sdk.tool.HardFloor
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/** An instruction file found at [found], to be read at [canonical]. */
internal class InstructionFile(val found: Path, val canonical: Path)

/**
 * A project [folder] (canonical), the instruction files found from it down to the touched paths and the `SKILL.md`
 * files (canonical) of its skill directories.
 */
internal class Project(val folder: Path, val files: List<InstructionFile>, val skills: List<Path> = emptyList())

/** Finds the projects that the paths a tool call touched lie in. */
internal class ProjectFolders(
    private val floor: HardFloor,
    /** Whether a folder (canonical) is a trusted folder itself, which makes it a project folder. */
    private val trusted: (Path) -> Boolean,
) {
    /**
     * The projects that [touched] (absolute) lie in for [agent], each once with the instruction files of all of them,
     * leaving out the paths that the floor refuses.
     */
    fun projects(touched: List<Path>, agent: AgentId): List<Project> = Search(agent).projects(touched)

    /** One search, which looks at each directory once. */
    private inner class Search(private val agent: AgentId) {
        /** The nearest marked directory at or above a directory, null when the walk up met none. */
        private val marks = HashMap<Path, Path?>()
        private val found = HashMap<Path, List<InstructionFile>>()

        fun projects(touched: List<Path>): List<Project> {
            val starts = touched.filter { it.isAbsolute }.mapNotNull(::start).distinct()
            return starts.map(::project)
                .groupBy { it.folder }
                .map { (folder, projects) ->
                    Project(folder, projects.flatMap { it.files }.distinctBy { it.canonical }, projects.first().skills)
                }
        }

        /** The directory where the search for [path]'s project starts, null when the floor refuses it. */
        private fun start(path: Path): Path? {
            val canonical = allowed(path) ?: return null
            if (Files.isDirectory(canonical)) return canonical
            return canonical.parent?.let(::allowed)
        }

        /**
         * The project of [start]: the nearest directory from it up that holds `.git` or is a trusted folder, else
         * [start] itself, with the instruction files from there down to [start] or the first dependency directory.
         */
        private fun project(start: Path): Project {
            val folder = marked(start) ?: start
            val below = generateSequence(start) { it.parent }.takeWhile { it != folder }.toList().asReversed()
                .takeWhile { it.fileName.toString() !in DEPENDENCIES }
            return Project(folder, (listOf(folder) + below).flatMap(::files), skills(folder))
        }

        /** The `SKILL.md` files of [folder]'s skill directories that the floor lets the agent read. */
        private fun skills(folder: Path): List<Path> = SKILL_DIRECTORIES
            .flatMap { skillFiles(folder.resolve(it)) }
            .mapNotNull(::allowed)

        /** The nearest marked directory from [start] up, stopping below the first directory the floor refuses. */
        private fun marked(start: Path): Path? {
            val walked = mutableListOf<Path>()
            var current: Path? = start
            var mark: Path? = null
            while (current != null) {
                if (current in marks) {
                    mark = marks[current]
                    break
                }
                walked.add(current)
                if (trusted(current) || isRepository(current)) {
                    mark = current
                    break
                }
                current = current.parent?.takeIf { allowed(it) != null }
            }
            walked.forEach { marks[it] = mark }
            return mark
        }

        private fun files(directory: Path): List<InstructionFile> =
            found.getOrPut(directory) { NAMES.mapNotNull { file(directory.resolve(it)) } }

        /** The instruction file at [path], null when there is none, it is refused or it is no single-link file. */
        private fun file(path: Path): InstructionFile? {
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null
            val canonical = allowed(path) ?: return null
            val single = try {
                val attributes =
                    Files.readAttributes(canonical, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                attributes.isRegularFile && hardLinks(canonical) == 1
            } catch (e: IOException) {
                false
            }
            return if (single) InstructionFile(path, canonical) else null
        }

        private fun allowed(path: Path): Path? = (floor.checkRead(path, agent) as? FloorCheck.Allowed)?.canonical
    }
}

/** Whether [directory] holds `.git`. */
internal fun isRepository(directory: Path): Boolean = Files.exists(directory.resolve(GIT), LinkOption.NOFOLLOW_LINKS)

/** Where a project keeps its skills, one directory per skill. */
private val SKILL_DIRECTORIES = listOf(".agents/skills", ".claude/skills")

/** The names of the instruction files, in the order they load. */
private val NAMES = listOf("AGENTS.md", "CLAUDE.md")

/** Directories of third-party code and build output. */
private val DEPENDENCIES =
    setOf("node_modules", "bower_components", "vendor", ".venv", "venv", "site-packages", "target", "build")

private const val GIT = ".git"
