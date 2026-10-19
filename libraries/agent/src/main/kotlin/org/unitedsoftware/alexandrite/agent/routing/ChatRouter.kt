package org.unitedsoftware.alexandrite.agent.routing

import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.agent.config.Agent
import org.unitedsoftware.alexandrite.agent.config.AgentDirectory
import org.unitedsoftware.alexandrite.agent.control.Choice
import org.unitedsoftware.alexandrite.agent.control.SettingsStates
import org.unitedsoftware.alexandrite.sdk.channel.ChannelDirectory
import org.unitedsoftware.alexandrite.sdk.chat.AgentChatKey
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.AgentSource
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import java.util.concurrent.ConcurrentHashMap

/** Which agent serves a chat. */
internal sealed interface Resolution {
    /** Served at [key] for the chat [origin], which the turn came from. */
    data class Served(val key: AgentChatKey, val origin: ChatAddress, val source: AgentSource) : Resolution

    /** The chat's channel instance is not configured. */
    data object Unknown : Resolution

    /** No agent serves the chat's channel instance. */
    data object NoAgent : Resolution
}

/** Finds the agent of a chat: the one it switched to, else its home agent, else its instance's default. */
@Singleton
internal class ChatRouter(
    private val channels: ChannelDirectory,
    private val directory: AgentDirectory,
    private val topology: ChatTopology,
    private val settings: SettingsStates,
) {
    private val warned = ConcurrentHashMap.newKeySet<ChatAddress>()

    /** How [chat] resolves from memory, null while the agent it switched to is not read yet. */
    fun cached(chat: ChatAddress): Resolution? {
        unserved(chat)?.let { return it }
        val attached = directory.attached(chat.instance)
        val choice = if (attached.size < 2) Choice() else settings.cachedAgent(chat) ?: return null
        return served(chat, attached, choice.value)
    }

    /** How [chat] resolves, reading the agent it switched to the first time. */
    suspend fun resolve(chat: ChatAddress): Resolution {
        unserved(chat)?.let { return it }
        val attached = directory.attached(chat.instance)
        val choice = if (attached.size < 2) Choice() else settings.loadAgent(chat)
        return served(chat, attached, choice.value)
    }

    /** How [chat] resolves where it switched to [selected], or to no agent when it is null. */
    fun resolveAs(chat: ChatAddress, selected: AgentId?): Resolution =
        unserved(chat) ?: served(chat, directory.attached(chat.instance), selected)

    private fun unserved(chat: ChatAddress): Resolution? = when {
        chat.instance !in channels.instances -> Resolution.Unknown
        directory.attached(chat.instance).isEmpty() -> Resolution.NoAgent
        else -> null
    }

    private fun served(chat: ChatAddress, attached: List<Agent>, selected: AgentId?): Resolution.Served {
        val chosen = selected?.let { attachedOrWarn(chat, attached, it) }
        val home = topology.homeAgent(chat)
        val (agent, source) = when {
            chosen != null -> chosen to AgentSource.SELECTED
            home != null -> home to AgentSource.HOME
            else -> checkNotNull(directory.default(chat.instance)).id to AgentSource.DEFAULT
        }
        return Resolution.Served(topology.key(agent, chat), chat, source)
    }

    /** [selected], null when it no longer serves [chat]'s instance. */
    private fun attachedOrWarn(chat: ChatAddress, attached: List<Agent>, selected: AgentId): AgentId? {
        if (attached.any { it.id == selected }) return selected
        if (warned.add(chat)) {
            logger.warn(
                "Chat {} switched to agent '{}', which no longer serves {}: the chat goes to its home or default agent",
                chat,
                selected,
                chat.instance,
            )
        }
        return null
    }
}

private val logger = LoggerFactory.getLogger(ChatRouter::class.java)
