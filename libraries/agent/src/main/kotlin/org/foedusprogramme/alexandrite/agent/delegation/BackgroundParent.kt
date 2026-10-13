package org.foedusprogramme.alexandrite.agent.delegation

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.foedusprogramme.alexandrite.agent.tool.DelegatingTurn
import org.foedusprogramme.alexandrite.sdk.channel.ChannelDirectory
import org.foedusprogramme.alexandrite.sdk.channel.ReplyEnd
import org.foedusprogramme.alexandrite.sdk.channel.ReplyRequest
import org.foedusprogramme.alexandrite.sdk.channel.ReplySink
import org.foedusprogramme.alexandrite.sdk.channel.ToolCallStatus
import org.foedusprogramme.alexandrite.sdk.channel.TurnEvent
import org.foedusprogramme.alexandrite.sdk.chat.AgentId
import org.foedusprogramme.alexandrite.sdk.chat.RunId
import org.foedusprogramme.alexandrite.sdk.chat.ToolCallId
import org.foedusprogramme.alexandrite.sdk.chat.TurnId
import org.foedusprogramme.alexandrite.sdk.chat.rebuild
import org.foedusprogramme.alexandrite.sdk.tool.ToolActivity

internal class BackgroundParent(
    parent: DelegatingTurn,
    private val channels: ChannelDirectory,
    private val activityTurn: TurnId,
    private val run: RunId,
    private val profile: AgentId,
) : DelegatingTurn by parent {
    private val words = parent.ownWords().toList()
    private val approvals = Mutex()
    private var sink: ReplySink? = null
    private val call = ToolCallId("background-${run.value}")

    override val overBudget: Boolean get() = false
    override val callable: Set<String> = parent.callable.toSet()
    override fun spend(tokens: Long) = Unit
    override fun followedUp(): Boolean = false
    override fun ownWords(): List<String> = words

    override suspend fun <T : Any> asking(ask: suspend () -> T): T = approvals.withLock { ask() }

    override suspend fun show(event: TurnEvent) {
        sink?.event(event)
    }

    suspend fun open() {
        if (!activityVisible) return
        val channel = channels.channel(turn.chat.instance) ?: return
        sink = channel.openReply(ReplyRequest(turn.rebuild { id(activityTurn) }, null))
        show(TurnEvent.ToolCallStarted(call, DELEGATE_TOOL, ToolActivity.OTHER, "$profile ($run)", run, profile))
    }

    suspend fun close(status: ToolCallStatus) {
        try {
            show(TurnEvent.ToolCallUpdated(call, status, run = run))
        } finally {
            sink?.abandon(ReplyEnd.of("completed"))
        }
    }
}
