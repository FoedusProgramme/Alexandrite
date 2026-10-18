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
import org.foedusprogramme.alexandrite.sdk.tool.BackgroundCall
import org.foedusprogramme.alexandrite.sdk.tool.ToolContext
import org.foedusprogramme.alexandrite.sdk.tool.ToolResult
import org.foedusprogramme.alexandrite.sdk.transcript.ModelRef
import org.foedusprogramme.alexandrite.sdk.transcript.UserEntry
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
    /** Whether the call's review asked for it to run as a background job from its start. */
    val startsInBackground: Boolean = false,
) : ToolContext {
    @Volatile
    private var open = true

    private val job = CompletableDeferred<BackgroundCall>()

    override suspend fun background(): BackgroundCall? {
        checkOpen()
        if (!startsInBackground) return null
        return job.await().also { checkOpen() }
    }

    /** Sets up the call's background job, whose updates go to [updates]. */
    fun background(updates: suspend (ToolResult) -> Boolean) {
        job.complete(
            object : BackgroundCall {
                override suspend fun update(result: ToolResult): Boolean {
                    checkOpen()
                    return updates(result)
                }
            },
        )
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

    private val loading = CopyOnWriteArrayList<UserEntry>()

    /** The trusted instructions that the call loads, added to the conversation once its round ends. */
    val loads: List<UserEntry> get() = loading.toList()

    /** Loads [entry], trusted instructions that Alexandrite wrote, into the conversation once the round ends. */
    fun load(entry: UserEntry) {
        checkOpen()
        loading.add(entry)
    }

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
