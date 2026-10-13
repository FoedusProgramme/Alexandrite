package org.foedusprogramme.alexandrite.agent.delegation

import kotlinx.coroutines.awaitCancellation
import org.foedusprogramme.alexandrite.agent.permission.TurnTaint
import org.foedusprogramme.alexandrite.agent.tool.DelegatingTurn
import org.foedusprogramme.alexandrite.agent.tool.RunChain
import org.foedusprogramme.alexandrite.agent.turn.FollowUp
import org.foedusprogramme.alexandrite.agent.turn.FollowUps
import org.foedusprogramme.alexandrite.agent.turn.TurnPermit
import org.foedusprogramme.alexandrite.sdk.agent.AgentProfile
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.chat.ConversationId
import org.foedusprogramme.alexandrite.sdk.chat.RunId
import org.foedusprogramme.alexandrite.sdk.chat.TurnInfo
import org.foedusprogramme.alexandrite.sdk.chat.TurnLineage
import org.foedusprogramme.alexandrite.sdk.chat.UserAddress
import org.foedusprogramme.alexandrite.sdk.store.ConversationInfo
import java.time.Instant

/** The name of the tool that delegates a task to a sub-agent. */
internal const val DELEGATE_TOOL: String = "agent.delegate"

/** How deep a tree of sub-agent runs may grow below its root turn. */
internal const val MAX_DEPTH: Int = 3

/** A sub-agent run of [profile] on [task] in [conversation], which a call of [parent]'s turn started as [run]. */
internal class Delegation(
    val run: RunId,
    val lineage: TurnLineage,
    val conversation: ConversationInfo,
    val profile: AgentProfile,
    val task: String,
    val parent: DelegatingTurn,
    val chain: RunChain,
    val background: Boolean = false,
) {
    /** The run's turn, null until it started. */
    @Volatile
    var turn: TurnInfo? = null
        private set

    /** The taint of the run's turn, null until it started. */
    @Volatile
    var taint: TurnTaint? = null
        private set

    /** Notes that the run's turn [turn] started with [taint]. */
    fun started(turn: TurnInfo, taint: TurnTaint) {
        this.taint = taint
        this.turn = turn
    }
}

/** The follow-ups of a sub-agent run, which takes none. */
internal object NoFollowUps : FollowUps {
    override fun take(principal: UserAddress, chat: ChatAddress, since: Instant): List<FollowUp> = emptyList()

    override fun restore(followUps: List<FollowUp>) = Unit

    override fun queued(principal: UserAddress, chat: ChatAddress, since: Instant): Boolean = false

    override suspend fun await(principal: UserAddress, chat: ChatAddress, since: Instant) = awaitCancellation()

    override fun takeJobs(chat: ChatAddress, conversation: ConversationId): List<FollowUp> = emptyList()
}

/** The permit of a sub-agent run, which runs on the permit of its root turn. */
internal object RootPermit : TurnPermit {
    override suspend fun <T> released(block: suspend () -> T): T = block()
}
