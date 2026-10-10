package org.foedusprogramme.alexandrite.agent.project

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

/** A project [folder] (canonical) and the instruction files found from it down to the touched paths. */
internal class Project(val folder: Path, val files: List<InstructionFile>)

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
                    Project(folder, projects.flatMap { it.files }.distinctBy { it.canonical })
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
         * [start] itself, with the instruction files from there down to [start].
         */
        private fun project(start: Path): Project {
            val folder = marked(start) ?: start
            val below = generateSequence(start) { it.parent }.takeWhile { it != folder }.toList()
            return Project(folder, below.plusElement(folder).asReversed().flatMap(::files))
        }

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
                if (trusted(current) || Files.exists(current.resolve(GIT), LinkOption.NOFOLLOW_LINKS)) {
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
                attributes.isRegularFile && links(canonical) == 1
            } catch (e: IOException) {
                false
            }
            return if (single) InstructionFile(path, canonical) else null
        }

        private fun allowed(path: Path): Path? = (floor.check(path, agent) as? FloorCheck.Allowed)?.canonical
    }
}

/** How many hard links [file] has, 1 where the file system does not tell. */
private fun links(file: Path): Int = try {
    Files.getAttribute(file, "unix:nlink", LinkOption.NOFOLLOW_LINKS) as Int
} catch (e: UnsupportedOperationException) {
    1
} catch (e: IllegalArgumentException) {
    1
}

/** The names of the instruction files, in the order they load. */
private val NAMES = listOf("AGENTS.md", "CLAUDE.md")

private const val GIT = ".git"
