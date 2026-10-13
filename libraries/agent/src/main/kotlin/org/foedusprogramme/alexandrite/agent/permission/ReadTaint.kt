package org.foedusprogramme.alexandrite.agent.permission

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.foedusprogramme.alexandrite.agent.directory.ConversationDirectories
import org.foedusprogramme.alexandrite.agent.project.FolderTrust
import org.foedusprogramme.alexandrite.agent.workspace.Workspaces
import org.foedusprogramme.alexandrite.sdk.chat.AgentId
import org.foedusprogramme.alexandrite.sdk.chat.ConversationId
import org.foedusprogramme.alexandrite.sdk.chat.TurnInfo
import org.foedusprogramme.alexandrite.sdk.chat.rootConversation
import org.foedusprogramme.alexandrite.sdk.di.Singleton
import org.foedusprogramme.alexandrite.sdk.runtime.HostPaths
import org.foedusprogramme.alexandrite.sdk.tool.FloorCheck
import org.foedusprogramme.alexandrite.sdk.tool.HardFloor
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.cancellation.CancellationException

/**
 * Finds the paths a tool call reached outside the trusted folders, the agent's workspace, its persona directory and
 * the turn's own conversation directory.
 */
@Singleton
internal class ReadTaint(
    private val floor: HardFloor,
    private val trust: FolderTrust,
    private val workspaces: Workspaces,
    private val paths: HostPaths,
    private val directories: ConversationDirectories,
) {
    /** Why the paths that a call of [tool] in [turn] [touched] taint its conversation, null when each lies in a trusted place. */
    suspend fun reason(turn: TurnInfo, tool: String, touched: List<Path>): String? {
        if (touched.isEmpty()) return null
        val agent = turn.agent
        val conversation = turn.rootConversation
        return try {
            withContext(Dispatchers.IO) {
                val own = listOfNotNull(
                    workspaces.paths[agent],
                    personaDirectory(paths, agent),
                    directories.path(agent, conversation),
                ).mapNotNull { (floor.checkRead(it, agent, conversation) as? FloorCheck.Allowed)?.canonical }
                touched.firstNotNullOfOrNull { place(it, agent, conversation, own) }?.let { "$tool reached $it" }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Could not tell whether {} touched an untrusted place: {}", tool, e.toString())
            "$tool reached a path that could not be checked"
        }
    }

    /** The kind of untrusted place that [path] lies in, null when it lies in [own] or a trusted folder. */
    private fun place(path: Path, agent: AgentId, conversation: ConversationId, own: List<Path>): String? {
        if (!path.isAbsolute) return "a place it named by a relative path"
        val canonical = when (val check = floor.checkRead(path, agent, conversation)) {
            is FloorCheck.Allowed -> check.canonical
            is FloorCheck.Denied -> return "a protected place (${check.reason})"
        }
        if (own.any(canonical::startsWith)) return null
        val folder = if (Files.isDirectory(canonical)) canonical else canonical.parent ?: canonical
        if (trust.covering(folder) != null) return null
        return "$folder, a folder that is not trusted"
    }
}

private val logger = LoggerFactory.getLogger(ReadTaint::class.java)
