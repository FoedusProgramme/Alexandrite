package org.foedusprogramme.alexandrite.testkit

import org.foedusprogramme.alexandrite.sdk.chat.AgentChatKey
import org.foedusprogramme.alexandrite.sdk.chat.AgentId
import org.foedusprogramme.alexandrite.sdk.chat.ChannelInstanceId
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.chat.ChatSettings
import org.foedusprogramme.alexandrite.sdk.chat.ChatUser
import org.foedusprogramme.alexandrite.sdk.chat.ConversationId
import org.foedusprogramme.alexandrite.sdk.chat.LanguageTag
import org.foedusprogramme.alexandrite.sdk.chat.RunId
import org.foedusprogramme.alexandrite.sdk.chat.TurnId
import org.foedusprogramme.alexandrite.sdk.turn.AgentControl
import org.foedusprogramme.alexandrite.sdk.turn.AgentDefaults
import org.foedusprogramme.alexandrite.sdk.turn.AgentSource
import org.foedusprogramme.alexandrite.sdk.turn.AgentSummary
import org.foedusprogramme.alexandrite.sdk.turn.ChatLinkGroup
import org.foedusprogramme.alexandrite.sdk.turn.ChatSettingsSnapshot
import org.foedusprogramme.alexandrite.sdk.turn.ChatSettingsUpdate
import org.foedusprogramme.alexandrite.sdk.turn.SettingChange
import org.foedusprogramme.alexandrite.sdk.turn.TrustedFolder
import org.foedusprogramme.alexandrite.sdk.turn.TurnPhase
import org.foedusprogramme.alexandrite.sdk.turn.TurnStatus
import java.nio.file.Path

/**
 * An [AgentControl] and [ChatSettings] that records its calls, lists the [statuses] the test sets, mints conversations
 * `conversation-<n>` and keeps chat settings per agent and chat, home chats per agent and instance, linked groups and
 * trusted folders, where [defaultAgent] serves every chat that is no other agent's home until an update switches it.
 */
public class RecordingAgentControl(private val defaultAgent: AgentId = AgentId.MAIN) :
    AgentControl,
    ChatSettings {
    private val lock = Any()
    private val recorded = mutableListOf<Call>()
    private val profiles = linkedMapOf(defaultAgent to Profile(defaultAgent.value, null, AgentDefaults()))
    private val selected = HashMap<ChatAddress, AgentId>()
    private val settings = HashMap<AgentChatKey, ChatSettingsSnapshot>()
    private val homes = HashMap<Pair<AgentId, ChannelInstanceId>, ChatAddress>()
    private val groups = LinkedHashMap<AgentId, List<ChatLinkGroup>>()
    private val trusted = mutableListOf<TrustedFolder>()
    private var conversations = 0

    /** The running and queued turns that [turns] lists and the cancels find. */
    @Volatile
    public var statuses: List<TurnStatus> = emptyList()

    public val calls: List<Call> get() = synchronized(lock) { recorded.toList() }

    /** Lists [agent], in its place when it is listed already, with [name], [description] and [defaults]. */
    public fun scriptAgent(
        agent: AgentId,
        name: String = agent.value,
        description: String? = null,
        defaults: AgentDefaults = AgentDefaults(),
    ) {
        synchronized(lock) { profiles[agent] = Profile(name, description, defaults) }
    }

    /** Lists [folder] as a trusted folder that the config file declares. */
    public fun declareFolder(folder: Path) {
        synchronized(lock) { trusted += TrustedFolder(folder.normalize(), true, null, null) }
    }

    override fun turns(chat: ChatAddress?): List<TurnStatus> = statuses.filter { chat == null || it.turn.chat == chat }

    /** Whether a turn at [chat] runs. */
    override fun cancel(chat: ChatAddress, by: ChatUser?): Boolean {
        record(Call.Cancel(chat, by))
        return statuses.any { it.turn.chat == chat && it.phase == TurnPhase.RUNNING }
    }

    /** Whether [turn] is listed. */
    override fun cancelTurn(turn: TurnId, by: ChatUser?): Boolean {
        record(Call.CancelTurn(turn, by))
        return statuses.any { it.turn.id == turn }
    }

    /** Whether a turn of [run] is listed. */
    override fun cancelRun(run: RunId, tree: Boolean, by: ChatUser?): Boolean {
        record(Call.CancelRun(run, tree, by))
        return statuses.any { it.turn.lineage?.run == run }
    }

    override suspend fun newConversation(chat: ChatAddress, by: ChatUser?): ConversationId = synchronized(lock) {
        ConversationId("conversation-${++conversations}").also { recorded += Call.NewConversation(chat, by, it) }
    }

    /** [defaultAgent] and the scripted agents, in order. */
    override fun agents(instance: ChannelInstanceId): List<AgentSummary> = synchronized(lock) {
        profiles.map { (id, profile) ->
            AgentSummary(id, profile.name, profile.description, id == defaultAgent, homes[id to instance])
        }
    }

    override suspend fun settings(chat: ChatAddress): ChatSettingsSnapshot = synchronized(lock) { snapshot(chat) }

    /** The language stored at [chat], else the one its agent is scripted with. */
    override suspend fun language(chat: ChatAddress): LanguageTag? = synchronized(lock) {
        snapshot(chat).let { it.language ?: it.defaults.language }
    }

    /** Refuses to make [chat] the home of its agent while it is another agent's home chat. */
    override suspend fun updateSettings(
        chat: ChatAddress,
        update: ChatSettingsUpdate,
        by: ChatUser?,
    ): ChatSettingsSnapshot = synchronized(lock) {
        recorded += Call.UpdateSettings(chat, update, by)
        val choice = when (val change = update.agent) {
            is SettingChange.SetTo -> change.value
            SettingChange.Reset -> null
            else -> selected[chat]
        }
        val agent = choice ?: homeAgent(chat) ?: defaultAgent
        val owner = homeOf(chat)
        require(update.home != true || owner == null || owner == agent) { "$chat is the home chat of agent '$owner'." }
        if (choice == null) selected.remove(chat) else selected[chat] = choice
        when (update.home) {
            true -> homes[agent to chat.instance] = chat
            false -> homes.remove(agent to chat.instance)
            null -> Unit
        }
        val key = AgentChatKey(agent, chat)
        val stored = settings[key]
        settings[key] = ChatSettingsSnapshot.builder(agent)
            .model(update.model.applyTo(stored?.model))
            .reasoning(update.reasoning.applyTo(stored?.reasoning))
            .language(update.language.applyTo(stored?.language))
            .build()
        snapshot(chat)
    }

    /** Links [member] into the group that holds [anchor], else into a new one, and refuses a member linked already. */
    override suspend fun link(agent: AgentId, anchor: ChatAddress, member: ChatAddress, by: ChatUser?): ChatLinkGroup =
        synchronized(lock) {
            recorded += Call.Link(agent, anchor, member, by)
            val groups = groups[agent].orEmpty()
            require(groups.none { member == it.anchor || member in it.members }) { "$member is linked already." }
            val group = groups.firstOrNull { anchor == it.anchor || anchor in it.members }
            val linked = ChatLinkGroup(agent, group?.anchor ?: anchor, group?.members.orEmpty() + member)
            this.groups[agent] = if (group == null) groups + linked else groups.map { if (it === group) linked else it }
            linked
        }

    /** Takes [member] out of its group and refuses an anchor. */
    override suspend fun unlink(agent: AgentId, member: ChatAddress, by: ChatUser?): Boolean = synchronized(lock) {
        recorded += Call.Unlink(agent, member, by)
        val groups = groups[agent].orEmpty()
        require(groups.none { it.anchor == member }) { "$member anchors its group." }
        val group = groups.firstOrNull { member in it.members } ?: return@synchronized false
        val members = group.members - member
        this.groups[agent] = groups.mapNotNull {
            when {
                it !== group -> it
                members.isEmpty() -> null
                else -> ChatLinkGroup(agent, group.anchor, members)
            }
        }
        true
    }

    override fun links(agent: AgentId?): List<ChatLinkGroup> = synchronized(lock) {
        groups.filterKeys { agent == null || it == agent }.values.flatten()
    }

    /**
     * Trusts [folder], normalized, at [TEST_TIME] unless a trusted folder covers it, and refuses a relative path.
     */
    override suspend fun trustFolder(folder: Path, by: ChatUser?): Path = synchronized(lock) {
        recorded += Call.TrustFolder(folder, by)
        require(folder.isAbsolute) { "Give the folder as an absolute path, was $folder." }
        val normalized = folder.normalize()
        trusted.firstOrNull { normalized.startsWith(it.path) }?.path
            ?: normalized.also { trusted += TrustedFolder(it, false, TEST_TIME, by) }
    }

    /** Stops trusting [folder] and refuses one that the config file declares. */
    override suspend fun untrustFolder(folder: Path, by: ChatUser?): Boolean = synchronized(lock) {
        recorded += Call.UntrustFolder(folder, by)
        val found = trusted.firstOrNull { it.path == folder.normalize() } ?: return@synchronized false
        require(!found.declared) { "$folder is declared in the config file." }
        trusted.remove(found)
    }

    override fun trustedFolders(): List<TrustedFolder> = synchronized(lock) { trusted.toList() }

    private fun snapshot(chat: ChatAddress): ChatSettingsSnapshot {
        val chosen = selected[chat]
        val home = homeAgent(chat)
        val (agent, source) = when {
            chosen != null -> chosen to AgentSource.SELECTED
            home != null -> home to AgentSource.HOME
            else -> defaultAgent to AgentSource.DEFAULT
        }
        val stored = settings[AgentChatKey(agent, chat)] ?: ChatSettingsSnapshot.builder(agent).build()
        return stored.toBuilder()
            .home(homes[agent to chat.instance] == chat)
            .agentSource(source)
            .defaults(profiles[agent]?.defaults ?: AgentDefaults())
            .build()
    }

    /** The agent whose home [chat] is, else the one whose home its parent is. */
    private fun homeAgent(chat: ChatAddress): AgentId? = homeOf(chat) ?: homeOf(chat.parent)

    private fun homeOf(chat: ChatAddress): AgentId? = homes.entries.firstOrNull { it.value == chat }?.key?.first

    private fun record(call: Call) {
        synchronized(lock) { recorded += call }
    }

    private class Profile(val name: String, val description: String?, val defaults: AgentDefaults)

    /** A call of the control port. */
    public sealed interface Call {
        public data class Cancel(public val chat: ChatAddress, public val by: ChatUser?) : Call

        public data class CancelTurn(public val turn: TurnId, public val by: ChatUser?) : Call

        public data class CancelRun(public val run: RunId, public val tree: Boolean, public val by: ChatUser?) : Call

        public data class NewConversation(
            public val chat: ChatAddress,
            public val by: ChatUser?,
            /** The conversation the call returned. */
            public val conversation: ConversationId,
        ) : Call

        public data class UpdateSettings(
            public val chat: ChatAddress,
            public val update: ChatSettingsUpdate,
            public val by: ChatUser?,
        ) : Call

        public data class Link(
            public val agent: AgentId,
            public val anchor: ChatAddress,
            public val member: ChatAddress,
            public val by: ChatUser?,
        ) : Call

        public data class Unlink(public val agent: AgentId, public val member: ChatAddress, public val by: ChatUser?) :
            Call

        public data class TrustFolder(public val folder: Path, public val by: ChatUser?) : Call

        public data class UntrustFolder(public val folder: Path, public val by: ChatUser?) : Call
    }
}

private fun <T : Any> SettingChange<T>.applyTo(current: T?): T? = when (this) {
    is SettingChange.SetTo -> value
    SettingChange.Reset -> null
    else -> current
}
