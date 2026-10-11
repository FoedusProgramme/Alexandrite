package org.foedusprogramme.alexandrite.channel.telegram

import org.foedusprogramme.alexandrite.channel.telegram.approval.ApprovalDesk
import org.foedusprogramme.alexandrite.channel.telegram.format.formatted
import org.foedusprogramme.alexandrite.channel.telegram.format.parts
import org.foedusprogramme.alexandrite.channel.telegram.intake.AccessGate
import org.foedusprogramme.alexandrite.channel.telegram.reply.MAX_PARTS_PER_REPLY
import org.foedusprogramme.alexandrite.channel.telegram.reply.Outbox
import org.foedusprogramme.alexandrite.channel.telegram.reply.TelegramReply
import org.foedusprogramme.alexandrite.channel.telegram.reply.Typing
import org.foedusprogramme.alexandrite.channel.telegram.reply.targetOf
import org.foedusprogramme.alexandrite.sdk.channel.ApprovalAnswer
import org.foedusprogramme.alexandrite.sdk.channel.ApprovalRequest
import org.foedusprogramme.alexandrite.sdk.channel.Channel
import org.foedusprogramme.alexandrite.sdk.channel.ChannelCapabilities
import org.foedusprogramme.alexandrite.sdk.channel.ChannelInstance
import org.foedusprogramme.alexandrite.sdk.channel.Delivery
import org.foedusprogramme.alexandrite.sdk.channel.DeliveryFailure
import org.foedusprogramme.alexandrite.sdk.channel.Markup
import org.foedusprogramme.alexandrite.sdk.channel.OutboundMessage
import org.foedusprogramme.alexandrite.sdk.channel.ReplyEnd
import org.foedusprogramme.alexandrite.sdk.channel.ReplyRequest
import org.foedusprogramme.alexandrite.sdk.channel.ReplySink
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.di.ChannelInstanceScoped
import org.foedusprogramme.alexandrite.sdk.di.Contribute
import org.foedusprogramme.alexandrite.sdk.di.Lifecycle
import java.util.concurrent.ConcurrentHashMap

/** The front end of one bot, which delivers nothing to a chat that is not paired, nor once the instance stops. */
@ChannelInstanceScoped
@Contribute(Channel::class, name = "telegram")
internal class TelegramChannel(
    private val instance: ChannelInstance,
    private val config: TelegramInstanceConfig,
    private val gate: AccessGate,
    private val outbox: Outbox,
    private val typing: Typing,
    private val approvals: ApprovalDesk,
) : Channel,
    Lifecycle {
    private val replies: MutableSet<TelegramReply> = ConcurrentHashMap.newKeySet()

    @Volatile
    private var stopped = false

    override suspend fun capabilities(chat: ChatAddress): ChannelCapabilities = ChannelCapabilities.builder()
        .maxPartsPerReply(MAX_PARTS_PER_REPLY)
        .streaming(config.streaming.enabled)
        .finalReplacesPreview(true)
        .proactive(gate.isPaired(chat))
        .markups(setOf(Markup.PLAIN, Markup.MARKDOWN))
        .build()

    override suspend fun partsNeeded(chat: ChatAddress, text: String, markup: Markup): Int =
        formatted(text, markup).parts().size

    override suspend fun openReply(request: ReplyRequest): ReplySink {
        if (stopped) return Undelivered(STOPPED)
        if (!gate.isPaired(request.turn.chat)) return Undelivered(NOT_PAIRED)
        val target = targetOf(request.turn.chat) ?: return Undelivered(Delivery.NotDelivered(DeliveryFailure.CHAT_GONE))
        val reply = TelegramReply(request, target, config.streaming.enabled, outbox, typing, instance.scope) {
            replies.remove(it)
        }
        replies += reply
        reply.open()
        return reply
    }

    override suspend fun send(chat: ChatAddress, message: OutboundMessage): Delivery = when {
        stopped -> STOPPED
        !gate.isPaired(chat) -> NOT_PAIRED
        else -> outbox.send(chat, message)
    }

    override suspend fun askApproval(request: ApprovalRequest): ApprovalAnswer = when {
        stopped -> ApprovalAnswer.Unavailable("the bot is stopping")

        request.turn.chat.instance != instance.id ->
            ApprovalAnswer.Unavailable("the chat ${request.turn.chat} belongs to another channel instance")

        else -> approvals.ask(request)
    }

    override fun onStop() {
        stopped = true
        replies.forEach { it.stop() }
        replies.clear()
    }

    /** A reply whose final message ends as [delivery]. */
    private class Undelivered(private val delivery: Delivery) : ReplySink {
        override suspend fun preview(segment: Int, text: String) {}

        override suspend fun complete(message: OutboundMessage): Delivery = delivery

        override suspend fun abandon(end: ReplyEnd) {}
    }

    private companion object {
        val STOPPED = Delivery.NotDelivered(DeliveryFailure.TRANSIENT)
        val NOT_PAIRED = Delivery.NotDelivered(DeliveryFailure.FORBIDDEN, "the chat is not paired")
    }
}
