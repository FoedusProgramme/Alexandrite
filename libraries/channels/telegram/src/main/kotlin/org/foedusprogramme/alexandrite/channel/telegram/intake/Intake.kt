package org.foedusprogramme.alexandrite.channel.telegram.intake

import org.foedusprogramme.alexandrite.channel.telegram.TelegramInstanceConfig
import org.foedusprogramme.alexandrite.channel.telegram.api.Update
import org.foedusprogramme.alexandrite.channel.telegram.reply.Outbox
import org.foedusprogramme.alexandrite.sdk.channel.ChannelInstance
import org.foedusprogramme.alexandrite.sdk.chat.ChannelMessageRef
import org.foedusprogramme.alexandrite.sdk.chat.ChatKind
import org.foedusprogramme.alexandrite.sdk.di.ChannelInstanceScoped
import org.foedusprogramme.alexandrite.sdk.turn.Capacity
import org.foedusprogramme.alexandrite.sdk.turn.Submission
import org.slf4j.LoggerFactory
import java.util.Locale

/** Takes one update through the access gate, the reader, the command rules and the admissions. */
@ChannelInstanceScoped
internal class Intake(
    private val instance: ChannelInstance,
    private val config: TelegramInstanceConfig,
    private val session: BotSession,
    private val gate: AccessGate,
    private val admissions: Admissions,
    private val outbox: Outbox,
) {
    private val reader by lazy { UpdateReader(instance.id, config.adminUserIds.toSet(), session.identity) }

    /** Dispatches [update] without waiting for the network. */
    fun dispatch(update: Update): Dispatch {
        val message = update.message ?: return ignored(update, "it holds no message")
        val chat = reader.chatOf(message)
        val kind = reader.kindOf(message.chat)
        val trigger = ChannelMessageRef(chat, "${message.messageId}")
        val requester = message.from?.let { reader.user(it, message.senderChat) }
        if (!gate.admit(chat, kind, message.chat.title, trigger, requester, reader.addressed(message))) {
            return Dispatch.DONE
        }
        return when (val inbound = reader.read(message)) {
            is Inbound.Ignored -> ignored(update, inbound.why)

            is Inbound.Unsupported -> {
                admissions.unsupported(inbound.chat, inbound.trigger)
                Dispatch.DONE
            }

            is Inbound.Message -> admissions.submit(Submission.Message(inbound.message), trigger)

            is Inbound.Command -> command(inbound, kind, trigger)
        }
    }

    private fun command(inbound: Inbound.Command, kind: ChatKind, trigger: ChannelMessageRef): Dispatch {
        val invocation = inbound.invocation
        val refusal = CommandRules.refusal(invocation, kind)
        if (refusal != null) {
            outbox.notify(invocation.chat, trigger, refusal, "command" to invocation.name.lowercase(Locale.ROOT))
            return Dispatch.DONE
        }
        val capacity = if (invocation.issuer.isAdmin) Capacity.EXEMPT else Capacity.COUNTED
        return admissions.submit(Submission.Command(invocation, inbound.message, capacity), trigger)
    }

    private fun ignored(update: Update, why: String): Dispatch {
        logger.debug("{} ignores update {}: {}", instance.id, update.updateId, why)
        return Dispatch.DONE
    }
}

private val logger = LoggerFactory.getLogger(Intake::class.java)
