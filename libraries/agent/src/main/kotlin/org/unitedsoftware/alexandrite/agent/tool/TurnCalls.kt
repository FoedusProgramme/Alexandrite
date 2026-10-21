package org.unitedsoftware.alexandrite.agent.tool

import org.unitedsoftware.alexandrite.sdk.channel.DelegatedRun
import org.unitedsoftware.alexandrite.sdk.channel.ReplyActivity
import org.unitedsoftware.alexandrite.sdk.channel.ReplySink
import org.unitedsoftware.alexandrite.sdk.channel.ToolCallStatus
import org.unitedsoftware.alexandrite.sdk.channel.TurnEvent
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChatKind
import org.unitedsoftware.alexandrite.sdk.chat.ReplyTarget
import org.unitedsoftware.alexandrite.sdk.chat.RunId
import org.unitedsoftware.alexandrite.sdk.chat.ToolCallId
import org.unitedsoftware.alexandrite.sdk.chat.TurnInfo
import org.unitedsoftware.alexandrite.sdk.model.ReasoningEffort
import org.unitedsoftware.alexandrite.sdk.permission.PermissionMode
import org.unitedsoftware.alexandrite.sdk.transcript.ModelRef

/** What a turn does for its tool calls besides running them. */
internal interface TurnCalls {
    /** Notes that the tool of [call] starts. */
    fun starting(call: ToolCallId)

    /** Whether the turn's principal sent a message that joins the turn at its next tool boundary. */
    fun followedUp(): Boolean

    /** The texts that the turn's principal wrote itself in the turn so far, message by message. */
    fun ownWords(): List<String>

    /**
     * What [ask] returns for [call], which waits for the approval request [request] without the turn's permit, or null
     * once the turn's principal sends a message that joins the turn.
     */
    suspend fun <T : Any> awaitApproval(call: ToolCallId, request: String, ask: suspend () -> T): T?

    /** Shows that [call] now runs, having passed admission. */
    suspend fun running(call: ToolCallId) {}

    /** Shows that [call] now waits for an approver. */
    suspend fun waitingApproval(call: ToolCallId) {}

    /** Shows that [call] ended with [status], shown with [content]. */
    suspend fun ended(call: ToolCallId, status: ToolCallStatus, content: TurnEvent.Content?) {}

    /** Where the turn, a sub-agent run, sits below its root turn, null for a top-level turn. */
    val chain: RunChain? get() = null

    /** Whether an approver denied [call], a tool and its canonical arguments, in a sub-agent run of the tree. */
    fun deniedInTree(call: String): Boolean = false

    /** Notes that an approver denied [call] in a sub-agent run of the turn's tree. */
    fun denied(call: String) {}
}

/** What a sub-agent run takes from the turn that delegated it. */
internal interface DelegatingTurn : TurnCalls {
    val turn: TurnInfo
    val runAncestors: Set<RunId>
        get() = turn.lineage?.run?.let(::setOf).orEmpty()
    val activityVisible: Boolean get() = turn.replyTarget == ReplyTarget.CHAT
    val model: ModelRef
    val reasoning: ReasoningEffort?

    /** The names of the tools that the turn may call. */
    val callable: Set<String>

    /** Whether the turn's operator acts under a plugin's mandate, which ends once its conversation is tainted. */
    val mandated: Boolean get() = false

    /** The kind of the chat that the root turn's message came from, null when it came from none. */
    val chatKind: ChatKind? get() = null

    /** Whether the turn or one it descends from used up its token budget. */
    val overBudget: Boolean

    /** Whether the agent vouches for what the turn says now, as for its reply. */
    val vouched: Boolean get() = false

    /** What [ask] returns, asked without the root turn's permit, or null once its principal follows up. */
    suspend fun <T : Any> asking(ask: suspend () -> T): T?

    /** Shows [event] of a sub-agent run below the turn. */
    suspend fun show(event: TurnEvent)

    /** Hands the activity of the background [run] over from the turn's reply, as [ReplySink.detachActivity] does. */
    suspend fun detachActivity(run: DelegatedRun): ReplyActivity? = null

    /** Counts [tokens] that a sub-agent run below the turn spent toward the turn's budget. */
    fun spend(tokens: Long)
}

/** The agents from a root turn's down to a sub-agent run, with their profiles' modes and tasks. */
internal class RunChain(
    val agents: List<AgentId>,
    /** The modes of the profiles along the chain that set one. */
    val modes: List<PermissionMode>,
    val tasks: List<DelegatedTask>,
) {
    /** The chain written as `main → reviewer`. */
    override fun toString(): String = agents.joinToString(" → ")
}

/** The [task] that [from] delegated to [to]. */
internal class DelegatedTask(val from: AgentId, val to: AgentId, val task: String)
