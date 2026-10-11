package org.foedusprogramme.alexandrite.agent.tool

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.foedusprogramme.alexandrite.agent.workspace.Workspaces
import org.foedusprogramme.alexandrite.sdk.chat.ToolCallId
import org.foedusprogramme.alexandrite.sdk.chat.TurnInfo
import org.foedusprogramme.alexandrite.sdk.tool.ToolContext
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

/** The context of the call [call] in [turn], which holds only until [close]. */
internal class AgentToolContext(turn: TurnInfo, call: ToolCallId, private val workspaces: Workspaces) : ToolContext {
    @Volatile
    private var open = true

    private val reported = CopyOnWriteArrayList<Path>()

    override val turn: TurnInfo = turn
        get() = field.also { checkOpen() }

    override val call: ToolCallId = call
        get() = field.also { checkOpen() }

    override val workspace: Path?
        get() = workspaces.paths[turn.agent]

    override suspend fun openWorkspace(): Path {
        val agent = turn.agent
        val path = withContext(Dispatchers.IO) { workspaces.open(agent) }
        return checkNotNull(path) { "Agent '$agent' has no workspace." }
    }

    /** The paths that the call reported as touched, each once, in order. */
    val touchedPaths: List<Path> get() = reported.distinct()

    override fun touched(path: Path) {
        checkOpen()
        reported.add(path)
    }

    fun close() {
        open = false
    }

    private fun checkOpen() {
        check(open) { "A tool context is valid only while its call runs." }
    }
}
