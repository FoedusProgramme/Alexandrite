package org.foedusprogramme.alexandrite.agent.job

import org.foedusprogramme.alexandrite.agent.cancel.TurnCancelled
import org.foedusprogramme.alexandrite.agent.turn.TurnPlan
import org.foedusprogramme.alexandrite.agent.turn.TurnSource
import org.foedusprogramme.alexandrite.agent.worker.AgentTicket
import org.foedusprogramme.alexandrite.agent.worker.TurnWorkers
import org.foedusprogramme.alexandrite.agent.worker.WorkerJob
import org.foedusprogramme.alexandrite.agent.worker.newTurnId
import org.foedusprogramme.alexandrite.sdk.chat.TurnKind
import org.foedusprogramme.alexandrite.sdk.di.Binds
import org.foedusprogramme.alexandrite.sdk.di.Singleton
import org.foedusprogramme.alexandrite.sdk.turn.Admission
import org.foedusprogramme.alexandrite.sdk.turn.Capacity
import java.time.Clock
import java.time.Instant

/**
 * Queues a JOB turn for the result of each job that ended, answering the chat that started the job; a job that a
 * sub-agent run started goes back to that run instead.
 */
@Singleton
@Binds(JobDelivery::class)
internal class JobTurns(
    private val workers: Lazy<TurnWorkers>,
    private val inboxes: JobInboxes,
    private val clock: Clock,
) : JobDelivery {
    override fun deliver(job: EndedJob): Boolean {
        inboxes[job.job.resultConversation]?.let { return it.offer(job) }
        val plan = jobPlan(job, clock.instant())
        val ticket = AgentTicket(plan.id) { workers.value.cancel(it, TurnCancelled(null)) }
        return workers.value.submit(plan.key, WorkerJob.Turn(plan, ticket, Capacity.EXEMPT)) is Admission.Accepted
    }

    override fun ended(job: BackgroundJob) {
        inboxes[job.resultConversation]?.ended()
    }
}

/** The JOB turn, queued at [now], that takes the result of [job]. */
internal fun jobPlan(job: EndedJob, now: Instant): TurnPlan {
    val turn = job.job.turn
    return TurnPlan(
        id = newTurnId(now),
        key = turn.key,
        origin = turn.chat,
        kind = TurnKind.JOB,
        input = TurnSource.FromJob(job),
        actor = turn.actor,
        replyTarget = job.job.resultReplyTarget,
        trigger = null,
        queuedAt = now,
    )
}
