package org.foedusprogramme.alexandrite.channel.telegram

import org.foedusprogramme.alexandrite.channel.telegram.format.MAX_MESSAGE_LENGTH
import org.foedusprogramme.alexandrite.channel.telegram.format.formatted
import org.foedusprogramme.alexandrite.channel.telegram.reply.Outbox
import org.foedusprogramme.alexandrite.channel.telegram.reply.TelegramReply
import org.foedusprogramme.alexandrite.channel.telegram.reply.Typing
import org.foedusprogramme.alexandrite.channel.telegram.reply.targetOf
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

/** The front end of one bot, which delivers nothing once the instance stops. */
@ChannelInstanceScoped
@Contribute(Channel::class, name = "telegram")
internal class TelegramChannel(
    private val instance: ChannelInstance,
    private val config: TelegramInstanceConfig,
    private val outbox: Outbox,
    private val typing: Typing,
) : Channel,
    Lifecycle {
    private val replies: MutableSet<TelegramReply> = ConcurrentHashMap.newKeySet()

    @Volatile
    private var stopped = false

    override suspend fun capabilities(chat: ChatAddress): ChannelCapabilities = ChannelCapabilities.builder()
        .maxPartsPerReply(1)
        .streaming(config.streaming.enabled)
        .finalReplacesPreview(true)
        .proactive(true)
        .markups(setOf(Markup.PLAIN, Markup.MARKDOWN))
        .build()

    override suspend fun partsNeeded(chat: ChatAddress, text: String, markup: Markup): Int =
        maxOf(1, (formatted(text, markup).text.length + MAX_MESSAGE_LENGTH - 1) / MAX_MESSAGE_LENGTH)

    override suspend fun openReply(request: ReplyRequest): ReplySink {
        if (stopped) return Undelivered(STOPPED)
        val target = targetOf(request.turn.chat) ?: return Undelivered(Delivery.NotDelivered(DeliveryFailure.CHAT_GONE))
        val reply = TelegramReply(request, target, config.streaming.enabled, outbox, typing, instance.scope) {
            replies.remove(it)
        }
        replies += reply
        reply.open()
        return reply
    }

    override suspend fun send(chat: ChatAddress, message: OutboundMessage): Delivery =
        if (stopped) STOPPED else outbox.send(chat, message)

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
    }
}
