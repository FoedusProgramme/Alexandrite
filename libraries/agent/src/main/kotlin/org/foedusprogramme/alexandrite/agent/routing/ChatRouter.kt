package org.foedusprogramme.alexandrite.agent.routing

import org.foedusprogramme.alexandrite.agent.config.Agent
import org.foedusprogramme.alexandrite.agent.config.AgentDirectory
import org.foedusprogramme.alexandrite.agent.control.SettingsStates
import org.foedusprogramme.alexandrite.sdk.channel.ChannelDirectory
import org.foedusprogramme.alexandrite.sdk.chat.AgentChatKey
import org.foedusprogramme.alexandrite.sdk.chat.AgentId
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.di.Singleton
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/** Which agent serves a chat. */
internal sealed interface Resolution {
    data class Served(val key: AgentChatKey) : Resolution

    /** The chat's channel instance is not configured. */
    data object Unknown : Resolution

    /** No agent serves the chat's channel instance. */
    data object NoAgent : Resolution
}

/** Finds the agent of a chat: its home agent, else the agent the chat switched to, else its instance's default. */
@Singleton
internal class ChatRouter(
    private val channels: ChannelDirectory,
    private val directory: AgentDirectory,
    private val settings: SettingsStates,
) {
    private val warned = ConcurrentHashMap.newKeySet<ChatAddress>()

    fun resolve(chat: ChatAddress): Resolution {
        if (chat.instance !in channels.instances) return Resolution.Unknown
        val attached = directory.attached(chat.instance)
        if (attached.isEmpty()) return Resolution.NoAgent
        val agent = directory.home(chat)?.id
            ?: selected(chat, attached)
            ?: checkNotNull(directory.default(chat.instance)).id
        return Resolution.Served(AgentChatKey(agent, chat))
    }

    /** The agent [chat] switched to, null when it is none of [attached] or only one agent is attached. */
    private fun selected(chat: ChatAddress, attached: List<Agent>): AgentId? {
        if (attached.size < 2) return null
        val selected = settings.selectedAgent(chat) ?: return null
        if (attached.any { it.id == selected }) return selected
        if (warned.add(chat)) {
            logger.warn(
                "Chat {} switched to agent '{}', which no longer serves {}: the chat goes to its default agent",
                chat,
                selected,
                chat.instance,
            )
        }
        return null
    }
}

private val logger = LoggerFactory.getLogger(ChatRouter::class.java)
