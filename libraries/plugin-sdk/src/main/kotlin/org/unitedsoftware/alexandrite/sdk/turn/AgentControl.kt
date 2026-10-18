package org.unitedsoftware.alexandrite.sdk.turn

import dev.drewhamilton.poko.Poko
import kotlinx.serialization.Serializable
import org.unitedsoftware.alexandrite.sdk.InternalAlexandriteApi
import org.unitedsoftware.alexandrite.sdk.agent.AgentProfile
import org.unitedsoftware.alexandrite.sdk.agent.ProfileSource
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChannelInstanceId
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.chat.ConversationId
import org.unitedsoftware.alexandrite.sdk.chat.LanguageTag
import org.unitedsoftware.alexandrite.sdk.chat.RunId
import org.unitedsoftware.alexandrite.sdk.chat.TurnId
import org.unitedsoftware.alexandrite.sdk.chat.TurnInfo
import org.unitedsoftware.alexandrite.sdk.model.ModelInfo
import org.unitedsoftware.alexandrite.sdk.model.ReasoningEffort
import org.unitedsoftware.alexandrite.sdk.permission.PermissionMode
import org.unitedsoftware.alexandrite.sdk.runtime.HostApi
import org.unitedsoftware.alexandrite.sdk.store.ConversationTaint
import org.unitedsoftware.alexandrite.sdk.tool.RuleSubject
import org.unitedsoftware.alexandrite.sdk.transcript.EndpointId
import org.unitedsoftware.alexandrite.sdk.transcript.ModelRef
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId

/**
 * The agent's control primitives, which check no permissions and record who asked as `by`.
 *
 * A change that breaks a rule throws [ControlRefusedException] with a message for the user and changes nothing.
 */
@HostApi
@SubclassOptInRequired(InternalAlexandriteApi::class)
public interface AgentControl {
    /**
     * The turns that the agent's workers run or queue and the commands that run, at [chat] or everywhere when null,
     * delegated runs included, where a queued turn shows the conversation it would start in now.
     *
     * Messages that a chat's intake holds and commands that wait in its command lane are left out.
     */
    public suspend fun turns(chat: ChatAddress? = null): List<TurnStatus>

    /**
     * Cancels the running turns at [chat] and every run they delegated and stops its jobs, leaving queued turns alone.
     */
    public fun cancel(chat: ChatAddress, by: ChatUser?): Boolean

    /** Drops [turn] while it is queued, held or waiting, or cancels it while it runs. */
    public fun cancelTurn(turn: TurnId, by: ChatUser?): Boolean

    /** Cancels [run], and the runs it delegated when [tree] is true, or stops it when it is a job. */
    public fun cancelRun(run: RunId, tree: Boolean, by: ChatUser?): Boolean

    /** The jobs that turns at [chat] moved to the background and that still run, oldest first. */
    public fun jobs(chat: ChatAddress): List<JobStatus>

    /** Stops the job [job] of [chat], false when [chat] runs no such job. */
    public fun stopJob(chat: ChatAddress, job: RunId, by: ChatUser?): Boolean

    /**
     * Seals the conversation of the chat's agent once the turns queued at its key before the call have ended, and
     * returns the new one.
     *
     * Throws [IllegalStateException] when called in a turn, in a change of the chat's key or a hook it fires, or once
     * the agent stops.
     */
    public suspend fun newConversation(chat: ChatAddress, by: ChatUser?): ConversationId

    /**
     * Folds the older part of the conversation of the chat's agent into its rolling summary once the turns queued at its
     * key before the call have ended, with [focus] added to the summarizer's instructions.
     *
     * Throws [ControlRefusedException] when there is nothing to fold or no summary was written, and
     * [IllegalStateException] where [newConversation] does.
     */
    public suspend fun compact(chat: ChatAddress, focus: String?, by: ChatUser?): CompactionReport

    /** How much of its model's context window the current conversation of the chat's agent takes. */
    public suspend fun context(chat: ChatAddress): ContextStatus

    /**
     * Clears the taint of the conversation of the chat's agent once the turns queued at its key before the call have
     * ended, so that its next turns run as in a clean conversation until something taints it again, and returns the
     * mark it had, null when it had none.
     *
     * Throws [IllegalStateException] where [newConversation] does.
     */
    public suspend fun untaint(chat: ChatAddress, by: ChatUser?): ConversationTaint?

    /** The agents that serve [instance], in config order. */
    public fun agents(instance: ChannelInstanceId): List<AgentSummary>

    /** The settings at [chat], of the agent that serves it. */
    public suspend fun settings(chat: ChatAddress): ChatSettingsSnapshot

    /**
     * Applies [update] at [chat] and returns the settings then.
     *
     * The agent, the home and the permission mode change at once, the model, reasoning, language and zone once the
     * turns queued at the chat's key before the call have ended. An update of the model, reasoning, language or zone
     * throws [IllegalStateException] when called in a turn or in a change of the chat's key or a hook it fires,
     * changing nothing, and when the agent stops before it is made, with the agent, home and mode changed.
     */
    public suspend fun updateSettings(
        chat: ChatAddress,
        update: ChatSettingsUpdate,
        by: ChatUser?,
    ): ChatSettingsSnapshot

    /**
     * Links [member] into the group of [anchor] at [agent], which [anchor] starts when it is in none, and returns the
     * group.
     */
    public suspend fun link(agent: AgentId, anchor: ChatAddress, member: ChatAddress, by: ChatUser?): ChatLinkGroup

    /**
     * Takes [member] out of its group at [agent], removing its link made at runtime whenever there is one, and tells
     * whether the config file still links it.
     */
    public suspend fun unlink(agent: AgentId, member: ChatAddress, by: ChatUser?): Revocation

    /** The linked groups of [agent], or of every agent when null. */
    public fun links(agent: AgentId? = null): List<ChatLinkGroup>

    /**
     * Trusts the folder [folder] (absolute), so that the project instructions in it and below it load outside the
     * repositories nested in it, and returns the trusted folder that covers it, an ancestor when one covers it already.
     */
    public suspend fun trustFolder(folder: Path, by: ChatUser?): Path

    /**
     * Stops trusting [folder] at runtime, removing its grant whenever there is one, and tells whether the config file
     * still trusts it.
     */
    public suspend fun untrustFolder(folder: Path, by: ChatUser?): Revocation

    /** The trusted folders, each covering the folders below it outside the repositories nested in it. */
    public fun trustedFolders(): List<TrustedFolder>

    /**
     * The allow rules in force at [chat]: those the config file declares for its agent, then those granted in its
     * current conversation.
     */
    public suspend fun allowRules(chat: ChatAddress): List<AllowRule>

    /**
     * Revokes the rule numbered [number] in [allowRules] at [chat] when it was granted in the conversation, and tells
     * whether the config file still allows what it covered.
     */
    public suspend fun revokeAllowRule(chat: ChatAddress, number: Int, by: ChatUser?): Revocation

    /**
     * The trust rules in force at [chat]: those the config file declares for its agent, then those granted in its
     * current conversation.
     */
    public suspend fun trustRules(chat: ChatAddress): List<TrustRule> = emptyList()

    /**
     * Trusts the output of the calls that [rule], written like a trust rule of the config file, covers in the current
     * conversation at [chat], and returns the rule.
     */
    public suspend fun trust(chat: ChatAddress, rule: String, by: ChatUser?): TrustRule =
        throw ControlRefusedException("This agent keeps no trust rules.")

    /**
     * Stops trusting the calls that [rule] covers in the current conversation at [chat], removing its grant whenever
     * there is one, and tells whether the config file still declares it.
     */
    public suspend fun untrust(chat: ChatAddress, rule: String, by: ChatUser?): Revocation =
        throw ControlRefusedException("This agent keeps no trust rules.")

    /** The profiles a delegating agent may run as a sub-agent, config ones first, then plugin ones, then created ones. */
    public suspend fun profiles(): List<ProfileSummary> = emptyList()

    /** The model endpoints the agents can use. */
    public fun endpoints(): List<EndpointId>

    /**
     * The models of [endpoint] from the listing that a chat's model is checked against.
     *
     * Throws [ControlRefusedException] for an endpoint that [endpoints] leaves out, and the endpoint's own exception
     * when its first listing fails.
     */
    public suspend fun models(endpoint: EndpointId): List<ModelInfo>
}

/** What revoking a grant did. */
@Poko
public class Revocation(
    /** Whether a grant made at runtime was removed. */
    public val removed: Boolean,
    /** Whether the config file still grants it. */
    public val declared: Boolean,
)

/** A trusted folder. */
@Poko
public class TrustedFolder(
    public val path: Path,
    /** Whether the config file declares the folder. */
    public val declared: Boolean,
    /** When the folder was trusted at runtime, null when it was not. */
    public val trustedAt: Instant?,
    /** Who trusted the folder at runtime, null for the host or when nobody did. */
    public val trustedBy: ChatUser?,
)

/** A rule that lets the calls of its [subject] run without asking on an operator's turn. */
@Poko
public class AllowRule(
    /** The rule's place among the rules in force, from 1. */
    public val number: Int,
    public val subject: RuleSubject,
    /** Whether the config file declares the rule. */
    public val declared: Boolean,
    /** When the rule was granted in the conversation, null for a declared one. */
    public val grantedAt: Instant?,
    /** Who granted the rule, null for a declared one or the host. */
    public val grantedBy: ChatUser?,
)

/** A rule whose calls' output does not taint the conversation. */
@Poko
public class TrustRule(
    /** As the config file writes it. */
    public val rule: String,
    /** Whether the config file declares the rule. */
    public val declared: Boolean,
    /** When the rule was granted in the conversation, null for a declared one. */
    public val grantedAt: Instant?,
    /** Who granted the rule, null for a declared one or the host. */
    public val grantedBy: ChatUser?,
    /** Whether the rule trusts programs that run whatever code they are given, such as a shell. */
    public val anyCode: Boolean = false,
)

/** Chats whose messages to [agent] continue one conversation, kept at [anchor]. */
@Poko
public class ChatLinkGroup(
    public val agent: AgentId,
    public val anchor: ChatAddress,
    public val members: List<ChatAddress>,
    /** Whether the config file declares the group. */
    public val declared: Boolean = false,
    /** The links of [members] made at runtime, by member. */
    public val links: Map<ChatAddress, ChatLink> = emptyMap(),
) {
    init {
        require(members.isNotEmpty()) { "A linked group of $anchor has at least one member." }
        require(anchor !in members && members.toSet().size == members.size) {
            "A linked group holds each chat once, was $anchor with $members."
        }
        require(members.containsAll(links.keys)) { "A linked group has links of its members only, was ${links.keys}." }
    }
}

/** A link of a chat into a group made at runtime. */
@Poko
public class ChatLink(
    public val linkedAt: Instant,
    /** Null for the host. */
    public val linkedBy: ChatUser?,
)

/** An agent as it serves one channel instance. */
@Poko
public class AgentSummary(
    public val id: AgentId,
    /** How the agent calls itself. */
    public val name: String,
    public val description: String?,
    /** Whether the agent serves the instance's chats that choose no other agent. */
    public val default: Boolean,
    /** The agent's home chat on the instance, null when it has none. */
    public val home: ChatAddress?,
    /** The home chat that the config file declares on the instance, null when it declares none. */
    public val configuredHome: ChatAddress? = null,
    /** When [home], or none, was set at runtime, null when the configured home applies. */
    public val homeSetAt: Instant? = null,
    /** Who set [home] at runtime, null for the host or when nobody did. */
    public val homeSetBy: ChatUser? = null,
)

/** A profile as [AgentControl.profiles] shows it. */
@Poko
public class ProfileSummary(public val profile: AgentProfile, public val source: ProfileSource)

/** A tool call that runs on in the background after its turn went on. */
@Poko
public class JobStatus(
    public val id: RunId,
    /** The chat of the turn that made the call. */
    public val chat: ChatAddress,
    public val tool: String,
    /** The call's main argument, such as its command line or path, empty when it has none. */
    public val title: String,
    /** When the call started to run. */
    public val startedAt: Instant,
    /** The principal of the turn that made the call, null when it had none. */
    public val by: ChatUser?,
)

/** What a compaction folded into the rolling summary. */
@Poko
public class CompactionReport(
    /** The stored entries that the compaction folded into the summary. */
    public val folded: Int,
    /** The prompt tokens before the compaction, as the agent estimates them. */
    public val tokensBefore: Long,
    /** The prompt tokens after the compaction, as the agent estimates them. */
    public val tokensAfter: Long,
)

/** How much of its model's context window a conversation takes. */
@Poko
public class ContextStatus(
    public val conversation: ConversationId,
    /** The model the conversation's turns call now, null when its agent has none. */
    public val model: ModelRef?,
    /** The prompt tokens of the conversation's last request, as its backend counted them, null before one. */
    public val promptTokens: Long?,
    /** When [promptTokens] was counted. */
    public val measuredAt: Instant?,
    /** Null when the model's window is unknown. */
    public val window: Int?,
    /** The prompt tokens from which the conversation is compacted, null without a [window]. */
    public val trigger: Long?,
    /** The prompt tokens a compaction sheds down to, null without a [window]. */
    public val target: Long?,
    /** When the conversation was last compacted, null when it never was. */
    public val compactedAt: Instant?,
    /** The stored entries that the conversation's summary stands in for. */
    public val summarized: Int,
)

/** A running or queued turn. */
@Poko
public class TurnStatus private constructor(
    public val turn: TurnInfo,
    public val phase: TurnPhase,
    public val queuedAt: Instant,
    /** Null while the turn is queued. */
    public val startedAt: Instant?,
) {
    init {
        require(phase != TurnPhase.QUEUED || startedAt == null) { "A queued turn has not started." }
        require(phase != TurnPhase.RUNNING || startedAt != null) { "A running turn has a start time." }
    }

    public fun toBuilder(): Builder = Builder(turn, phase, queuedAt).startedAt(startedAt)

    public class Builder internal constructor(
        private var turn: TurnInfo,
        private var phase: TurnPhase,
        private var queuedAt: Instant,
    ) {
        private var startedAt: Instant? = null

        public fun turn(turn: TurnInfo): Builder = apply { this.turn = turn }

        public fun phase(phase: TurnPhase): Builder = apply { this.phase = phase }

        public fun queuedAt(queuedAt: Instant): Builder = apply { this.queuedAt = queuedAt }

        public fun startedAt(startedAt: Instant?): Builder = apply { this.startedAt = startedAt }

        public fun build(): TurnStatus = TurnStatus(turn, phase, queuedAt, startedAt)
    }

    public companion object {
        public fun builder(turn: TurnInfo, phase: TurnPhase, queuedAt: Instant): Builder =
            Builder(turn, phase, queuedAt)
    }
}

public inline fun TurnStatus.rebuild(block: TurnStatus.Builder.() -> Unit): TurnStatus =
    toBuilder().apply(block).build()

/** Where a turn is in its life. */
@JvmInline
@Serializable
public value class TurnPhase internal constructor(public val id: String) {
    override fun toString(): String = id

    public companion object {
        public val QUEUED: TurnPhase = TurnPhase("queued")

        public val RUNNING: TurnPhase = TurnPhase("running")

        /** The values this version knows. */
        public val entries: List<TurnPhase> = listOf(QUEUED, RUNNING)

        /** The value of [id], which keeps an id this version does not know. */
        public fun of(id: String): TurnPhase = TurnPhase(id)
    }
}

/**
 * The settings of a chat, where the chat's own ones are null when it has none and the default ones are null when
 * [agent] is configured with none.
 */
@Poko
public class ChatSettingsSnapshot private constructor(
    public val agent: AgentId,
    public val model: ModelRef?,
    public val reasoning: ReasoningEffort?,
    public val language: LanguageTag?,
    /** Whether the chat, or the chat of its thread, is the home chat of [agent] on its channel instance. */
    public val home: Boolean,
    /** Why [agent] serves the chat. */
    public val agentSource: AgentSource,
    public val defaultModel: ModelRef?,
    /** The models [agent] is configured with, as equals, [defaultModel] first. */
    public val models: List<ModelRef>,
    public val defaultReasoning: ReasoningEffort?,
    public val defaultLanguage: LanguageTag?,
    public val mode: PermissionMode?,
    /** When the chat's [PermissionMode.BYPASS] ends. */
    public val modeUntil: Instant?,
    public val defaultMode: PermissionMode?,
    public val zone: ZoneId?,
    public val defaultZone: ZoneId?,
) {
    init {
        require(modeUntil == null || mode == PermissionMode.BYPASS) {
            "Only a bypass ends, was $mode until $modeUntil."
        }
    }

    /** The language [agent] answers the chat in, null when the host's default language applies. */
    public val effectiveLanguage: LanguageTag? get() = language ?: defaultLanguage

    /** The permission mode of the chat, [PermissionMode.ASK] when neither the chat nor [agent] sets one. */
    public val effectiveMode: PermissionMode get() = mode ?: defaultMode ?: PermissionMode.ASK

    /** The zone [agent] tells the chat's times in, null when the host's zone applies. */
    public val effectiveZone: ZoneId? get() = zone ?: defaultZone

    public fun toBuilder(): Builder = Builder(agent)
        .model(model)
        .reasoning(reasoning)
        .language(language)
        .home(home)
        .agentSource(agentSource)
        .defaultModel(defaultModel)
        .models(models)
        .defaultReasoning(defaultReasoning)
        .defaultLanguage(defaultLanguage)
        .mode(mode, modeUntil)
        .defaultMode(defaultMode)
        .zone(zone)
        .defaultZone(defaultZone)

    public class Builder internal constructor(private var agent: AgentId) {
        private var model: ModelRef? = null
        private var reasoning: ReasoningEffort? = null
        private var language: LanguageTag? = null
        private var home: Boolean = false
        private var agentSource: AgentSource = AgentSource.DEFAULT
        private var defaultModel: ModelRef? = null
        private var models: List<ModelRef> = emptyList()
        private var defaultReasoning: ReasoningEffort? = null
        private var defaultLanguage: LanguageTag? = null
        private var mode: PermissionMode? = null
        private var modeUntil: Instant? = null
        private var defaultMode: PermissionMode? = null
        private var zone: ZoneId? = null
        private var defaultZone: ZoneId? = null

        public fun agent(agent: AgentId): Builder = apply { this.agent = agent }

        public fun model(model: ModelRef?): Builder = apply { this.model = model }

        public fun reasoning(reasoning: ReasoningEffort?): Builder = apply { this.reasoning = reasoning }

        public fun language(language: LanguageTag?): Builder = apply { this.language = language }

        public fun home(home: Boolean): Builder = apply { this.home = home }

        public fun agentSource(agentSource: AgentSource): Builder = apply { this.agentSource = agentSource }

        public fun defaultModel(defaultModel: ModelRef?): Builder = apply { this.defaultModel = defaultModel }

        public fun models(models: List<ModelRef>): Builder = apply { this.models = models.toList() }

        public fun defaultReasoning(defaultReasoning: ReasoningEffort?): Builder =
            apply { this.defaultReasoning = defaultReasoning }

        public fun defaultLanguage(defaultLanguage: LanguageTag?): Builder =
            apply { this.defaultLanguage = defaultLanguage }

        public fun mode(mode: PermissionMode?, until: Instant? = null): Builder = apply {
            this.mode = mode
            modeUntil = until
        }

        public fun defaultMode(defaultMode: PermissionMode?): Builder = apply { this.defaultMode = defaultMode }

        public fun zone(zone: ZoneId?): Builder = apply { this.zone = zone }

        public fun defaultZone(defaultZone: ZoneId?): Builder = apply { this.defaultZone = defaultZone }

        public fun build(): ChatSettingsSnapshot = ChatSettingsSnapshot(
            agent,
            model,
            reasoning,
            language,
            home,
            agentSource,
            defaultModel,
            models,
            defaultReasoning,
            defaultLanguage,
            mode,
            modeUntil,
            defaultMode,
            zone,
            defaultZone,
        )
    }

    public companion object {
        public fun builder(agent: AgentId): Builder = Builder(agent)
    }
}

public inline fun ChatSettingsSnapshot.rebuild(block: ChatSettingsSnapshot.Builder.() -> Unit): ChatSettingsSnapshot =
    toBuilder().apply(block).build()

/** Why an agent serves a chat. */
@JvmInline
@Serializable
public value class AgentSource internal constructor(public val id: String) {
    override fun toString(): String = id

    public companion object {
        /** The chat switched to the agent. */
        public val SELECTED: AgentSource = AgentSource("selected")

        /** The chat, or the chat of its thread, is the agent's home chat. */
        public val HOME: AgentSource = AgentSource("home")

        /** The agent is the default of the chat's channel instance. */
        public val DEFAULT: AgentSource = AgentSource("default")

        /** The values this version knows. */
        public val entries: List<AgentSource> = listOf(SELECTED, HOME, DEFAULT)

        /** The value of [id], which keeps an id this version does not know. */
        public fun of(id: String): AgentSource = AgentSource(id)
    }
}

/** Changes to the settings of a chat, which keep each setting a builder leaves alone. */
@Poko
public class ChatSettingsUpdate private constructor(
    /** The agent that serves the chat, whose own settings the other changes apply to. */
    public val agent: SettingChange<AgentId>,
    public val model: SettingChange<ModelRef>,
    public val reasoning: SettingChange<ReasoningEffort>,
    public val language: SettingChange<LanguageTag>,
    /**
     * Whether the chat is the home chat of its agent on its channel instance, where a reset, from any chat of the
     * instance, drops the home set at runtime there and brings back the configured one.
     *
     * True changes nothing while the chat, or the chat of its thread, is the home, and false changes nothing unless it
     * is.
     */
    public val home: SettingChange<Boolean>,
    public val mode: SettingChange<PermissionMode>,
    /** When the bypass that [mode] sets ends, at most a day after the update, null for an hour after it. */
    public val modeUntil: Instant?,
    public val zone: SettingChange<ZoneId>,
) {
    init {
        require(modeUntil == null || (mode as? SettingChange.SetTo)?.value == PermissionMode.BYPASS) {
            "Only a bypass ends, was $mode until $modeUntil."
        }
    }

    public fun toBuilder(): Builder = Builder(agent, model, reasoning, language, home, mode, modeUntil, zone)

    public class Builder internal constructor(
        private var agent: SettingChange<AgentId>,
        private var model: SettingChange<ModelRef>,
        private var reasoning: SettingChange<ReasoningEffort>,
        private var language: SettingChange<LanguageTag>,
        private var home: SettingChange<Boolean>,
        private var mode: SettingChange<PermissionMode>,
        private var modeUntil: Instant?,
        private var zone: SettingChange<ZoneId>,
    ) {
        public fun agent(agent: AgentId): Builder = apply { this.agent = SettingChange.SetTo(agent) }

        public fun resetAgent(): Builder = apply { agent = SettingChange.Reset }

        public fun model(model: ModelRef): Builder = apply { this.model = SettingChange.SetTo(model) }

        public fun resetModel(): Builder = apply { model = SettingChange.Reset }

        public fun reasoning(reasoning: ReasoningEffort): Builder =
            apply { this.reasoning = SettingChange.SetTo(reasoning) }

        public fun resetReasoning(): Builder = apply { reasoning = SettingChange.Reset }

        public fun language(language: LanguageTag): Builder = apply { this.language = SettingChange.SetTo(language) }

        public fun resetLanguage(): Builder = apply { language = SettingChange.Reset }

        public fun home(): Builder = apply { home = SettingChange.SetTo(true) }

        public fun noHome(): Builder = apply { home = SettingChange.SetTo(false) }

        public fun resetHome(): Builder = apply { home = SettingChange.Reset }

        public fun mode(mode: PermissionMode, until: Instant? = null): Builder = apply {
            this.mode = SettingChange.SetTo(mode)
            modeUntil = until
        }

        public fun resetMode(): Builder = apply {
            mode = SettingChange.Reset
            modeUntil = null
        }

        public fun zone(zone: ZoneId): Builder = apply { this.zone = SettingChange.SetTo(zone) }

        public fun resetZone(): Builder = apply { zone = SettingChange.Reset }

        public fun build(): ChatSettingsUpdate =
            ChatSettingsUpdate(agent, model, reasoning, language, home, mode, modeUntil, zone)
    }

    public companion object {
        public fun builder(): Builder = Builder(
            agent = SettingChange.Keep,
            model = SettingChange.Keep,
            reasoning = SettingChange.Keep,
            language = SettingChange.Keep,
            home = SettingChange.Keep,
            mode = SettingChange.Keep,
            modeUntil = null,
            zone = SettingChange.Keep,
        )
    }
}

public inline fun ChatSettingsUpdate.rebuild(block: ChatSettingsUpdate.Builder.() -> Unit): ChatSettingsUpdate =
    toBuilder().apply(block).build()

/** What an update does to one setting. */
public sealed interface SettingChange<out T : Any> {
    public data object Keep : SettingChange<Nothing>

    @Poko
    public class SetTo<out T : Any>(public val value: T) : SettingChange<T>

    /** Back to the default. */
    public data object Reset : SettingChange<Nothing>
}
