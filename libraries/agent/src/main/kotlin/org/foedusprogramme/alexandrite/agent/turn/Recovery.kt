package org.foedusprogramme.alexandrite.agent.turn

import org.foedusprogramme.alexandrite.agent.delivery.Notices
import org.foedusprogramme.alexandrite.agent.i18n.TextKeys
import org.foedusprogramme.alexandrite.agent.permission.AwaitingCalls
import org.foedusprogramme.alexandrite.agent.permission.Refusals
import org.foedusprogramme.alexandrite.agent.tool.CANCELLED_RESULT
import org.foedusprogramme.alexandrite.agent.tool.textResult
import org.foedusprogramme.alexandrite.sdk.chat.ToolCallId
import org.foedusprogramme.alexandrite.sdk.chat.TurnId
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

/** Ends the turns that an earlier run of the agent left unended. */
@Singleton
internal class Recovery(
    private val conversations: ConversationStore,
    private val transcripts: TranscriptStore,
    private val notices: Notices,
    private val awaiting: AwaitingCalls,
) {
    /**
     * Answers the open calls of each unended turn, as interrupted where they waited for an approver and as cancelled
     * otherwise, notes the interruption and ends the turn as shut down.
     */
    suspend fun run() {
        val waited = awaiting.all()
        for (turn in conversations.unendedTurns()) recover(turn, waited)
        if (waited.isNotEmpty()) awaiting.clear()
    }

    private suspend fun recover(turn: TurnRecord, waited: Map<Pair<TurnId, ToolCallId>, String>) {
        val entries = transcripts.turnEntries(turn.id)
        val open = openCalls(entries)
        val interrupted = open.filter { (turn.id to it.id) in waited }
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
                if (request == null) {
                    textResult(call, CANCELLED_RESULT, ToolOutcome.Cancelled)
                } else {
                    textResult(call, Refusals.INTERRUPTED, ToolOutcome.NotRun(NotRunReason.INTERRUPTED, request))
                }
            }
            transcripts.append(turn.id, results + notice)
        }
        conversations.endTurn(turn.id, TurnEndKind.SHUT_DOWN)
        val active = state == ConversationState.ACTIVE
        logger.info(
            "Turn {} of {} never ended: it ends as shut down, with {} open call(s) answered as cancelled",
            turn.id,
            turn.key,
            if (active) open.size - interrupted.size else 0,
        )
        if (active && interrupted.isNotEmpty()) {
            logger.info(
                "Turn {} of {} never ended while {} call(s) waited for an approver: they are answered as interrupted",
                turn.id,
                turn.key,
                interrupted.size,
            )
        }
    }
}

private val logger = LoggerFactory.getLogger(Recovery::class.java)
