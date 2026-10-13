package org.foedusprogramme.alexandrite.agent.turn

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.foedusprogramme.alexandrite.agent.delegation.DELEGATE_TOOL
import org.foedusprogramme.alexandrite.agent.delegation.RUN_INTERRUPTED_RESULT
import org.foedusprogramme.alexandrite.agent.delivery.Notices
import org.foedusprogramme.alexandrite.agent.i18n.TextKeys
import org.foedusprogramme.alexandrite.agent.job.InterruptedJob
import org.foedusprogramme.alexandrite.agent.job.InterruptedJobs
import org.foedusprogramme.alexandrite.agent.job.JobRecord
import org.foedusprogramme.alexandrite.agent.job.JobRecords
import org.foedusprogramme.alexandrite.agent.job.described
import org.foedusprogramme.alexandrite.agent.permission.AwaitingCalls
import org.foedusprogramme.alexandrite.agent.permission.Refusals
import org.foedusprogramme.alexandrite.agent.tool.CANCELLED_RESULT
import org.foedusprogramme.alexandrite.agent.tool.textResult
import org.foedusprogramme.alexandrite.sdk.chat.RunId
import org.foedusprogramme.alexandrite.sdk.chat.ToolCallId
import org.foedusprogramme.alexandrite.sdk.chat.TurnId
import org.foedusprogramme.alexandrite.sdk.chat.TurnKind
import org.foedusprogramme.alexandrite.sdk.di.Singleton
import org.foedusprogramme.alexandrite.sdk.store.ConversationState
import org.foedusprogramme.alexandrite.sdk.store.ConversationStore
import org.foedusprogramme.alexandrite.sdk.store.TranscriptStore
import org.foedusprogramme.alexandrite.sdk.store.TurnEndKind
import org.foedusprogramme.alexandrite.sdk.store.TurnRecord
import org.foedusprogramme.alexandrite.sdk.transcript.NotRunReason
import org.foedusprogramme.alexandrite.sdk.transcript.NoticeEntry
import org.foedusprogramme.alexandrite.sdk.transcript.NoticeKind
import org.foedusprogramme.alexandrite.sdk.transcript.ToolOutcome
import org.slf4j.LoggerFactory

/** Ends the turns and the jobs that an earlier run of the agent left unended. */
@Singleton
internal class Recovery(
    private val conversations: ConversationStore,
    private val transcripts: TranscriptStore,
    private val notices: Notices,
    private val awaiting: AwaitingCalls,
    private val jobs: JobRecords,
    private val interruptedJobs: InterruptedJobs,
) {
    /**
     * Answers the open calls of each unended turn, as interrupted where they waited for an approver or a sub-agent run
     * and as cancelled otherwise, notes the interruption and ends the turn as shut down.
     */
    suspend fun run() {
        val waited = awaiting.all()
        for (turn in conversations.unendedTurns()) recover(turn, waited)
        if (waited.isNotEmpty()) awaiting.clear()
        for ((id, job) in jobs.all()) interrupted(id, job)
    }

    private suspend fun interrupted(id: RunId, job: JobRecord) {
        val call = described(job.tool, job.title)
        try {
            if (conversations.conversation(job.conversation)?.state == ConversationState.ACTIVE) {
                interruptedJobs.add(job.conversation, InterruptedJob(id, job.tool, job.title))
                val text = notices.text(job.key, TextKeys.JOB_INTERRUPTED, "job" to id, "call" to call)
                if (transcripts.turnEntries(job.turn).none { it is NoticeEntry && it.text == text }) {
                    transcripts.append(job.turn, listOf(NoticeEntry(null, text, NoticeKind.INTERRUPTED)))
                }
                logger.info("Job {} ({}) of {} was interrupted by a restart: it is not run again", id, call, job.chat)
            } else {
                logger.info(
                    "Job {} ({}) of {} was interrupted by a restart, after its conversation {} was sealed",
                    id,
                    call,
                    job.chat,
                    job.conversation,
                )
            }
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            logger.warn(
                "Job {} ({}) was interrupted by a restart, which could not be noted: {}",
                id,
                call,
                e.toString(),
            )
            return
        }
        jobs.remove(id)
    }

    private suspend fun recover(turn: TurnRecord, waited: Map<Pair<TurnId, ToolCallId>, String>) {
        val entries = transcripts.turnEntries(turn.id)
        val open = openCalls(entries)
        val interrupted = open.filter { (turn.id to it.id) in waited }
        val runs = open.filter { it.name == DELEGATE_TOOL && (turn.id to it.id) !in waited }
        val state = conversations.conversation(turn.conversation)?.state
        if (state != ConversationState.ACTIVE) {
            logger.warn(
                "Turn {} of {} never ended, but its conversation {} is {}: its {} open call(s) stay unanswered",
                turn.id,
                turn.key,
                turn.conversation,
                state ?: "unknown",
                open.size,
            )
        } else if (entries.none { it is NoticeEntry && it.kind == NoticeKind.INTERRUPTED }) {
            val notice = NoticeEntry(null, notices.text(turn.key, TextKeys.INTERRUPTED), NoticeKind.INTERRUPTED)
            val results = open.map { call ->
                val request = waited[turn.id to call.id]
                when {
                    request != null ->
                        textResult(call, Refusals.INTERRUPTED, ToolOutcome.NotRun(NotRunReason.INTERRUPTED, request))

                    call.name == DELEGATE_TOOL ->
                        textResult(call, RUN_INTERRUPTED_RESULT, ToolOutcome.NotRun(NotRunReason.INTERRUPTED))

                    else -> textResult(call, CANCELLED_RESULT, ToolOutcome.Cancelled)
                }
            }
            transcripts.append(turn.id, results + notice)
        }
        conversations.endTurn(turn.id, TurnEndKind.SHUT_DOWN)
        if (turn.kind == TurnKind.DELEGATED && state == ConversationState.ACTIVE) {
            conversations.sealDelegated(turn.conversation)
        }
        val active = state == ConversationState.ACTIVE
        logger.info(
            "Turn {} of {} never ended: it ends as shut down, with {} open call(s) answered as cancelled",
            turn.id,
            turn.key,
            if (active) open.size - interrupted.size - runs.size else 0,
        )
        if (active && interrupted.isNotEmpty()) {
            logger.info(
                "Turn {} of {} never ended while {} call(s) waited for an approver: they are answered as interrupted",
                turn.id,
                turn.key,
                interrupted.size,
            )
        }
        if (active && runs.isNotEmpty()) {
            logger.info(
                "Turn {} of {} never ended while it waited for {} sub-agent run(s): they are answered as interrupted",
                turn.id,
                turn.key,
                runs.size,
            )
        }
    }
}

private val logger = LoggerFactory.getLogger(Recovery::class.java)
