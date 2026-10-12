package org.foedusprogramme.alexandrite.agent.job

import org.foedusprogramme.alexandrite.agent.cancel.TurnCancelled
import org.foedusprogramme.alexandrite.agent.turn.TurnPlan
import org.foedusprogramme.alexandrite.agent.turn.TurnSource
import org.foedusprogramme.alexandrite.agent.worker.AgentTicket
import org.foedusprogramme.alexandrite.agent.worker.TurnWorkers
import org.foedusprogramme.alexandrite.agent.worker.WorkerJob
import org.foedusprogramme.alexandrite.agent.worker.newTurnId
import org.foedusprogramme.alexandrite.sdk.chat.ReplyTarget
import org.foedusprogramme.alexandrite.sdk.chat.TurnKind
import org.foedusprogramme.alexandrite.sdk.di.Binds
import org.foedusprogramme.alexandrite.sdk.di.Singleton
import org.foedusprogramme.alexandrite.sdk.turn.Admission
import org.foedusprogramme.alexandrite.sdk.turn.Capacity
import java.time.Clock

/** Queues a JOB turn for the result of each job that ended, answering the chat that started the job. */
@Singleton
@Binds(JobDelivery::class)
internal class JobTurns(private val workers: Lazy<TurnWorkers>, private val clock: Clock) : JobDelivery {
    override fun deliver(job: EndedJob): Boolean {
        val turn = job.job.turn
        val now = clock.instant()
        val id = newTurnId(now)
        val plan = TurnPlan(
            id = id,
            key = turn.key,
            origin = turn.chat,
            kind = TurnKind.JOB,
            input = TurnSource.FromJob(job),
            actor = turn.actor,
            replyTarget = ReplyTarget.CHAT,
            trigger = null,
            queuedAt = now,
        )
        val ticket = AgentTicket(id) { workers.value.cancel(it, TurnCancelled(null)) }
        return workers.value.submit(turn.key, WorkerJob.Turn(plan, ticket, Capacity.EXEMPT)) is Admission.Accepted
    }
}
