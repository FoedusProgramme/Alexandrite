package org.foedusprogramme.alexandrite.agent.routing

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import org.foedusprogramme.alexandrite.agent.config.AgentDirectory
import org.foedusprogramme.alexandrite.agent.i18n.Refused
import org.foedusprogramme.alexandrite.agent.i18n.TextKeys
import org.foedusprogramme.alexandrite.sdk.chat.AgentChatKey
import org.foedusprogramme.alexandrite.sdk.chat.AgentId
import org.foedusprogramme.alexandrite.sdk.chat.ChannelInstanceId
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.chat.ChatUser
import org.foedusprogramme.alexandrite.sdk.di.Lifecycle
import org.foedusprogramme.alexandrite.sdk.di.Singleton
import org.foedusprogramme.alexandrite.sdk.store.Records
import org.foedusprogramme.alexandrite.sdk.store.collection
import org.slf4j.LoggerFactory

/** Chats of one agent that share the conversation kept at [anchor]. */
internal data class LinkGroup(
    val anchor: ChatAddress,
    val members: List<ChatAddress>,
    /** Whether the config file declares the group. */
    val declared: Boolean = false,
) {
    val chats: List<ChatAddress> get() = listOf(anchor) + members
}

/**
 * The home chats and linked groups that the config declares, with the changes made at runtime, read without
 * suspending.
 *
 * A change that breaks a rule throws [Refused].
 */
@Singleton
internal class ChatTopology(private val directory: AgentDirectory, records: Records) : Lifecycle {
    private val homeRecords = records.collection<HomeRecord>(HOMES)
    private val linkRecords = records.collection<LinkRecord>(LINKS)

    /** Held from checking a change until it is applied. */
    private val writing = Mutex()

    private val declared = Topology(
        directory.agents.associate { agent ->
            agent.id to
                agent.attachments.mapNotNull { attachment -> attachment.home?.let { attachment.instance to it } }
                    .toMap()
        },
        directory.agents.associate { agent ->
            agent.id to agent.linkedChats.map { LinkGroup(it.first(), it.drop(1), declared = true) }
        },
    )

    @Volatile
    private var current = declared

    override suspend fun onStart() {
        val homes = homeRecords.list().map { it.value }
        val links = linkRecords.list().map { it.value }
        writing.withLock { current = recorded(homes, links) }
    }

    /** The agent whose home [chat] is, else the one whose home its parent is, null when it is no home chat. */
    fun homeAgent(chat: ChatAddress): AgentId? = current.homeAgent(chat)

    fun home(agent: AgentId, instance: ChannelInstanceId): ChatAddress? = current.home(agent, instance)

    fun groups(agent: AgentId): List<LinkGroup> = current.groups(agent)

    /** The group of [agent] that holds [chat], null when none does. */
    fun group(agent: AgentId, chat: ChatAddress): LinkGroup? = current.group(agent, chat)

    /** Where [agent] keeps its conversation with [chat]. */
    fun key(agent: AgentId, chat: ChatAddress): AgentChatKey = AgentChatKey(agent, group(agent, chat)?.anchor ?: chat)

    /** Makes [chat] the home of [agent] on [instance] for [by], or leaves the instance without one when null. */
    suspend fun setHome(agent: AgentId, instance: ChannelInstanceId, chat: ChatAddress?, by: ChatUser?) {
        val before = writing.withLock {
            val topology = current
            requireAttached(agent, instance)
            if (chat != null) {
                if (chat.instance != instance) {
                    throw Refused(TextKeys.NOT_A_CHAT_OF, "chat" to chat, "instance" to instance)
                }
                val other = topology.homeAgents[chat]
                if (other != null && other != agent) {
                    throw Refused(TextKeys.HOME_TAKEN, "chat" to chat, "agent" to other)
                }
            }
            val before = topology.home(agent, instance)
            if (before == chat) return
            val key = "$agent@$instance"
            if (chat == declared.home(agent, instance)) {
                homeRecords.remove(key)
            } else {
                homeRecords.put(key, HomeRecord(agent, instance, chat), by?.address)
            }
            current = topology.withHome(agent, instance, chat)
            before
        }
        logger.info(
            "The home chat of agent '{}' on {} is changed from {} to {} by {}",
            agent,
            instance,
            before,
            chat,
            who(by),
        )
    }

    /** Links [member] into the group of [chat] for [by], which [chat] anchors when it is in none, and returns it. */
    suspend fun link(agent: AgentId, chat: ChatAddress, member: ChatAddress, by: ChatUser?): LinkGroup =
        writing.withLock {
            val topology = current
            requireAttached(agent, chat.instance)
            requireAttached(agent, member.instance)
            if (member == chat) throw Refused(TextKeys.SELF_LINK)
            val linked = topology.group(agent, member)
            if (linked?.declared == true) throw Refused(TextKeys.LINK_DECLARED, "chat" to member)
            if (linked != null) throw Refused(TextKeys.LINKED_ALREADY, "member" to member, "anchor" to linked.anchor)
            val group = topology.group(agent, chat)
            if (group?.declared == true) throw Refused(TextKeys.LINK_DECLARED, "chat" to chat)
            val grown = group?.copy(members = group.members + member) ?: LinkGroup(chat, listOf(member))
            linkRecords.put("${AgentChatKey(agent, member)}", LinkRecord(agent, member, grown.anchor), by?.address)
            current = topology.withGroup(agent, group, grown)
            grown
        }

    /** Takes [member] out of its group of [agent], false when it is in none. */
    suspend fun unlink(agent: AgentId, member: ChatAddress): Boolean = writing.withLock {
        val topology = current
        val group = topology.group(agent, member) ?: return false
        if (group.declared) throw Refused(TextKeys.LINK_DECLARED, "chat" to member)
        if (group.anchor == member) throw Refused(TextKeys.ANCHOR_WITH_MEMBERS, "chat" to member)
        linkRecords.remove("${AgentChatKey(agent, member)}")
        val shrunk = group.copy(members = group.members - member)
        current = topology.withGroup(agent, group, shrunk.takeIf { it.members.isNotEmpty() })
        true
    }

    private fun requireAttached(agent: AgentId, instance: ChannelInstanceId) {
        if (!attaches(agent, instance)) {
            throw Refused(TextKeys.AGENT_NOT_ATTACHED, "agent" to agent, "instance" to instance)
        }
    }

    private fun attaches(agent: AgentId, instance: ChannelInstanceId): Boolean =
        directory.agent(agent)?.attachments.orEmpty().any { it.instance == instance }

    /** The declared topology with the [homes] and [links] that still fit it applied in order. */
    private fun recorded(homes: List<HomeRecord>, links: List<LinkRecord>): Topology {
        var topology = declared
        for (home in homes) {
            val other = home.chat?.let { topology.homeAgents[it] }
            val problem = when {
                !attaches(home.agent, home.instance) -> "agent '${home.agent}' does not serve ${home.instance}"
                home.chat != null && home.chat.instance != home.instance -> "it is no chat of ${home.instance}"
                other != null && other != home.agent -> "it is the home chat of agent '$other'"
                else -> null
            }
            if (problem == null) {
                topology = topology.withHome(home.agent, home.instance, home.chat)
            } else {
                logger.warn("The home chat {} of agent '{}' is ignored: {}", home.chat, home.agent, problem)
            }
        }
        for (link in links) {
            val group = topology.group(link.agent, link.anchor)
            val problem = when {
                !attaches(link.agent, link.member.instance) || !attaches(link.agent, link.anchor.instance) ->
                    "agent '${link.agent}' does not serve both chats"

                link.member == link.anchor -> "it is linked to itself"

                topology.group(link.agent, link.member) != null -> "it is in another group"

                group != null && (group.declared || group.anchor != link.anchor) -> "${link.anchor} is in another group"

                else -> null
            }
            if (problem == null) {
                val grown = group?.copy(members = group.members + link.member)
                    ?: LinkGroup(link.anchor, listOf(link.member))
                topology = topology.withGroup(link.agent, group, grown)
            } else {
                val (member, anchor, agent) = Triple(link.member, link.anchor, link.agent)
                logger.warn("The link of {} to {} at agent '{}' is ignored: {}", member, anchor, agent, problem)
            }
        }
        return topology
    }
}

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

    /** This topology with [chat] as the home of [agent] on [instance], or none there when null. */
    fun withHome(agent: AgentId, instance: ChannelInstanceId, chat: ChatAddress?): Topology {
        val byInstance = homes[agent].orEmpty().toMutableMap()
        if (chat == null) byInstance.remove(instance) else byInstance[instance] = chat
        return Topology(homes + (agent to byInstance), groups)
    }

    /** This topology with the group [before] of [agent] replaced by [after], which is added when [before] is null. */
    fun withGroup(agent: AgentId, before: LinkGroup?, after: LinkGroup?): Topology {
        val current = groups(agent)
        val next = if (before == null) {
            current + listOfNotNull(after)
        } else {
            current.mapNotNull { if (it == before) after else it }
        }
        return Topology(homes, groups + (agent to next))
    }
}

/** The home chat of [agent] on [instance] set at runtime, null for none. */
@Serializable
private class HomeRecord(val agent: AgentId, val instance: ChannelInstanceId, val chat: ChatAddress? = null)

/** [member] linked at runtime into the group of [anchor] at [agent]. */
@Serializable
private class LinkRecord(val agent: AgentId, val member: ChatAddress, val anchor: ChatAddress)

private fun who(by: ChatUser?): String = by?.address?.toString() ?: "the host"

private const val HOMES = "homes"

private const val LINKS = "links"

private val logger = LoggerFactory.getLogger(ChatTopology::class.java)
