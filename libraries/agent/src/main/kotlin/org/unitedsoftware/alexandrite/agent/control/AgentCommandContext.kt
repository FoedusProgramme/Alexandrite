package org.unitedsoftware.alexandrite.agent.control

import org.unitedsoftware.alexandrite.agent.delivery.delivered
import org.unitedsoftware.alexandrite.sdk.channel.ChannelDirectory
import org.unitedsoftware.alexandrite.sdk.channel.Delivery
import org.unitedsoftware.alexandrite.sdk.channel.DeliveryFailure
import org.unitedsoftware.alexandrite.sdk.channel.Markup
import org.unitedsoftware.alexandrite.sdk.channel.MessageKind
import org.unitedsoftware.alexandrite.sdk.channel.OutboundMessage
import org.unitedsoftware.alexandrite.sdk.chat.ChannelMessageRef
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.TurnInfo
import org.unitedsoftware.alexandrite.sdk.turn.CommandContext
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Where a handler runs the invocation [turn], whose replies are notices in reply to [trigger], vouched for when the
 * agent trusts the command's issuer.
 */
internal class AgentCommandContext(
    override val turn: TurnInfo,
    private val trigger: ChannelMessageRef?,
    private val channels: ChannelDirectory,
    private val vouched: Boolean,
) : CommandContext {
    private val sent = LinkedHashMap<ChatAddress, Delivery>()
    private val replied = Collections.synchronizedList(mutableListOf<ReplyRecord>())

    /** How the replies reached their chat. */
    val deliveries: Map<ChatAddress, Delivery> get() = synchronized(sent) { sent.toMap() }

    /** The replies the handler sent, in order. */
    val replies: List<ReplyRecord> get() = replied.toList()

    override suspend fun reply(text: String, markup: Markup, forModel: Boolean): Delivery {
        val chat = turn.chat
        val message = OutboundMessage.builder(text, MessageKind.NOTICE)
            .markup(markup)
            .replyTo(trigger)
            .vouched(vouched)
            .build()
        val channel = channels.channel(chat.instance)
        val delivery = if (channel == null) {
            Delivery.NotDelivered(DeliveryFailure.TRANSIENT, "Channel instance ${chat.instance} is closed.")
        } else {
            delivered { channel.send(chat, message) }
        }
        synchronized(sent) { sent[chat] = sent[chat]?.let { merged(it, delivery) } ?: delivery }
        replied += ReplyRecord(text, forModel)
        return delivery
    }
}

/** The command whose handler the coroutine runs. */
internal class RunningCommand(val turn: TurnInfo) : AbstractCoroutineContextElement(Key) {
    private val waits = AtomicInteger()

    /** Whether the handler waits for a change it queued at a worker. */
    val onWorker: Boolean get() = waits.get() > 0

    suspend fun <T> waitingOnWorker(block: suspend () -> T): T {
        waits.incrementAndGet()
        try {
            return block()
        } finally {
            waits.decrementAndGet()
        }
    }

    companion object Key : CoroutineContext.Key<RunningCommand>
}

/** [first] and [then] as one delivery: all their messages when both arrived, else the first failure. */
private fun merged(first: Delivery, then: Delivery): Delivery = when {
    first is Delivery.Delivered && then is Delivery.Delivered -> Delivery.Delivered(first.messages + then.messages)
    first is Delivery.NotDelivered -> first
    else -> then
}
