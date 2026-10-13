package org.foedusprogramme.alexandrite.agent.control

import org.foedusprogramme.alexandrite.agent.delivery.delivered
import org.foedusprogramme.alexandrite.sdk.channel.ChannelDirectory
import org.foedusprogramme.alexandrite.sdk.channel.Delivery
import org.foedusprogramme.alexandrite.sdk.channel.DeliveryFailure
import org.foedusprogramme.alexandrite.sdk.channel.Markup
import org.foedusprogramme.alexandrite.sdk.channel.MessageKind
import org.foedusprogramme.alexandrite.sdk.channel.OutboundMessage
import org.foedusprogramme.alexandrite.sdk.chat.ChannelMessageRef
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.chat.TurnInfo
import org.foedusprogramme.alexandrite.sdk.turn.CommandContext
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Where a handler runs the invocation [turn], whose replies are notices in reply to [trigger]. */
internal class AgentCommandContext(
    override val turn: TurnInfo,
    private val trigger: ChannelMessageRef?,
    private val channels: ChannelDirectory,
) : CommandContext {
    private val sent = LinkedHashMap<ChatAddress, Delivery>()
    private val replied = Collections.synchronizedList(mutableListOf<ReplyRecord>())

    /** How the replies reached their chat. */
    val deliveries: Map<ChatAddress, Delivery> get() = synchronized(sent) { sent.toMap() }

    /** The replies the handler sent, in order. */
    val replies: List<ReplyRecord> get() = replied.toList()

    override suspend fun reply(text: String, markup: Markup, forModel: Boolean): Delivery {
        val chat = turn.chat
        val message = OutboundMessage.builder(text, MessageKind.NOTICE).markup(markup).replyTo(trigger).build()
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
