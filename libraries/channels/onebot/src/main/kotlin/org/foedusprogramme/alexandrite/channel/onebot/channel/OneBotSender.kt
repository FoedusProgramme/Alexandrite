package org.foedusprogramme.alexandrite.channel.onebot.channel

import kotlinx.serialization.json.JsonObject
import org.foedusprogramme.alexandrite.channel.onebot.mapping.OneBotMessages
import org.foedusprogramme.alexandrite.channel.onebot.protocol.api.SendGroupMessage
import org.foedusprogramme.alexandrite.channel.onebot.protocol.api.SendPrivateMessage
import org.foedusprogramme.alexandrite.sdk.channel.Delivery
import org.foedusprogramme.alexandrite.sdk.channel.DeliveryFailure
import org.foedusprogramme.alexandrite.sdk.channel.OutboundMessage
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.di.ChannelInstanceScoped

/**
 * What this instance sends, and the record of what it sent.
 *
 * Every delivery goes through here: the answer of a turn, the notice of a refusal, and the message that shows an
 * approval request. The ids that come back are remembered as they arrive, because what answers the bot is told from
 * them and from nothing else.
 */
@ChannelInstanceScoped
internal class OneBotSender(private val link: OneBotLink, private val sent: OneBotSentMessages) {
    /**
     * The action that sends a message to [chat], null when this instance serves no such chat.
     *
     * A temporary session of a group is a private chat whose thread names the sender, so it is asked for first: a reply
     * to it goes to the sender and not into the group, which is what its address means.
     */
    fun action(chat: ChatAddress): Pair<String, ChatAddress>? = when {
        OneBotMessages.privateTemporary(chat) != null -> "send_private_msg" to chat
        OneBotMessages.isGroup(chat) && OneBotMessages.group(chat) != null -> "send_group_msg" to chat
        OneBotMessages.privateUser(chat) != null -> "send_private_msg" to chat
        else -> null
    }

    /** The parameters that send [message] to [chat], null when this instance serves no such chat. */
    fun params(chat: ChatAddress, message: OutboundMessage): JsonObject? {
        val temporary = OneBotMessages.privateTemporary(chat)
        val user = OneBotMessages.privateUser(chat)
        val group = OneBotMessages.group(chat)
        val segments = OneBotMessages.outgoing(chat, message)
        return when {
            temporary != null -> SendPrivateMessage(temporary, segments).toJson()
            group != null -> SendGroupMessage(group, segments).toJson()
            user != null -> SendPrivateMessage(user, segments).toJson()
            else -> null
        }
    }

    /** Sends [message] to [chat], remembering the ids of what it delivered. */
    suspend fun send(chat: ChatAddress, message: OutboundMessage): Delivery {
        val target = action(chat)
            ?: return Delivery.NotDelivered(DeliveryFailure.UNKNOWN, "This instance serves no $chat.")
        val params = params(chat, message)
            ?: return Delivery.NotDelivered(DeliveryFailure.UNKNOWN, "This instance serves no $chat.")
        return remember(OneBotMessages.delivery(link.call(target.first, params), chat))
    }

    /** Remembers the ids of what [delivery] delivered, which is what a reply is matched against. */
    private fun remember(delivery: Delivery): Delivery {
        (delivery as? Delivery.Delivered)?.messages?.forEach { sent.remember(it.id) }
        return delivery
    }
}
