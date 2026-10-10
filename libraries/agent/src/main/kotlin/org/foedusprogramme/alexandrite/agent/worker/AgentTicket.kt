package org.foedusprogramme.alexandrite.agent.worker

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import org.foedusprogramme.alexandrite.agent.turn.TurnCancelled
import org.foedusprogramme.alexandrite.sdk.chat.TurnId
import org.foedusprogramme.alexandrite.sdk.turn.TurnOutcome
import org.foedusprogramme.alexandrite.sdk.turn.TurnTicket

/** The ticket of a submitted turn, which [canceller] drops or cancels. */
internal class AgentTicket(override val turn: TurnId, private val canceller: (TurnId) -> Boolean) : TurnTicket {
    private val result = CompletableDeferred<TurnOutcome>()

    val ended: Boolean get() = result.isCompleted

    /** Ends the turn with [outcome] unless it ended before. */
    fun complete(outcome: TurnOutcome): Boolean = result.complete(outcome)

    override suspend fun outcome(): TurnOutcome = result.await()

    override fun cancel(): Boolean = !ended && canceller(turn)

    companion object {
        fun ended(turn: TurnId, outcome: TurnOutcome): AgentTicket = AgentTicket(turn) { false }.also {
            it.complete(outcome)
        }
    }
}

/** How a turn that [cause] ended before it started ends. */
internal fun notStarted(cause: CancellationException): TurnOutcome =
    if (cause is TurnCancelled) TurnOutcome.Cancelled else TurnOutcome.ShutDown(replayable = true)
