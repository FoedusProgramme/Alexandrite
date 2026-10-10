package org.foedusprogramme.alexandrite.agent.routing

import org.foedusprogramme.alexandrite.agent.config.AgentDirectory
import org.foedusprogramme.alexandrite.agent.i18n.Refused
import org.foedusprogramme.alexandrite.agent.i18n.TextKeys
import org.foedusprogramme.alexandrite.sdk.chat.AgentChatKey
import org.foedusprogramme.alexandrite.sdk.chat.AgentId
import org.foedusprogramme.alexandrite.sdk.chat.ChannelInstanceId
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.di.Singleton

/** Chats of one agent that share the conversation kept at [anchor]. */
internal data class LinkGroup(val anchor: ChatAddress, val members: List<ChatAddress>) {
    val chats: List<ChatAddress> get() = listOf(anchor) + members
}

/**
 * The home chats and linked groups of the agents, read without suspending.
 *
 * A change that breaks a rule throws [Refused] when it is checked.
 */
@Singleton
internal class ChatTopology(private val directory: AgentDirectory) {
    private val lock = Any()

    @Volatile
    private var current = Topology(
        directory.agents.associate { agent ->
            agent.id to
                agent.attachments.mapNotNull { attachment -> attachment.home?.let { attachment.instance to it } }
                    .toMap()
        },
        directory.agents.associate { agent ->
            agent.id to agent.linkedChats.map { LinkGroup(it.first(), it.drop(1)) }
        },
    )

    /** The agent whose home [chat] is, else the one whose home its parent is, null when it is no home chat. */
    fun homeAgent(chat: ChatAddress): AgentId? = current.homeAgent(chat)

    fun home(agent: AgentId, instance: ChannelInstanceId): ChatAddress? = current.home(agent, instance)

    fun groups(agent: AgentId): List<LinkGroup> = current.groups(agent)

    /** The group of [agent] that holds [chat], null when none does. */
    fun group(agent: AgentId, chat: ChatAddress): LinkGroup? = current.group(agent, chat)

    /** Where [agent] keeps its conversation with [chat]. */
    fun key(agent: AgentId, chat: ChatAddress): AgentChatKey = AgentChatKey(agent, group(agent, chat)?.anchor ?: chat)

    /** The change that makes [chat] the home of [agent] on [instance], or leaves the instance without one when null. */
    fun homeChange(agent: AgentId, instance: ChannelInstanceId, chat: ChatAddress?): TopologyChange {
        val topology = current
        requireAttached(agent, instance)
        if (chat != null) {
            if (chat.instance != instance) throw Refused(TextKeys.NOT_A_CHAT_OF, "chat" to chat, "instance" to instance)
            val other = topology.homeAgents[chat]
            if (other != null && other != agent) throw Refused(TextKeys.HOME_TAKEN, "chat" to chat, "agent" to other)
        }
        val homes = topology.homes[agent].orEmpty().toMutableMap()
        if (chat == null) homes.remove(instance) else homes[instance] = chat
        return TopologyChange(topology, Topology(topology.homes + (agent to homes), topology.groups))
    }

    /** The change that links [member] into the group of [chat], which [chat] anchors when it is in none. */
    fun linkChange(agent: AgentId, chat: ChatAddress, member: ChatAddress): TopologyChange {
        val topology = current
        requireAttached(agent, chat.instance)
        requireAttached(agent, member.instance)
        if (member == chat) throw Refused(TextKeys.SELF_LINK)
        val linked = topology.group(agent, member)
        if (linked != null) throw Refused(TextKeys.LINKED_ALREADY, "member" to member, "anchor" to linked.anchor)
        val group = topology.group(agent, chat)
        val grown = group?.copy(members = group.members + member) ?: LinkGroup(chat, listOf(member))
        val groups = topology.groups(agent)
        val next = if (group == null) groups + grown else groups.map { if (it == group) grown else it }
        return TopologyChange(topology, Topology(topology.homes, topology.groups + (agent to next)))
    }

    /** The change that takes [member] out of its group of [agent], null when it is in none. */
    fun unlinkChange(agent: AgentId, member: ChatAddress): TopologyChange? {
        val topology = current
        val group = topology.group(agent, member) ?: return null
        if (group.anchor == member) throw Refused(TextKeys.ANCHOR_WITH_MEMBERS, "chat" to member)
        val shrunk = group.copy(members = group.members - member)
        val next = topology.groups(agent).mapNotNull {
            if (it != group) it else shrunk.takeIf { shrunk.members.isNotEmpty() }
        }
        return TopologyChange(topology, Topology(topology.homes, topology.groups + (agent to next)))
    }

    /** Makes the topology after [change] current, or throws [IllegalStateException] when another change came first. */
    fun apply(change: TopologyChange) {
        synchronized(lock) {
            check(change.before === current) { "The chat topology changed since the change was checked." }
            current = change.after
        }
    }

    private fun requireAttached(agent: AgentId, instance: ChannelInstanceId) {
        if (directory.agent(agent)?.attachments.orEmpty().none { it.instance == instance }) {
            throw Refused(TextKeys.AGENT_NOT_ATTACHED, "agent" to agent, "instance" to instance)
        }
    }
}

/** A checked change of the topology [before] into [after]. */
internal class TopologyChange(val before: Topology, val after: Topology)

/** The home chats and linked groups of the agents at one moment. */
internal class Topology(
    val homes: Map<AgentId, Map<ChannelInstanceId, ChatAddress>>,
    val groups: Map<AgentId, List<LinkGroup>>,
) {
    val homeAgents: Map<ChatAddress, AgentId> =
        homes.flatMap { (agent, byInstance) -> byInstance.values.map { it to agent } }.toMap()

    private val groupOf: Map<AgentId, Map<ChatAddress, LinkGroup>> =
        groups.mapValues { (_, groups) -> groups.flatMap { group -> group.chats.map { it to group } }.toMap() }

    fun homeAgent(chat: ChatAddress): AgentId? = homeAgents[chat] ?: homeAgents[chat.parent]

    fun home(agent: AgentId, instance: ChannelInstanceId): ChatAddress? = homes[agent]?.get(instance)

    fun groups(agent: AgentId): List<LinkGroup> = groups[agent].orEmpty()

    fun group(agent: AgentId, chat: ChatAddress): LinkGroup? = groupOf[agent]?.get(chat)
}
