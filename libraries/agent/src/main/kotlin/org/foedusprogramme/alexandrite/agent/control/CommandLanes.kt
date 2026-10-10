package org.foedusprogramme.alexandrite.agent.control

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.foedusprogramme.alexandrite.agent.delivery.Notices
import org.foedusprogramme.alexandrite.agent.delivery.delivered
import org.foedusprogramme.alexandrite.agent.delivery.notice
import org.foedusprogramme.alexandrite.agent.i18n.TextKeys
import org.foedusprogramme.alexandrite.agent.routing.ChatRouter
import org.foedusprogramme.alexandrite.agent.routing.Resolution
import org.foedusprogramme.alexandrite.agent.worker.AgentTicket
import org.foedusprogramme.alexandrite.sdk.channel.ChannelDirectory
import org.foedusprogramme.alexandrite.sdk.channel.Delivery
import org.foedusprogramme.alexandrite.sdk.channel.DeliveryFailure
import org.foedusprogramme.alexandrite.sdk.chat.TurnId
import org.foedusprogramme.alexandrite.sdk.di.Singleton
import org.foedusprogramme.alexandrite.sdk.plugin.PluginScope
import org.foedusprogramme.alexandrite.sdk.turn.Admission
import org.foedusprogramme.alexandrite.sdk.turn.CommandInvocation
import org.foedusprogramme.alexandrite.sdk.turn.RefusalReason
import org.foedusprogramme.alexandrite.sdk.turn.Submission
import org.foedusprogramme.alexandrite.sdk.turn.TurnOutcome

/** Runs the command invocations that a handler claims, in a lane per chat. */
@Singleton
internal class CommandLanes(
    private val router: ChatRouter,
    private val notices: Notices,
    private val channels: ChannelDirectory,
    private val scope: PluginScope,
) {
    /** Placeholder until T2.5h builds the command lane. */
    fun submit(turn: TurnId, command: Submission.Command): Admission = Admission.Accepted(
        AgentTicket.ended(
            turn,
            TurnOutcome.Failed("Command '/${command.invocation.name}' was not run: commands run from T2.5h."),
        ),
    )

    /** Answers [command], which nobody claims and no message carried, with a notice. */
    fun unknown(turn: TurnId, command: CommandInvocation): Admission {
        when (router.cached(command.chat)) {
            Resolution.Unknown -> return Admission.Refused(RefusalReason.UNKNOWN_CHAT)
            Resolution.NoAgent -> return Admission.Refused(RefusalReason.NO_AGENT)
            else -> Unit
        }
        val ticket = AgentTicket(turn) { false }
        val answer = scope.launch {
            ticket.complete(TurnOutcome.Completed(null, mapOf(command.chat to answer(command))))
        }
        answer.invokeOnCompletion { error ->
            when (error) {
                null -> Unit
                is CancellationException -> ticket.complete(TurnOutcome.ShutDown(replayable = true))
                else -> ticket.complete(TurnOutcome.Failed(error.toString()))
            }
        }
        return Admission.Accepted(ticket)
    }

    private suspend fun answer(command: CommandInvocation): Delivery {
        val chat = command.chat
        val channel = channels.channel(chat.instance)
            ?: return Delivery.NotDelivered(DeliveryFailure.TRANSIENT, "Channel instance ${chat.instance} is closed.")
        val served = router.resolve(chat) as? Resolution.Served
            ?: return Delivery.NotDelivered(DeliveryFailure.UNKNOWN, "No agent serves $chat.")
        val text = notices.text(served.key, TextKeys.UNKNOWN_COMMAND, "command" to command.name)
        return delivered { channel.send(chat, notice(text, command.trigger, null)) }
    }
}
