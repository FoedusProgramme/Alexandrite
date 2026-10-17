package org.foedusprogramme.alexandrite.channel.telegram.reply

import org.foedusprogramme.alexandrite.channel.telegram.TelegramInstanceConfig
import org.foedusprogramme.alexandrite.channel.telegram.intake.AccessGate
import org.foedusprogramme.alexandrite.sdk.channel.ChannelInstance
import org.foedusprogramme.alexandrite.sdk.channel.Delivery
import org.foedusprogramme.alexandrite.sdk.channel.DeliveryFailure
import org.foedusprogramme.alexandrite.sdk.channel.OutboundMessage
import org.foedusprogramme.alexandrite.sdk.channel.ReplyEnd
import org.foedusprogramme.alexandrite.sdk.channel.ReplyRequest
import org.foedusprogramme.alexandrite.sdk.channel.ReplySink
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.di.ChannelInstanceScoped
import org.foedusprogramme.alexandrite.sdk.i18n.Texts
import java.util.concurrent.ConcurrentHashMap

/** The turns' replies and the messages outside them, delivered to paired chats alone and until [stop]. */
@ChannelInstanceScoped
internal class Deliveries(
    private val instance: ChannelInstance,
    private val config: TelegramInstanceConfig,
    private val gate: AccessGate,
    private val outbox: Outbox,
    private val typing: Typing,
    private val texts: Texts,
) {
    private val replies: MutableSet<TelegramReply> = ConcurrentHashMap.newKeySet()

    @Volatile
    var stopped: Boolean = false
        private set

    suspend fun openReply(request: ReplyRequest): ReplySink {
        if (stopped) return Undelivered(STOPPED)
        if (!gate.isPaired(request.turn.chat)) return Undelivered(NOT_PAIRED)
        val target = targetOf(request.turn.chat) ?: return Undelivered(Delivery.NotDelivered(DeliveryFailure.CHAT_GONE))
        val reply = TelegramReply(request, target, config.streaming.enabled, outbox, typing, texts, instance.scope) {
            replies.remove(it)
        }
        replies += reply
        reply.open()
        return reply
    }

    /** Sends [message] to [chat], its files after what the chat's open replies have shown so far. */
    suspend fun send(chat: ChatAddress, message: OutboundMessage): Delivery = when {
        stopped -> STOPPED

        !gate.isPaired(chat) -> NOT_PAIRED

        message.files.isEmpty() -> outbox.send(chat, message)

        else -> {
            replies.filter { it.chat == chat }.forEach { it.flush() }
            outbox.sendFiles(chat, message)
        }
    }

    fun stop() {
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
