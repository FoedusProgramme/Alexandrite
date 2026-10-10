package org.foedusprogramme.alexandrite.agent.turn

import org.foedusprogramme.alexandrite.agent.delivery.Notices
import org.foedusprogramme.alexandrite.agent.i18n.TextKeys
import org.foedusprogramme.alexandrite.agent.tool.CANCELLED_RESULT
import org.foedusprogramme.alexandrite.agent.tool.textResult
import org.foedusprogramme.alexandrite.sdk.di.Singleton
import org.foedusprogramme.alexandrite.sdk.store.ConversationState
import org.foedusprogramme.alexandrite.sdk.store.ConversationStore
import org.foedusprogramme.alexandrite.sdk.store.TranscriptStore
import org.foedusprogramme.alexandrite.sdk.store.TurnEndKind
import org.foedusprogramme.alexandrite.sdk.store.TurnRecord
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
) {
    /** Answers the open calls of each unended turn as cancelled, notes the interruption and ends it as shut down. */
    suspend fun run() {
        for (turn in conversations.unendedTurns()) recover(turn)
    }

    private suspend fun recover(turn: TurnRecord) {
        val entries = transcripts.turnEntries(turn.id)
        val open = openCalls(entries)
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
            transcripts.append(turn.id, open.map { textResult(it, CANCELLED_RESULT, ToolOutcome.Cancelled) } + notice)
        }
        conversations.endTurn(turn.id, TurnEndKind.SHUT_DOWN)
        logger.info(
            "Turn {} of {} never ended: it ends as shut down, with {} open call(s) answered as cancelled",
            turn.id,
            turn.key,
            if (state == ConversationState.ACTIVE) open.size else 0,
        )
    }
}

private val logger = LoggerFactory.getLogger(Recovery::class.java)
