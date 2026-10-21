package org.unitedsoftware.alexandrite.channel.telegram.reply

import org.unitedsoftware.alexandrite.channel.telegram.TelegramInstanceConfig
import org.unitedsoftware.alexandrite.channel.telegram.intake.AccessGate
import org.unitedsoftware.alexandrite.sdk.channel.ChannelInstance
import org.unitedsoftware.alexandrite.sdk.channel.Delivery
import org.unitedsoftware.alexandrite.sdk.channel.DeliveryFailure
import org.unitedsoftware.alexandrite.sdk.channel.OutboundMessage
import org.unitedsoftware.alexandrite.sdk.channel.ReplyEnd
import org.unitedsoftware.alexandrite.sdk.channel.ReplyRequest
import org.unitedsoftware.alexandrite.sdk.channel.ReplySink
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.di.ChannelInstanceScoped
import org.unitedsoftware.alexandrite.sdk.i18n.Texts
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
    private val styles: ReplyStyles,
    private val vouched: VouchedReplies,
) {
    private val replies: MutableSet<TelegramReply> = ConcurrentHashMap.newKeySet()

    @Volatile
    var stopped: Boolean = false
        private set

    suspend fun openReply(request: ReplyRequest): ReplySink {
        if (stopped) return Undelivered(STOPPED)
        if (!gate.isPaired(request.turn.chat)) return Undelivered(NOT_PAIRED)
        val target = targetOf(request.turn.chat) ?: return Undelivered(Delivery.NotDelivered(DeliveryFailure.CHAT_GONE))
        val style = styles.of(request.turn.chat)
        val shownIn: (Collection<Long>) -> Unit =
            if (request.vouched) ({ vouched.note(target.chat, it) }) else ({})
        val reply = TelegramReply(
            request,
            target,
            config.streaming.enabled,
            style,
            outbox,
            typing,
            texts,
            shownIn,
            instance.scope,
        ) { replies.remove(it) }
        replies += reply
        reply.open()
        return reply
    }

    /** Sends [message] to [chat], its files after what the chat's open replies have shown so far. */
    suspend fun send(chat: ChatAddress, message: OutboundMessage): Delivery {
        val delivery = when {
            stopped -> STOPPED

            !gate.isPaired(chat) -> NOT_PAIRED

            message.files.isEmpty() -> outbox.send(chat, message)

            else -> {
                flush(chat)
                outbox.sendFiles(chat, message)
            }
        }
        if (message.vouched && delivery is Delivery.Delivered) {
            val target = targetOf(chat)
            if (target != null) vouched.note(target.chat, delivery.messages.mapNotNull { it.id.toLongOrNull() })
        }
        return delivery
    }

    /** Shows what the open replies in [chat] hold so far, so that a message sent beside them follows it. */
    suspend fun flush(chat: ChatAddress) {
        replies.filter { it.chat == chat }.forEach { it.flush() }
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
