package org.unitedsoftware.alexandrite.agent.control

import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.agent.cancel.TurnCancelled
import org.unitedsoftware.alexandrite.agent.delegation.DelegatedRuns
import org.unitedsoftware.alexandrite.agent.job.Jobs
import org.unitedsoftware.alexandrite.agent.who
import org.unitedsoftware.alexandrite.agent.worker.AgentTurnInitiation
import org.unitedsoftware.alexandrite.agent.worker.ChatIntakes
import org.unitedsoftware.alexandrite.agent.worker.TurnWorkers
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.chat.RunId
import org.unitedsoftware.alexandrite.sdk.chat.TurnId
import org.unitedsoftware.alexandrite.sdk.di.Binds
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.turn.JobStatus
import org.unitedsoftware.alexandrite.sdk.turn.TurnControl
import org.unitedsoftware.alexandrite.sdk.turn.TurnStatus

@Singleton
@Binds(TurnControl::class)
internal class TurnControlImpl(
    private val workers: TurnWorkers,
    private val background: Jobs,
    private val intakes: ChatIntakes,
    private val initiation: AgentTurnInitiation,
    private val lanes: CommandLanes,
    private val runs: DelegatedRuns,
) : TurnControl {
    override suspend fun turns(chat: ChatAddress?): List<TurnStatus> =
        (workers.statuses(chat) + runs.statuses(chat) + lanes.statuses(chat)).sortedBy { it.queuedAt }

    override fun cancel(chat: ChatAddress, by: ChatUser?): Boolean {
        val cancelled = workers.cancelRunning(chat, TurnCancelled(by))
        if (cancelled) logger.info("The running turns from {} are cancelled by {}", chat, who(by))
        val stopped = background.stopAll(chat, by)
        return cancelled || stopped > 0
    }

    override fun cancelTurn(turn: TurnId, by: ChatUser?): Boolean {
        val cause = TurnCancelled(by)
        val found = initiation.cancelHeld(turn, cause) || intakes.cancel(turn, cause) || lanes.cancel(turn, cause) ||
            runs.cancelTurn(turn, cause)
        if (found) logger.info("Turn {} is cancelled by {}", turn, who(by))
        return found
    }

    override fun cancelRun(run: RunId, tree: Boolean, by: ChatUser?): Boolean {
        val backgroundStopped = background.stop(run, null, by, tree)
        val runStopped = runs.cancel(run, tree, TurnCancelled(by))
        if (!backgroundStopped && !runStopped) return false
        logger.info("Run {}{} is cancelled by {}", run, if (tree) " and the runs below it" else "", who(by))
        return true
    }

    override fun jobs(chat: ChatAddress): List<JobStatus> = background.list(chat)

    override fun stopJob(chat: ChatAddress, job: RunId, by: ChatUser?): Boolean = background.stop(job, chat, by)
}

private val logger = LoggerFactory.getLogger(TurnControlImpl::class.java)
