package org.foedusprogramme.alexandrite.agent.delivery

import org.foedusprogramme.alexandrite.agent.config.AgentDirectory
import org.foedusprogramme.alexandrite.agent.routing.ChatTopology
import org.foedusprogramme.alexandrite.sdk.chat.AgentId
import org.foedusprogramme.alexandrite.sdk.chat.ChannelInstanceId
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.di.Singleton
import org.foedusprogramme.alexandrite.sdk.store.ConversationStore
import org.foedusprogramme.alexandrite.sdk.turn.ReplyRoute
import org.slf4j.LoggerFactory

/** The chats that the final reply of an initiated turn goes to. */
@Singleton
internal class Routes(
    private val directory: AgentDirectory,
    private val topology: ChatTopology,
    private val conversations: ConversationStore,
) {
    /** The chats, each once, that [route] sends a reply of [agent] to. */
    suspend fun destinations(agent: AgentId, route: ReplyRoute): List<ChatAddress> = when (route) {
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

    /** The instances of [channels] that [agent] serves, all it serves when [channels] is null. */
    private fun instances(agent: AgentId, channels: Set<ChannelInstanceId>?): List<ChannelInstanceId> =
        directory.agent(agent)?.attachments.orEmpty().map { it.instance }.filter { channels == null || it in channels }
}

private val logger = LoggerFactory.getLogger(Routes::class.java)
