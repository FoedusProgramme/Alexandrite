package org.foedusprogramme.alexandrite.testkit

import dev.drewhamilton.poko.Poko
import org.foedusprogramme.alexandrite.sdk.InternalAlexandriteApi
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
import org.foedusprogramme.alexandrite.sdk.model.ModelInfo
import org.foedusprogramme.alexandrite.sdk.model.ReasoningEffort
import org.foedusprogramme.alexandrite.sdk.transcript.EndpointId
import org.foedusprogramme.alexandrite.sdk.transcript.ModelRef
import org.foedusprogramme.alexandrite.sdk.turn.AgentControl
import org.foedusprogramme.alexandrite.sdk.turn.AgentSource
import org.foedusprogramme.alexandrite.sdk.turn.AgentSummary
import org.foedusprogramme.alexandrite.sdk.turn.ChatLink
import org.foedusprogramme.alexandrite.sdk.turn.ChatLinkGroup
import org.foedusprogramme.alexandrite.sdk.turn.ChatSettingsSnapshot
import org.foedusprogramme.alexandrite.sdk.turn.ChatSettingsUpdate
import org.foedusprogramme.alexandrite.sdk.turn.ControlRefusedException
import org.foedusprogramme.alexandrite.sdk.turn.Revocation
import org.foedusprogramme.alexandrite.sdk.turn.SettingChange
import org.foedusprogramme.alexandrite.sdk.turn.TrustedFolder
import org.foedusprogramme.alexandrite.sdk.turn.TurnPhase
import org.foedusprogramme.alexandrite.sdk.turn.TurnStatus
import java.nio.file.Path

/**
 * An [AgentControl] and [ChatSettings] that records its calls, lists the [statuses] the test sets, mints conversations
 * `conversation-<n>` and keeps chat settings per agent and chat, home chats per agent and instance, linked groups and
 * trusted folders, where [defaultAgent] serves every chat that is no other agent's home until an update switches it.
 *
 * Homes, groups and folders that the test declares stand for the config file's, and those changed at runtime over them
 * are set at [TEST_TIME]. It refuses with a [ControlRefusedException] what the agent refuses for the reasons it knows,
 * and the calls that [refuseNext] queues refusals for.
 */
public class RecordingAgentControl(private val defaultAgent: AgentId = AgentId.MAIN) :
    AgentControl,
    ChatSettings {
    private val lock = Any()
    private val recorded = mutableListOf<Call>()
    private val profiles = linkedMapOf(defaultAgent to Profile(defaultAgent.value, null, null, null, null))
    private val selected = HashMap<ChatAddress, AgentId>()
    private val settings = HashMap<AgentChatKey, ChatSettingsSnapshot>()
    private val configuredHomes = HashMap<Pair<AgentId, ChannelInstanceId>, ChatAddress>()
    private val runtimeHomes = LinkedHashMap<Pair<AgentId, ChannelInstanceId>, RuntimeHome>()
    private val groups = LinkedHashMap<AgentId, List<ChatLinkGroup>>()
    private val trusted = mutableListOf<TrustedFolder>()
    private val endpointModels = LinkedHashMap<EndpointId, List<ModelInfo>>()
    private val refusals = ArrayDeque<String>()
    private var conversations = 0

    /** The running and queued turns that [turns] lists and the cancels find. */
    @Volatile
    public var statuses: List<TurnStatus> = emptyList()

    public val calls: List<Call> get() = synchronized(lock) { recorded.toList() }

    /**
     * Lists [agent], in its place when it is listed already, with [name], [description] and the [model], [reasoning]
     * and [language] it is configured with.
     */
    public fun scriptAgent(
        agent: AgentId,
        name: String = agent.value,
        description: String? = null,
        model: ModelRef? = null,
        reasoning: ReasoningEffort? = null,
        language: LanguageTag? = null,
    ) {
        synchronized(lock) { profiles[agent] = Profile(name, description, model, reasoning, language) }
    }

    /** Lists [endpoint], in its place when it is listed already, with [models]. */
    public fun scriptModels(endpoint: EndpointId, models: List<ModelInfo>) {
        synchronized(lock) { endpointModels[endpoint] = models.toList() }
    }

    /**
     * Makes the next call that changes something, of [newConversation], [updateSettings], [link], [unlink],
     * [trustFolder] or [untrustFolder], record itself and throw [ControlRefusedException] with [message].
     */
    public fun refuseNext(message: String) {
        synchronized(lock) { refusals.addLast(message) }
    }

    /** Lists [folder] as a trusted folder that the config file declares. */
    public fun declareFolder(folder: Path) {
        synchronized(lock) { trusted += TrustedFolder(folder.normalize(), true, null, null) }
    }

    /** Makes [chat] the home chat of [agent] on its instance that the config file declares. */
    public fun declareHome(agent: AgentId, chat: ChatAddress) {
        synchronized(lock) { configuredHomes[agent to chat.instance] = chat }
    }

    /** Lists the group of [anchor] and [members] at [agent] as one that the config file declares. */
    public fun declareLink(agent: AgentId, anchor: ChatAddress, members: List<ChatAddress>) {
        synchronized(lock) {
            groups[agent] = groups[agent].orEmpty() + ChatLinkGroup(agent, anchor, members.toList(), declared = true)
        }
    }

    override suspend fun turns(chat: ChatAddress?): List<TurnStatus> =
        statuses.filter { chat == null || it.turn.chat == chat }

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
        refusals.removeFirstOrNull()?.let { message ->
            recorded += Call.NewConversation(chat, by, null)
            throw ControlRefusedException(message)
        }
        ConversationId("conversation-${++conversations}").also { recorded += Call.NewConversation(chat, by, it) }
    }

    /** [defaultAgent] and the scripted agents, in order. */
    override fun agents(instance: ChannelInstanceId): List<AgentSummary> = synchronized(lock) {
        val homes = homes()
        profiles.map { (id, profile) ->
            val runtime = runtimeHomes[id to instance]
            AgentSummary(
                id,
                profile.name,
                profile.description,
                id == defaultAgent,
                homes[id to instance],
                configuredHomes[id to instance],
                runtime?.let { TEST_TIME },
                runtime?.by,
            )
        }
    }

    override suspend fun settings(chat: ChatAddress): ChatSettingsSnapshot = synchronized(lock) { snapshot(chat) }

    /** The language stored at [chat], else the one its agent is scripted with. */
    override suspend fun language(chat: ChatAddress): LanguageTag? = synchronized(lock) {
        snapshot(chat).effectiveLanguage
    }

    /**
     * Refuses an agent that is not listed and a home chat of another agent, and changes homes as the agent does: true
     * while [chat] is not the home, false while it is, and a reset from any chat of the instance.
     */
    override suspend fun updateSettings(
        chat: ChatAddress,
        update: ChatSettingsUpdate,
        by: ChatUser?,
    ): ChatSettingsSnapshot = synchronized(lock) {
        recorded += Call.UpdateSettings(chat, update, by)
        refuseQueued()
        val choice = when (val change = update.agent) {
            is SettingChange.SetTo -> change.value
            SettingChange.Reset -> null
            else -> selected[chat]
        }
        refuseIf(choice != null && choice !in profiles) { "Agent '$choice' does not serve ${chat.instance}." }
        val agent = choice ?: homeAgent(chat) ?: defaultAgent
        val instance = agent to chat.instance
        val home = homeAgent(chat) == agent
        val set = (update.home as? SettingChange.SetTo)?.value
        val target = when {
            set == true && !home -> chat
            update.home == SettingChange.Reset && instance in runtimeHomes -> configuredHomes[instance]
            else -> null
        }
        val owner = target?.let(::homeOf)
        refuseIf(owner != null && owner != agent) { "$target is the home chat of agent '$owner'." }
        if (choice == null) selected.remove(chat) else selected[chat] = choice
        when {
            update.home == SettingChange.Reset -> runtimeHomes.remove(instance)
            set == true && !home -> setHome(instance, chat, by)
            set == false && home -> setHome(instance, null, by)
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

    /**
     * Links [member] into the group that holds [anchor], else into a new one, and refuses a member linked already and a
     * declared group.
     */
    override suspend fun link(agent: AgentId, anchor: ChatAddress, member: ChatAddress, by: ChatUser?): ChatLinkGroup =
        synchronized(lock) {
            recorded += Call.Link(agent, anchor, member, by)
            val groups = groups[agent].orEmpty()
            refuseQueued()
            refuseIf(groups.any { member == it.anchor || member in it.members }) { "$member is linked already." }
            val group = groups.firstOrNull { anchor == it.anchor || anchor in it.members }
            refuseIf(group?.declared == true) { "$anchor is in a group that the config file declares." }
            val linked = ChatLinkGroup(
                agent,
                group?.anchor ?: anchor,
                group?.members.orEmpty() + member,
                links = group?.links.orEmpty() + (member to ChatLink(TEST_TIME, by)),
            )
            this.groups[agent] = if (group == null) groups + linked else groups.map { if (it === group) linked else it }
            linked
        }

    /** Takes [member] out of its group unless the group is declared, and refuses the anchor of a runtime group. */
    override suspend fun unlink(agent: AgentId, member: ChatAddress, by: ChatUser?): Revocation = synchronized(lock) {
        recorded += Call.Unlink(agent, member, by)
        refuseQueued()
        val groups = groups[agent].orEmpty()
        val group = groups.firstOrNull { member == it.anchor || member in it.members }
            ?: return@synchronized Revocation(removed = false, declared = false)
        if (group.declared) return@synchronized Revocation(removed = false, declared = true)
        refuseIf(group.anchor == member) { "$member anchors its group." }
        val members = group.members - member
        this.groups[agent] = groups.mapNotNull {
            when {
                it !== group -> it
                members.isEmpty() -> null
                else -> ChatLinkGroup(agent, group.anchor, members, links = group.links - member)
            }
        }
        Revocation(removed = true, declared = false)
    }

    override fun links(agent: AgentId?): List<ChatLinkGroup> = synchronized(lock) {
        groups.filterKeys { agent == null || it == agent }.values.flatten()
    }

    /**
     * Trusts [folder], normalized, at [TEST_TIME] unless a trusted folder covers it, and refuses a relative path.
     */
    override suspend fun trustFolder(folder: Path, by: ChatUser?): Path = synchronized(lock) {
        recorded += Call.TrustFolder(folder, by)
        refuseQueued()
        refuseIf(!folder.isAbsolute) { "Give the folder as an absolute path, was $folder." }
        val normalized = folder.normalize()
        trusted.firstOrNull { normalized.startsWith(it.path) }?.path
            ?: normalized.also { trusted += TrustedFolder(it, false, TEST_TIME, by) }
    }

    /** Stops trusting [folder] unless the config file declares it, and refuses a relative path. */
    override suspend fun untrustFolder(folder: Path, by: ChatUser?): Revocation = synchronized(lock) {
        recorded += Call.UntrustFolder(folder, by)
        refuseQueued()
        refuseIf(!folder.isAbsolute) { "Give the folder as an absolute path, was $folder." }
        val found = trusted.firstOrNull { it.path == folder.normalize() }
        if (found != null && !found.declared) trusted.remove(found)
        Revocation(removed = found?.declared == false, declared = found?.declared == true)
    }

    override fun trustedFolders(): List<TrustedFolder> = synchronized(lock) { trusted.toList() }

    /** The endpoints that [scriptModels] listed, in order. */
    override fun endpoints(): List<EndpointId> = synchronized(lock) { endpointModels.keys.toList() }

    /** The models scripted for [endpoint], and refuses an endpoint that is not listed. */
    override suspend fun models(endpoint: EndpointId): List<ModelInfo> = synchronized(lock) {
        endpointModels[endpoint] ?: throw ControlRefusedException("No model endpoint '$endpoint'.")
    }

    private fun snapshot(chat: ChatAddress): ChatSettingsSnapshot {
        val chosen = selected[chat]
        val home = homeAgent(chat)
        val (agent, source) = when {
            chosen != null -> chosen to AgentSource.SELECTED
            home != null -> home to AgentSource.HOME
            else -> defaultAgent to AgentSource.DEFAULT
        }
        val stored = settings[AgentChatKey(agent, chat)] ?: ChatSettingsSnapshot.builder(agent).build()
        val profile = profiles[agent]
        return stored.toBuilder()
            .home(home == agent)
            .agentSource(source)
            .defaultModel(profile?.model)
            .defaultReasoning(profile?.reasoning)
            .defaultLanguage(profile?.language)
            .build()
    }

    /** The homes changed at runtime, and the configured ones that none of them changed or took. */
    private fun homes(): Map<Pair<AgentId, ChannelInstanceId>, ChatAddress> {
        val changed = runtimeHomes.mapNotNull { (key, home) -> home.chat?.let { key to it } }.toMap()
        return configuredHomes.filter { (key, chat) -> key !in runtimeHomes && chat !in changed.values } + changed
    }

    /** Makes [chat] the home at [key] for [by], or none there when null. */
    private fun setHome(key: Pair<AgentId, ChannelInstanceId>, chat: ChatAddress?, by: ChatUser?) {
        if (chat == configuredHomes[key]) runtimeHomes.remove(key) else runtimeHomes[key] = RuntimeHome(chat, by)
    }

    /** The agent whose home [chat] is, else the one whose home its parent is. */
    private fun homeAgent(chat: ChatAddress): AgentId? = homeOf(chat) ?: homeOf(chat.parent)

    private fun homeOf(chat: ChatAddress): AgentId? = homes().entries.firstOrNull { it.value == chat }?.key?.first

    private fun record(call: Call) {
        synchronized(lock) { recorded += call }
    }

    /** Throws the refusal that [refuseNext] queued first, if any. */
    private fun refuseQueued() {
        refusals.removeFirstOrNull()?.let { throw ControlRefusedException(it) }
    }

    private class RuntimeHome(val chat: ChatAddress?, val by: ChatUser?)

    private class Profile(
        val name: String,
        val description: String?,
        val model: ModelRef?,
        val reasoning: ReasoningEffort?,
        val language: LanguageTag?,
    )

    /** A call of the control port. */
    @SubclassOptInRequired(InternalAlexandriteApi::class)
    public interface Call {
        @Poko
        public class Cancel(public val chat: ChatAddress, public val by: ChatUser?) : Call

        @Poko
        public class CancelTurn(public val turn: TurnId, public val by: ChatUser?) : Call

        @Poko
        public class CancelRun(public val run: RunId, public val tree: Boolean, public val by: ChatUser?) : Call

        @Poko
        public class NewConversation(
            public val chat: ChatAddress,
            public val by: ChatUser?,
            /** The conversation the call returned, null when it was refused. */
            public val conversation: ConversationId?,
        ) : Call

        @Poko
        public class UpdateSettings(
            public val chat: ChatAddress,
            public val update: ChatSettingsUpdate,
            public val by: ChatUser?,
        ) : Call

        @Poko
        public class Link(
            public val agent: AgentId,
            public val anchor: ChatAddress,
            public val member: ChatAddress,
            public val by: ChatUser?,
        ) : Call

        @Poko
        public class Unlink(public val agent: AgentId, public val member: ChatAddress, public val by: ChatUser?) : Call

        @Poko
        public class TrustFolder(public val folder: Path, public val by: ChatUser?) : Call

        @Poko
        public class UntrustFolder(public val folder: Path, public val by: ChatUser?) : Call
    }
}

/** Throws [ControlRefusedException] with [message] when [refused]. */
private inline fun refuseIf(refused: Boolean, message: () -> String) {
    if (refused) throw ControlRefusedException(message())
}

private fun <T : Any> SettingChange<T>.applyTo(current: T?): T? = when (this) {
    is SettingChange.SetTo -> value
    SettingChange.Reset -> null
    else -> current
}
