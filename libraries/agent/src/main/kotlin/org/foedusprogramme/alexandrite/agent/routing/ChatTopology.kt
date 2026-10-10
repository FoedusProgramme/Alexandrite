package org.foedusprogramme.alexandrite.agent.routing

import org.foedusprogramme.alexandrite.agent.config.AgentDirectory
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
 * A change that breaks a rule throws [IllegalArgumentException] with a message for the user and changes nothing.
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
    fun homeAgent(chat: ChatAddress): AgentId? = current.let { it.homeAgents[chat] ?: it.homeAgents[chat.parent] }

    fun home(agent: AgentId, instance: ChannelInstanceId): ChatAddress? = current.homes[agent]?.get(instance)

    fun groups(agent: AgentId): List<LinkGroup> = current.groups[agent].orEmpty()

    /** The group of [agent] that holds [chat], null when none does. */
    fun group(agent: AgentId, chat: ChatAddress): LinkGroup? = current.groupOf[agent]?.get(chat)

    /** Where [agent] keeps its conversation with [chat]. */
    fun key(agent: AgentId, chat: ChatAddress): AgentChatKey = AgentChatKey(agent, group(agent, chat)?.anchor ?: chat)

    /** Makes [chat] the home of [agent] on [instance], or leaves the instance without one when [chat] is null. */
    fun setHome(agent: AgentId, instance: ChannelInstanceId, chat: ChatAddress?) {
        synchronized(lock) {
            val topology = current
            requireAttached(agent, instance)
            if (chat != null) {
                require(chat.instance == instance) { "$chat is no chat of $instance." }
                val other = topology.homeAgents[chat]
                require(other == null || other == agent) { "$chat is the home chat of agent '$other'." }
            }
            val homes = topology.homes[agent].orEmpty().toMutableMap()
            if (chat == null) homes.remove(instance) else homes[instance] = chat
            current = Topology(topology.homes + (agent to homes), topology.groups)
        }
    }

    /** Links [member] into the group of [chat], which [chat] anchors when it is in none, and returns that group. */
    fun link(agent: AgentId, chat: ChatAddress, member: ChatAddress): LinkGroup = synchronized(lock) {
        val topology = current
        requireAttached(agent, chat.instance)
        requireAttached(agent, member.instance)
        require(member != chat) { "A chat cannot be linked to itself." }
        val linked = topology.groupOf[agent]?.get(member)
        require(linked == null) { "$member is linked to ${linked?.anchor} already: unlink it first." }
        val group = topology.groupOf[agent]?.get(chat)
        val grown = group?.copy(members = group.members + member) ?: LinkGroup(chat, listOf(member))
        val groups = topology.groups[agent].orEmpty()
        val next = if (group == null) groups + grown else groups.map { if (it == group) grown else it }
        current = Topology(topology.homes, topology.groups + (agent to next))
        grown
    }

    /** Takes [member] out of its group of [agent], and returns whether it was in one. */
    fun unlink(agent: AgentId, member: ChatAddress): Boolean = synchronized(lock) {
        val topology = current
        val group = topology.groupOf[agent]?.get(member) ?: return false
        require(group.anchor != member) { "$member anchors its group: unlink the other chats first." }
        val shrunk = group.copy(members = group.members - member)
        val next = topology.groups.getValue(agent).mapNotNull {
            if (it != group) it else shrunk.takeIf { shrunk.members.isNotEmpty() }
        }
        current = Topology(topology.homes, topology.groups + (agent to next))
        true
    }

    private fun requireAttached(agent: AgentId, instance: ChannelInstanceId) {
        require(directory.agent(agent)?.attachments.orEmpty().any { it.instance == instance }) {
            "Agent '$agent' does not serve $instance."
        }
    }
}

private class Topology(
    val homes: Map<AgentId, Map<ChannelInstanceId, ChatAddress>>,
    val groups: Map<AgentId, List<LinkGroup>>,
) {
    val homeAgents: Map<ChatAddress, AgentId> =
        homes.flatMap { (agent, byInstance) -> byInstance.values.map { it to agent } }.toMap()

    val groupOf: Map<AgentId, Map<ChatAddress, LinkGroup>> =
        groups.mapValues { (_, groups) -> groups.flatMap { group -> group.chats.map { it to group } }.toMap() }
}
