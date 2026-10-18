package org.unitedsoftware.alexandrite.agent.routing

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.agent.config.AgentDirectory
import org.unitedsoftware.alexandrite.agent.control.Granter
import org.unitedsoftware.alexandrite.agent.i18n.Refused
import org.unitedsoftware.alexandrite.agent.i18n.TextKeys
import org.unitedsoftware.alexandrite.agent.who
import org.unitedsoftware.alexandrite.sdk.chat.AgentChatKey
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChannelInstanceId
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.di.Lifecycle
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.store.Records
import org.unitedsoftware.alexandrite.sdk.store.StoredRecord
import org.unitedsoftware.alexandrite.sdk.store.collection
import org.unitedsoftware.alexandrite.sdk.turn.Revocation
import org.unitedsoftware.alexandrite.sdk.turn.SettingChange
import java.time.Instant

/** Chats of one agent that share the conversation kept at [anchor]. */
internal data class LinkGroup(
    val anchor: ChatAddress,
    val members: List<ChatAddress>,
    /** Whether the config file declares the group. */
    val declared: Boolean = false,
) {
    val chats: List<ChatAddress> get() = listOf(anchor) + members
}

/** When and by whom a home or a link was set at runtime. */
internal class Grant(
    val at: Instant,
    /** Null for the host. */
    val by: ChatUser?,
)

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

    /** The homes set at runtime by `<agent>@<instance>`, in the order they were first set. */
    @Volatile
    private var homes: Map<String, RecordedHome> = emptyMap()

    /** The links made at runtime by the key of their agent and member. */
    @Volatile
    private var links: Map<String, RecordedLink> = emptyMap()

    @Volatile
    private var current = declared

    override suspend fun onStart() {
        val homes = homeRecords.list().mapNotNull(::recordedHome)
        val links = linkRecords.list().map { RecordedLink(it.value, Grant(it.updatedAt, it.value.by?.user())) }
        writing.withLock {
            this.homes = homes.associateBy { it.key }
            this.links = links.associateBy { it.key }
            current = linked(withHomes(declared, warn = true), links)
        }
    }

    /** The agent whose home [chat] is, else the one whose home its parent is, null when it is no home chat. */
    fun homeAgent(chat: ChatAddress): AgentId? = current.homeAgent(chat)

    fun home(agent: AgentId, instance: ChannelInstanceId): ChatAddress? = current.home(agent, instance)

    fun configuredHome(agent: AgentId, instance: ChannelInstanceId): ChatAddress? = declared.home(agent, instance)

    /** The change that sets the home of [agent] on [instance] at runtime, null when the config's applies. */
    fun homeGrant(agent: AgentId, instance: ChannelInstanceId): Grant? =
        homes["$agent@$instance"]?.takeIf { it.chat == current.home(agent, instance) }?.grant

    fun groups(agent: AgentId): List<LinkGroup> = current.groups(agent)

    /** The group of [agent] that holds [chat], null when none does. */
    fun group(agent: AgentId, chat: ChatAddress): LinkGroup? = current.group(agent, chat)

    /** The link that put [member] into [group] of [agent] at runtime, null when none did. */
    fun linkGrant(agent: AgentId, group: LinkGroup, member: ChatAddress): Grant? =
        links["${AgentChatKey(agent, member)}"]?.takeIf { it.record.anchor in group.chats }?.grant

    /** Where [agent] keeps its conversation with [chat]. */
    fun key(agent: AgentId, chat: ChatAddress): AgentChatKey = AgentChatKey(agent, group(agent, chat)?.anchor ?: chat)

    /**
     * Changes the home of [agent] on the instance of [chat] for [by]: true makes [chat] the home unless it or the chat
     * of its thread is the home already, false leaves the instance without one while it is, and a reset drops the
     * home set at runtime there.
     */
    suspend fun changeHome(agent: AgentId, chat: ChatAddress, change: SettingChange<Boolean>, by: ChatUser?) {
        if (change == SettingChange.Keep) return
        val instance = chat.instance
        writing.withLock {
            requireAttached(agent, instance)
            val set = (change as? SettingChange.SetTo)?.value
            if (set == null) {
                resetHome(agent, instance, by)
            } else if (set != (current.homeAgent(chat) == agent)) {
                moveHome(agent, instance, chat.takeIf { set }, by)
            }
        }
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
            val record = LinkRecord(agent, member, grown.anchor, by?.let(::Granter))
            val stored = linkRecords.put("${AgentChatKey(agent, member)}", record, by?.address)
            links = links + RecordedLink(record, Grant(stored.updatedAt, by)).let { it.key to it }
            current = topology.withGroup(agent, group, grown)
            grown
        }

    /**
     * Takes [member] out of its group of [agent], removing its link made at runtime whenever there is one, and tells
     * whether the config still links it.
     */
    suspend fun unlink(agent: AgentId, member: ChatAddress): Revocation = writing.withLock {
        val topology = current
        val group = topology.group(agent, member)
        val runtime = group?.takeUnless { it.declared }
        if (runtime?.anchor == member) throw Refused(TextKeys.ANCHOR_WITH_MEMBERS, "chat" to member)
        val key = "${AgentChatKey(agent, member)}"
        val removed = linkRecords.remove(key)
        links = links - key
        if (runtime != null) {
            val shrunk = runtime.copy(members = runtime.members - member)
            current = topology.withGroup(agent, runtime, shrunk.takeIf { it.members.isNotEmpty() })
        }
        Revocation(removed || runtime != null, group?.declared == true)
    }

    /** Makes [chat] the home of [agent] on [instance] for [by], or none there when null, under [writing]. */
    private suspend fun moveHome(agent: AgentId, instance: ChannelInstanceId, chat: ChatAddress?, by: ChatUser?) {
        val other = chat?.let { current.homeAgents[it] }
        if (other != null && other != agent) throw Refused(TextKeys.HOME_TAKEN, "chat" to chat, "agent" to other)
        val before = current.home(agent, instance)
        if (before == chat) return
        val key = "$agent@$instance"
        homes = if (chat == declared.home(agent, instance)) {
            homeRecords.remove(key)
            homes - key
        } else {
            val stored = homeRecords.put(key, HomeRecord(agent, instance, chat, by?.let(::Granter)), by?.address)
            homes + (key to RecordedHome(agent, instance, chat, Grant(stored.updatedAt, by)))
        }
        current = withHomes(current, warn = false)
        logger.info(
            "The home chat of agent '{}' on {} is changed from {} to {} by {}",
            agent,
            instance,
            before,
            chat,
            who(by),
        )
    }

    /** Drops the home set at runtime for [agent] on [instance], if any, under [writing]. */
    private suspend fun resetHome(agent: AgentId, instance: ChannelInstanceId, by: ChatUser?) {
        val key = "$agent@$instance"
        if (key !in homes) return
        val configured = declared.home(agent, instance)
        val other = configured?.let { current.homeAgents[it] }
        if (other != null && other != agent) throw Refused(TextKeys.HOME_TAKEN, "chat" to configured, "agent" to other)
        val before = current.home(agent, instance)
        homeRecords.remove(key)
        homes = homes - key
        current = withHomes(current, warn = false)
        logger.info(
            "The home chat of agent '{}' on {} is reset from {} to the configured {} by {}",
            agent,
            instance,
            before,
            configured,
            who(by),
        )
    }

    private fun requireAttached(agent: AgentId, instance: ChannelInstanceId) {
        if (!attaches(agent, instance)) {
            throw Refused(TextKeys.AGENT_NOT_ATTACHED, "agent" to agent, "instance" to instance)
        }
    }

    private fun attaches(agent: AgentId, instance: ChannelInstanceId): Boolean =
        directory.agent(agent)?.attachments.orEmpty().any { it.instance == instance }

    /** The home that [record] sets, null with a warning when the config no longer allows it. */
    private fun recordedHome(record: StoredRecord<HomeRecord>): RecordedHome? {
        val home = record.value
        val problem = when {
            !attaches(home.agent, home.instance) -> "agent '${home.agent}' does not serve ${home.instance}"
            home.chat != null && home.chat.instance != home.instance -> "it is no chat of ${home.instance}"
            else -> return RecordedHome(home.agent, home.instance, home.chat, Grant(record.updatedAt, home.by?.user()))
        }
        logger.warn("The home chat {} of agent '{}' is ignored: {}", home.chat, home.agent, problem)
        return null
    }

    /** [topology] with the configured homes and, over them, the homes set at runtime. */
    private fun withHomes(topology: Topology, warn: Boolean): Topology {
        val result = declared.homes.mapValues { it.value.toMutableMap() }.toMutableMap()
        val taken = HashMap<ChatAddress, AgentId>()
        for (home in homes.values) {
            val other = home.chat?.let(taken::get)
            if (other != null) {
                if (warn) {
                    logger.warn("The home chat {} of agent '{}' is ignored: '{}' has it", home.chat, home.agent, other)
                }
                continue
            }
            val byInstance = result.getOrPut(home.agent) { mutableMapOf() }
            if (home.chat == null) byInstance.remove(home.instance) else byInstance[home.instance] = home.chat
            home.chat?.let { taken[it] = home.agent }
        }
        for ((agent, byInstance) in result) {
            byInstance.entries.removeIf { (instance, chat) ->
                val owner = taken[chat]
                val lost = owner != null && owner != agent
                if (lost && warn) {
                    logger.warn(
                        "The configured home chat {} of agent '{}' is the home chat of agent '{}' now: '{}' has no " +
                            "home chat on {}",
                        chat,
                        agent,
                        owner,
                        agent,
                        instance,
                    )
                }
                lost
            }
        }
        return Topology(result, topology.groups)
    }

    /** [topology] with the [links] that still fit it applied in order. */
    private fun linked(topology: Topology, links: List<RecordedLink>): Topology {
        var result = topology
        for (recorded in links) {
            val link = recorded.record
            val group = result.group(link.agent, link.anchor)
            val problem = when {
                !attaches(link.agent, link.member.instance) || !attaches(link.agent, link.anchor.instance) ->
                    "agent '${link.agent}' does not serve both chats"

                link.member == link.anchor -> "it is linked to itself"

                result.group(link.agent, link.member) != null -> "it is in another group"

                group != null && (group.declared || group.anchor != link.anchor) -> "${link.anchor} is in another group"

                else -> null
            }
            if (problem == null) {
                val grown = group?.copy(members = group.members + link.member)
                    ?: LinkGroup(link.anchor, listOf(link.member))
                result = result.withGroup(link.agent, group, grown)
            } else {
                val (member, anchor, agent) = Triple(link.member, link.anchor, link.agent)
                logger.warn("The link of {} to {} at agent '{}' is ignored: {}", member, anchor, agent, problem)
            }
        }
        return result
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

/** The home chat of [agent] on [instance] set at runtime by [by], null for none. */
@Serializable
private class HomeRecord(
    val agent: AgentId,
    val instance: ChannelInstanceId,
    val chat: ChatAddress? = null,
    val by: Granter? = null,
)

/** [member] linked at runtime into the group of [anchor] at [agent] by [by]. */
@Serializable
private class LinkRecord(val agent: AgentId, val member: ChatAddress, val anchor: ChatAddress, val by: Granter? = null)

private class RecordedHome(
    val agent: AgentId,
    val instance: ChannelInstanceId,
    val chat: ChatAddress?,
    val grant: Grant,
) {
    val key: String get() = "$agent@$instance"
}

private class RecordedLink(val record: LinkRecord, val grant: Grant) {
    val key: String get() = "${AgentChatKey(record.agent, record.member)}"
}

private const val HOMES = "homes"

private const val LINKS = "links"

private val logger = LoggerFactory.getLogger(ChatTopology::class.java)
