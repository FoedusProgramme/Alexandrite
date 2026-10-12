package org.foedusprogramme.alexandrite.channel.onebot.channel

import org.foedusprogramme.alexandrite.channel.onebot.api.OneBotApi
import org.foedusprogramme.alexandrite.channel.onebot.config.OneBotInstanceConfig
import org.foedusprogramme.alexandrite.channel.onebot.mapping.OneBotMessages
import org.foedusprogramme.alexandrite.channel.onebot.protocol.api.SendGroupMessage
import org.foedusprogramme.alexandrite.channel.onebot.protocol.api.SendPrivateMessage
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
import org.foedusprogramme.alexandrite.sdk.turn.TurnSubmitter

/**
 * The OneBot front end of one channel instance.
 *
 * A message the implementation reported becomes an [IncomingMessage] and goes to the agent through the
 * [TurnSubmitter], and what the agent answers goes back as segments over the actions of the standard. Which
 * transport carries either is [OneBotLink]'s business, not this class's.
 */
@ChannelInstanceScoped
@Contribute(Channel::class, name = "onebot")
internal class OneBotChannel(
    private val instance: ChannelInstance,
    private val config: OneBotInstanceConfig,
    private val link: OneBotLink,
    /** The agent, null while the runtime has none. */
    private val submitter: TurnSubmitter?,
) : Channel {
    /** The action that sends a message to [chat], null when this instance serves no such chat. */
    internal fun action(chat: ChatAddress): Pair<String, ChatAddress>? = when {
        OneBotMessages.isGroup(chat) && OneBotMessages.group(chat) != null -> "send_group_msg" to chat
        OneBotMessages.privateUser(chat) != null -> "send_private_msg" to chat
        else -> null
    }

    /** The parameters that send [message] to [chat], null when this instance serves no such chat. */
    internal fun params(chat: ChatAddress, message: OutboundMessage): kotlinx.serialization.json.JsonObject? {
        val user = OneBotMessages.privateUser(chat)
        val group = OneBotMessages.group(chat)
        val segments = OneBotMessages.outgoing(chat, message)
        return when {
            group != null -> SendGroupMessage(group, segments).toJson()
            user != null -> SendPrivateMessage(user, segments).toJson()
            else -> null
        }
    }

    override suspend fun capabilities(chat: ChatAddress): ChannelCapabilities = OneBotMessages.capabilities()

    override suspend fun partsNeeded(chat: ChatAddress, text: String, markup: Markup): Int =
        if (text.isEmpty()) 0 else 1

    override suspend fun openReply(request: ReplyRequest): ReplySink = Sink(this, request)

    override suspend fun send(chat: ChatAddress, message: OutboundMessage): Delivery {
        val target =
            action(chat) ?: return Delivery.NotDelivered(DeliveryFailure.UNKNOWN, "This instance serves no $chat.")
        val params = params(chat, message)
            ?: return Delivery.NotDelivered(DeliveryFailure.UNKNOWN, "This instance serves no $chat.")
        return OneBotMessages.delivery(link.call(target.first, params), chat)
    }

    /** The reply of one turn, which delivers the final message of the agent. */
    private class Sink(private val channel: OneBotChannel, private val request: ReplyRequest) : ReplySink {
        override suspend fun preview(segment: Int, text: String) = Unit

        override suspend fun complete(message: OutboundMessage): Delivery {
            val target = channel.action(request.turn.chat)
                ?: return Delivery.NotDelivered(DeliveryFailure.UNKNOWN, "This instance serves no chat of this turn.")
            val params = channel.params(request.turn.chat, message)
                ?: return Delivery.NotDelivered(DeliveryFailure.UNKNOWN, "This instance serves no chat of this turn.")
            return OneBotMessages.delivery(channel.link.call(target.first, params), request.turn.chat)
        }

        override suspend fun abandon(end: ReplyEnd) = Unit
    }
}
