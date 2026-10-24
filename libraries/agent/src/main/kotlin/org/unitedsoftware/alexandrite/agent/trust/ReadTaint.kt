package org.unitedsoftware.alexandrite.agent.trust

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.agent.directory.ConversationDirectories
import org.unitedsoftware.alexandrite.agent.permission.canonical
import org.unitedsoftware.alexandrite.agent.permission.denial
import org.unitedsoftware.alexandrite.agent.workspace.Workspaces
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ConversationId
import org.unitedsoftware.alexandrite.sdk.chat.TurnInfo
import org.unitedsoftware.alexandrite.sdk.chat.rootConversation
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.runtime.AgentPlaces
import org.unitedsoftware.alexandrite.sdk.tool.FloorCheck
import org.unitedsoftware.alexandrite.sdk.tool.HardFloor
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.cancellation.CancellationException

/**
 * Finds the paths a tool call reached outside the trusted folders, the agent's workspace, its persona and skill
 * directories and the turn's own conversation directory, and those that a tainted conversation wrote.
 */
@Singleton
internal class ReadTaint(
    private val floor: HardFloor,
    private val trust: FolderTrust,
    private val workspaces: Workspaces,
    private val places: AgentPlaces,
    private val directories: ConversationDirectories,
    private val ledger: TaintLedger,
) {
    /** Why the paths that a call of [tool] in [turn] [touched] taint its conversation, null when each lies in a trusted place. */
    suspend fun reason(turn: TurnInfo, tool: String, touched: List<Path>): String? =
        places(turn, touched).firstOrNull()?.let { "$tool reached ${it.reason}" }

    /** The places among [touched] that would taint the conversation of [turn], in order. */
    suspend fun places(turn: TurnInfo, touched: List<Path>): List<UntrustedPlace> {
        if (touched.isEmpty()) return emptyList()
        val agent = turn.agent
        val conversation = turn.rootConversation
        return try {
            withContext(Dispatchers.IO) {
                val own = (
                    listOfNotNull(
                        workspaces.paths[agent],
                        places.personaDirectory(agent),
                        directories.path(agent, conversation),
                    ) + places.skillDirectories(agent)
                    ).mapNotNull { floor.checkRead(it, agent, conversation).canonical }
                touched.mapNotNull { place(it, agent, conversation, own) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Could not tell whether a call touched an untrusted place: {}", e.toString())
            listOf(UntrustedPlace(null, "a path that could not be checked", null))
        }
    }

    /** The untrusted place that [path] lies in, null when it lies in [own] or a trusted folder. */
    private fun place(path: Path, agent: AgentId, conversation: ConversationId, own: List<Path>): UntrustedPlace? {
        if (!path.isAbsolute) return UntrustedPlace(path, "a place it named by a relative path", null)
        val canonical = when (val check = floor.checkRead(path, agent, conversation)) {
            is FloorCheck.Allowed -> check.canonical
            is FloorCheck.Ask -> check.canonical
            else -> return UntrustedPlace(path, "a protected place (${check.denial})", null)
        }
        val folder = if (Files.isDirectory(canonical)) canonical else canonical.parent ?: canonical
        val trusted = own.any(canonical::startsWith) || trust.covering(agent, folder) != null
        if (!trusted) return UntrustedPlace(canonical, "$folder, a folder that is not trusted", folder)
        return ledger.reason(canonical)?.let {
            UntrustedPlace(canonical, "$canonical, written by a conversation that had taken in untrusted content", null)
        }
    }
}

/**
 * A [path] that taints a conversation for [reason], which trusting [folder] would keep from tainting, null when no
 * trusted folder would.
 */
internal class UntrustedPlace(val path: Path?, val reason: String, val folder: Path?)

private val logger = LoggerFactory.getLogger(ReadTaint::class.java)
