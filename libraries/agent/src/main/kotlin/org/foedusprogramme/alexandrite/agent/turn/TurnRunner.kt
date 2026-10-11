package org.foedusprogramme.alexandrite.agent.turn

import kotlinx.coroutines.CancellationException
import org.foedusprogramme.alexandrite.agent.cancel.TurnCancelled
import org.foedusprogramme.alexandrite.agent.cancel.TurnShutdown
import org.foedusprogramme.alexandrite.sdk.chat.ConversationId
import org.foedusprogramme.alexandrite.sdk.turn.TurnOutcome

/** Runs the turns that the workers take from their queues. */
internal interface TurnRunner {
    /**
     * Runs [plan], which may take its [followUps] and tells [resolved] the conversation it runs in once it read it, and
     * returns how the turn ended, also once it is cancelled with [TurnCancelled] or [TurnShutdown].
     *
     * A run that throws [CancellationException] instead ends as cancelled, or as cut off with something stored.
     */
    suspend fun run(plan: TurnPlan, followUps: FollowUps, resolved: (ConversationId) -> Unit): TurnOutcome
}
