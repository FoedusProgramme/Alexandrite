package org.unitedsoftware.alexandrite.agent.worker

import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.agent.control.CommandLanes
import org.unitedsoftware.alexandrite.agent.control.CommandRegistry
import org.unitedsoftware.alexandrite.sdk.chat.TurnId
import org.unitedsoftware.alexandrite.sdk.di.Binds
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.turn.Admission
import org.unitedsoftware.alexandrite.sdk.turn.RefusalReason
import org.unitedsoftware.alexandrite.sdk.turn.Submission
import org.unitedsoftware.alexandrite.sdk.turn.TurnOutcome
import org.unitedsoftware.alexandrite.sdk.turn.TurnSubmitter
import java.time.Clock
import java.time.Instant

@Singleton
@Binds(TurnSubmitter::class)
internal class AgentTurnSubmitter(
    private val intakes: ChatIntakes,
    private val workers: TurnWorkers,
    private val commands: CommandRegistry,
    private val lanes: CommandLanes,
    private val clock: Clock,
) : TurnSubmitter {
    override fun submit(submission: Submission): Admission {
        if (!workers.accepting) return Admission.Refused(RefusalReason.SHUTTING_DOWN)
        val now = clock.instant()
        val turn = newTurnId(now)
        return try {
            when (submission) {
                is Submission.Message -> intakes.dispatch(turn, submission.message, submission.capacity, now)
                is Submission.Command -> command(turn, submission, now)
            }
        } catch (e: Exception) {
            logger.error("Cannot take the submission {} of {}", turn, submission.chat, e)
            Admission.Accepted(AgentTicket.ended(turn, TurnOutcome.Failed("The agent could not take it: $e")))
        }
    }

    private fun command(turn: TurnId, command: Submission.Command, now: Instant): Admission {
        val handler = commands.handler(command.invocation.name)
        val carrier = command.message
        return when {
            handler != null -> lanes.submit(turn, command, handler, now)
            carrier != null -> intakes.dispatch(turn, carrier, command.capacity, now)
            else -> lanes.unknown(turn, command.invocation)
        }
    }
}

private val logger = LoggerFactory.getLogger(AgentTurnSubmitter::class.java)
