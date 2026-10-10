package org.foedusprogramme.alexandrite.agent.turn

import kotlinx.coroutines.CancellationException
import org.foedusprogramme.alexandrite.sdk.chat.ChatUser
import org.foedusprogramme.alexandrite.sdk.di.Binds
import org.foedusprogramme.alexandrite.sdk.di.Singleton
import org.foedusprogramme.alexandrite.sdk.turn.TurnOutcome

/** Runs the turns that the workers take from their queues. */
internal interface TurnRunner {
    /**
     * Runs [plan] and returns how the turn ended, also once it is cancelled with [TurnCancelled] or [TurnShutdown].
     *
     * A run that throws [CancellationException] instead ends as cancelled, or as cut off with something stored.
     */
    suspend fun run(plan: TurnPlan): TurnOutcome
}

/** Placeholder until T2.5d builds the turn pipeline. */
@Singleton
@Binds(TurnRunner::class)
internal class StubTurnRunner : TurnRunner {
    override suspend fun run(plan: TurnPlan): TurnOutcome =
        TurnOutcome.Failed("Turn ${plan.id} was not run: the turn pipeline arrives in T2.5d.")
}

/** Cancels a turn at the request of [by], or of the host when it is null. */
internal class TurnCancelled(val by: ChatUser?) : CancellationException("The turn was cancelled.")

/** Cuts a turn off because the agent is shutting down. */
internal class TurnShutdown : CancellationException("The agent is shutting down.")
