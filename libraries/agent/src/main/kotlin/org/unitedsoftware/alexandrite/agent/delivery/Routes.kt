package org.unitedsoftware.alexandrite.agent.delivery

import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.agent.config.AgentDirectory
import org.unitedsoftware.alexandrite.agent.routing.ChatTopology
import org.unitedsoftware.alexandrite.sdk.channel.ChannelDirectory
import org.unitedsoftware.alexandrite.sdk.channel.Delivery
import org.unitedsoftware.alexandrite.sdk.channel.DeliveryFailure
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChannelInstanceId
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.store.ConversationStore
import org.unitedsoftware.alexandrite.sdk.turn.ReplyRoute

/** The chats that the final reply of an initiated turn goes to. */
@Singleton
internal class Routes(
    private val directory: AgentDirectory,
    private val topology: ChatTopology,
    private val conversations: ConversationStore,
    private val channels: ChannelDirectory,
) {
    /**
     * The chats, each once, that [route] sends a reply of [agent] to, each with why the reply may not go there, null
     * where it may.
     */
    suspend fun destinations(agent: AgentId, route: ReplyRoute): Map<ChatAddress, Delivery.NotDelivered?> =
        chats(agent, route).associateWith { refusal(agent, it) }

    private suspend fun chats(agent: AgentId, route: ReplyRoute): List<ChatAddress> = when (route) {
        ReplyRoute.None -> emptyList()

        is ReplyRoute.ToChat -> listOf(route.chat)

        is ReplyRoute.AgentHomes -> instances(agent, route.channels).mapNotNull { topology.home(agent, it) }

        is ReplyRoute.Broadcast -> {
            val instances = instances(agent, route.channels).toSet()
            conversations.chats(agent)
                .flatMap { chat -> topology.group(agent, chat)?.takeIf { it.anchor == chat }?.chats ?: listOf(chat) }
                .filter { it.instance in instances }
                .distinct()
        }

        else -> {
            logger.warn("An initiated turn of agent '{}' names the unknown route {}: it is sent nowhere", agent, route)
            emptyList()
        }
    }

    /** Why a reply of [agent] may not go to [chat], null when it may. */
    private suspend fun refusal(agent: AgentId, chat: ChatAddress): Delivery.NotDelivered? {
        val detail = when {
            chat.instance !in instances(agent, null) -> "Agent '$agent' is not attached to ${chat.instance}."
            !channels.proactive(chat) -> "Chat $chat takes no messages it did not ask for."
            else -> return null
        }
        logger.warn("A routed reply of agent '{}' is not sent to {}: {}", agent, chat, detail)
        return Delivery.NotDelivered(DeliveryFailure.FORBIDDEN, detail)
    }

    /** The instances of [channels] that [agent] serves, all it serves when [channels] is null. */
    private fun instances(agent: AgentId, channels: Set<ChannelInstanceId>?): List<ChannelInstanceId> =
        directory.agent(agent)?.attachments.orEmpty().map { it.instance }.filter { channels == null || it in channels }
}

/** Whether [chat] takes messages it did not ask for, true when its channel is closed or cannot tell. */
internal suspend fun ChannelDirectory.proactive(chat: ChatAddress): Boolean = try {
    channel(chat.instance)?.capabilities(chat)?.proactive != false
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    true
}

private val logger = LoggerFactory.getLogger(Routes::class.java)
