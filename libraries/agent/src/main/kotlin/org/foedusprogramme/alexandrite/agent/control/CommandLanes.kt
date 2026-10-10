package org.foedusprogramme.alexandrite.agent.control

import org.foedusprogramme.alexandrite.agent.worker.AgentTicket
import org.foedusprogramme.alexandrite.sdk.chat.TurnId
import org.foedusprogramme.alexandrite.sdk.di.Singleton
import org.foedusprogramme.alexandrite.sdk.turn.Admission
import org.foedusprogramme.alexandrite.sdk.turn.Submission
import org.foedusprogramme.alexandrite.sdk.turn.TurnOutcome

/** Runs the command invocations that a handler claims, in a lane per chat. */
@Singleton
internal class CommandLanes {
    /** Placeholder until T2.5h builds the command lane. */
    fun submit(turn: TurnId, command: Submission.Command): Admission = ended(
        turn,
        TurnOutcome.Failed("Command '/${command.invocation.name}' was not run: commands run from T2.5h."),
    )

    /** Placeholder until T2.5d answers a command that nobody claims and no message carried with a notice. */
    fun unknown(turn: TurnId): Admission = ended(turn, TurnOutcome.Completed(null))

    private fun ended(turn: TurnId, outcome: TurnOutcome) = Admission.Accepted(AgentTicket.ended(turn, outcome))
}
