package org.foedusprogramme.alexandrite.agent.tool

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.foedusprogramme.alexandrite.agent.directory.ConversationDirectories
import org.foedusprogramme.alexandrite.agent.permission.TurnTaint
import org.foedusprogramme.alexandrite.agent.workspace.Workspaces
import org.foedusprogramme.alexandrite.sdk.chat.ToolCallId
import org.foedusprogramme.alexandrite.sdk.chat.TurnInfo
import org.foedusprogramme.alexandrite.sdk.permission.Clearance
import org.foedusprogramme.alexandrite.sdk.tool.ToolContext
import org.foedusprogramme.alexandrite.sdk.tool.ToolResult
import org.foedusprogramme.alexandrite.sdk.transcript.ModelRef
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

/** The context of the call [call] in [turn], cleared by [clearance], which holds only until [close]. */
internal class AgentToolContext(
    turn: TurnInfo,
    call: ToolCallId,
    private val workspaces: Workspaces,
    private val directories: ConversationDirectories,
    clearance: Clearance?,
    val taint: TurnTaint,
    /** What a sub-agent run that the call starts takes from its turn, null outside a turn. */
    val delegating: DelegatingTurn? = null,
) : ToolContext {
    @Volatile
    private var open = true

    private val backgroundReady = CompletableDeferred<Unit>()
    private var updates: (suspend (ToolResult) -> Unit)? = null

    override suspend fun awaitBackground() {
        backgroundReady.await()
        checkOpen()
    }

    override suspend fun backgroundUpdate(result: ToolResult) {
        awaitBackground()
        checkNotNull(updates)(result)
    }

    fun background(updates: suspend (ToolResult) -> Unit) {
        this.updates = updates
        backgroundReady.complete(Unit)
    }

    private val reported = CopyOnWriteArrayList<Path>()

    override val turn: TurnInfo = turn
        get() = field.also { checkOpen() }

    override val call: ToolCallId = call
        get() = field.also { checkOpen() }

    override val model: ModelRef?
        get() = delegating?.model.also { checkOpen() }

    override val workspace: Path?
        get() = workspaces.paths[turn.agent]

    override val conversationDirectory: Path
        get() = directories.path(turn)

    override val clearance: Clearance? = clearance
        get() = field.also { checkOpen() }

    override suspend fun openWorkspace(): Path {
        val agent = turn.agent
        val path = withContext(Dispatchers.IO) { workspaces.open(agent) }
        return checkNotNull(path) { "Agent '$agent' has no workspace." }
    }

    override suspend fun openConversationDirectory(): Path = withContext(Dispatchers.IO) { directories.open(turn) }

    override suspend fun conversationQuotaRefusal(path: Path, bytes: Long): String? =
        directories.quotaRefusal(conversationDirectory, path, bytes)

    /** The paths that the call reported as touched, each once, in order. */
    val touchedPaths: List<Path> get() = reported.distinct()

    override fun touched(path: Path) {
        checkOpen()
        reported.add(path)
    }

    override fun untrusted(reason: String) {
        checkOpen()
        taint.note("call $call reported untrusted content: $reason")
    }

    fun close() {
        open = false
    }

    private fun checkOpen() {
        check(open) { "A tool context is valid only while its call runs." }
    }
}
