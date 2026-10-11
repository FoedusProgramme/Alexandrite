package org.foedusprogramme.alexandrite.agent.permission

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.foedusprogramme.alexandrite.agent.project.FolderTrust
import org.foedusprogramme.alexandrite.agent.workspace.Workspaces
import org.foedusprogramme.alexandrite.sdk.chat.AgentId
import org.foedusprogramme.alexandrite.sdk.di.Singleton
import org.foedusprogramme.alexandrite.sdk.runtime.HostPaths
import org.foedusprogramme.alexandrite.sdk.tool.FloorCheck
import org.foedusprogramme.alexandrite.sdk.tool.HardFloor
import java.nio.file.Files
import java.nio.file.Path

/** Finds the paths a tool call reached outside the trusted folders, the agent's workspace and its persona directory. */
@Singleton
internal class ReadTaint(
    private val floor: HardFloor,
    private val trust: FolderTrust,
    private val workspaces: Workspaces,
    private val paths: HostPaths,
) {
    /**
     * Why the paths that a call of [tool] by [agent] [touched] taint its conversation, null when each lies in a trusted
     * place.
     */
    suspend fun reason(agent: AgentId, tool: String, touched: List<Path>): String? {
        if (touched.isEmpty()) return null
        return withContext(Dispatchers.IO) {
            val own = listOfNotNull(workspaces.paths[agent], personaDirectory(paths, agent)).mapNotNull {
                (floor.checkRead(it, agent) as? FloorCheck.Allowed)?.canonical
            }
            touched.firstNotNullOfOrNull { place(it, agent, own) }?.let { "$tool reached $it" }
        }
    }

    /** The kind of untrusted place that [path] lies in, null when it lies in [own] or a trusted folder. */
    private fun place(path: Path, agent: AgentId, own: List<Path>): String? {
        if (!path.isAbsolute) return "a place it named by a relative path"
        val canonical = when (val check = floor.checkRead(path, agent)) {
            is FloorCheck.Allowed -> check.canonical
            is FloorCheck.Denied -> return "a protected place (${check.reason})"
        }
        if (own.any(canonical::startsWith)) return null
        val folder = if (Files.isDirectory(canonical)) canonical else canonical.parent ?: canonical
        if (trust.covering(folder) != null) return null
        return "$folder, a folder that is not trusted"
    }
}
