package org.unitedsoftware.alexandrite.agent.turn

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.agent.delegation.DELEGATE_TOOL
import org.unitedsoftware.alexandrite.agent.delegation.RUN_INTERRUPTED_RESULT
import org.unitedsoftware.alexandrite.agent.delivery.Notices
import org.unitedsoftware.alexandrite.agent.i18n.TextKeys
import org.unitedsoftware.alexandrite.agent.job.AGENT_MESSAGE_TOOL
import org.unitedsoftware.alexandrite.agent.job.InterruptedJob
import org.unitedsoftware.alexandrite.agent.job.InterruptedJobs
import org.unitedsoftware.alexandrite.agent.job.JobRecord
import org.unitedsoftware.alexandrite.agent.job.JobRecords
import org.unitedsoftware.alexandrite.agent.job.described
import org.unitedsoftware.alexandrite.agent.permission.AwaitingCalls
import org.unitedsoftware.alexandrite.agent.permission.Refusals
import org.unitedsoftware.alexandrite.agent.tool.CANCELLED_RESULT
import org.unitedsoftware.alexandrite.agent.tool.textResult
import org.unitedsoftware.alexandrite.sdk.chat.ConversationId
import org.unitedsoftware.alexandrite.sdk.chat.RunId
import org.unitedsoftware.alexandrite.sdk.chat.ToolCallId
import org.unitedsoftware.alexandrite.sdk.chat.TurnId
import org.unitedsoftware.alexandrite.sdk.chat.TurnKind
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.store.ConversationState
import org.unitedsoftware.alexandrite.sdk.store.ConversationStore
import org.unitedsoftware.alexandrite.sdk.store.TranscriptStore
import org.unitedsoftware.alexandrite.sdk.store.TurnEndKind
import org.unitedsoftware.alexandrite.sdk.store.TurnRecord
import org.unitedsoftware.alexandrite.sdk.transcript.NotRunReason
import org.unitedsoftware.alexandrite.sdk.transcript.NoticeEntry
import org.unitedsoftware.alexandrite.sdk.transcript.NoticeKind
import org.unitedsoftware.alexandrite.sdk.transcript.ToolOutcome

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
        job.delegated?.let { sealRun(id, it) }
        try {
            if (conversations.conversation(job.conversation)?.state == ConversationState.ACTIVE) {
                interruptedJobs.add(job.conversation, InterruptedJob(id, job.tool, job.title))
                val text = if (job.tool == AGENT_MESSAGE_TOOL) {
                    notices.text(job.key, TextKeys.MESSAGE_INTERRUPTED, "agent" to job.title)
                } else {
                    notices.text(job.key, TextKeys.JOB_INTERRUPTED, "job" to id, "call" to call)
                }
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

    /** Seals [conversation] of the background run [id], which may have waited for a permit and never started. */
    private suspend fun sealRun(id: RunId, conversation: ConversationId) {
        try {
            conversations.sealDelegated(conversation)
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            logger.warn(
                "The conversation {} of interrupted run {} could not be sealed: {}",
                conversation,
                id,
                e.toString(),
            )
        }
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
