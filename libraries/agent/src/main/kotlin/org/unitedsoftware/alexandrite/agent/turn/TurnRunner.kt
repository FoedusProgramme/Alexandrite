package org.unitedsoftware.alexandrite.agent.turn

import kotlinx.coroutines.CancellationException
import org.unitedsoftware.alexandrite.agent.cancel.TurnCancelled
import org.unitedsoftware.alexandrite.agent.cancel.TurnShutdown
import org.unitedsoftware.alexandrite.sdk.chat.ConversationId
import org.unitedsoftware.alexandrite.sdk.turn.TurnOutcome

/** Runs the turns that the workers take from their queues. */
internal interface TurnRunner {
    /**
     * Runs [plan] while it holds [permit], which may take its [followUps], tells [resolved] the conversation it runs in
     * once it read it and [decided] once its outcome is decided, and returns how the turn ended, also once it is
     * cancelled with [TurnCancelled] or [TurnShutdown].
     *
     * A run that throws [CancellationException] instead ends as cancelled, or as cut off with something stored.
     */
    suspend fun run(
        plan: TurnPlan,
        followUps: FollowUps,
        permit: TurnPermit,
        resolved: (ConversationId) -> Unit,
        decided: () -> Unit,
    ): TurnOutcome
}
