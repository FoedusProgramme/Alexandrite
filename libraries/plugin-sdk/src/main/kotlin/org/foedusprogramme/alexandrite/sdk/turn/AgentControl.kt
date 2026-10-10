package org.foedusprogramme.alexandrite.sdk.turn

import dev.drewhamilton.poko.Poko
import kotlinx.serialization.Serializable
import org.foedusprogramme.alexandrite.sdk.InternalAlexandriteApi
import org.foedusprogramme.alexandrite.sdk.chat.AgentId
import org.foedusprogramme.alexandrite.sdk.chat.ChannelInstanceId
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.chat.ChatUser
import org.foedusprogramme.alexandrite.sdk.chat.ConversationId
import org.foedusprogramme.alexandrite.sdk.chat.LanguageTag
import org.foedusprogramme.alexandrite.sdk.chat.RunId
import org.foedusprogramme.alexandrite.sdk.chat.TurnId
import org.foedusprogramme.alexandrite.sdk.chat.TurnInfo
import org.foedusprogramme.alexandrite.sdk.model.ReasoningEffort
import org.foedusprogramme.alexandrite.sdk.runtime.HostApi
import org.foedusprogramme.alexandrite.sdk.transcript.ModelRef
import java.nio.file.Path
import java.time.Instant

/**
 * The agent's control primitives, which check no permissions and record who asked as `by`.
 *
 * A change that breaks a rule, or that the config file does not take, throws [IllegalArgumentException] with a message
 * for the user and changes nothing.
 */
@HostApi
@SubclassOptInRequired(InternalAlexandriteApi::class)
public interface AgentControl {
    /** The running and queued turns at [chat], or everywhere when null, delegated runs included. */
    public fun turns(chat: ChatAddress? = null): List<TurnStatus>

    /** Cancels the running turns at [chat] and every run they delegated, leaving queued turns alone. */
    public fun cancel(chat: ChatAddress, by: ChatUser?): Boolean

    /** Drops [turn] while it is queued or cancels it while it runs. */
    public fun cancelTurn(turn: TurnId, by: ChatUser?): Boolean

    /** Cancels [run], and the runs it delegated when [tree] is true. */
    public fun cancelRun(run: RunId, tree: Boolean, by: ChatUser?): Boolean

    /** Seals the conversation of the chat's agent once its running turn has ended, and returns the new one. */
    public suspend fun newConversation(chat: ChatAddress, by: ChatUser?): ConversationId

    /** The agents that serve [instance], in config order. */
    public fun agents(instance: ChannelInstanceId): List<AgentSummary>

    /** The settings at [chat], of the agent that serves it. */
    public suspend fun settings(chat: ChatAddress): ChatSettingsSnapshot

    /** Applies [update] at [chat] after its running turn and returns the settings then. */
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

    /** Takes [member] out of its group at [agent], false when it is in none. */
    public suspend fun unlink(agent: AgentId, member: ChatAddress, by: ChatUser?): Boolean

    /** The linked groups of [agent], or of every agent when null. */
    public fun links(agent: AgentId? = null): List<ChatLinkGroup>

    /**
     * Trusts the folder [folder] (absolute), so that the project instructions in it and below it load, and returns the
     * trusted folder that covers it, an ancestor when one is trusted already.
     */
    public suspend fun trustFolder(folder: Path, by: ChatUser?): Path

    /** Stops trusting [folder], false when it is not a trusted folder itself. */
    public suspend fun untrustFolder(folder: Path, by: ChatUser?): Boolean

    /** The trusted folders, each covering the folders below it. */
    public fun trustedFolders(): List<Path>
}

/** Chats whose messages to [agent] continue one conversation, kept at [anchor]. */
@Poko
public class ChatLinkGroup(
    public val agent: AgentId,
    public val anchor: ChatAddress,
    public val members: List<ChatAddress>,
) {
    init {
        require(members.isNotEmpty()) { "A linked group of $anchor has at least one member." }
        require(anchor !in members && members.toSet().size == members.size) {
            "A linked group holds each chat once, was $anchor with $members."
        }
    }
}

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
        require(startedAt == null || !startedAt.isBefore(queuedAt)) {
            "A turn starts after it is queued, was started at $startedAt and queued at $queuedAt."
        }
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

/** The settings of a chat, where null is the default of the agent that serves it. */
@Poko
public class ChatSettingsSnapshot private constructor(
    public val agent: AgentId,
    public val model: ModelRef?,
    public val reasoning: ReasoningEffort?,
    public val language: LanguageTag?,
    /** Whether the chat is the home chat of [agent] on its channel instance. */
    public val home: Boolean,
    /** Why [agent] serves the chat. */
    public val agentSource: AgentSource,
    /** The settings [agent] is configured with. */
    public val defaults: AgentDefaults,
) {
    public fun toBuilder(): Builder = Builder(agent)
        .model(model)
        .reasoning(reasoning)
        .language(language)
        .home(home)
        .agentSource(agentSource)
        .defaults(defaults)

    public class Builder internal constructor(private var agent: AgentId) {
        private var model: ModelRef? = null
        private var reasoning: ReasoningEffort? = null
        private var language: LanguageTag? = null
        private var home: Boolean = false
        private var agentSource: AgentSource = AgentSource.DEFAULT
        private var defaults: AgentDefaults = AgentDefaults()

        public fun agent(agent: AgentId): Builder = apply { this.agent = agent }

        public fun model(model: ModelRef?): Builder = apply { this.model = model }

        public fun reasoning(reasoning: ReasoningEffort?): Builder = apply { this.reasoning = reasoning }

        public fun language(language: LanguageTag?): Builder = apply { this.language = language }

        public fun home(home: Boolean): Builder = apply { this.home = home }

        public fun agentSource(agentSource: AgentSource): Builder = apply { this.agentSource = agentSource }

        public fun defaults(defaults: AgentDefaults): Builder = apply { this.defaults = defaults }

        public fun build(): ChatSettingsSnapshot =
            ChatSettingsSnapshot(agent, model, reasoning, language, home, agentSource, defaults)
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

/** The settings an agent is configured with. */
@Poko
public class AgentDefaults(
    public val model: ModelRef? = null,
    public val reasoning: ReasoningEffort? = null,
    public val language: LanguageTag? = null,
)

/** Changes to the settings of a chat, which keep each setting a builder leaves alone. */
@Poko
public class ChatSettingsUpdate private constructor(
    /** The agent that serves the chat, whose own settings the other changes apply to. */
    public val agent: SettingChange<AgentId>,
    public val model: SettingChange<ModelRef>,
    public val reasoning: SettingChange<ReasoningEffort>,
    public val language: SettingChange<LanguageTag>,
    /**
     * True to make the chat the home chat of its agent on its channel instance, false to leave the instance without
     * one, null to keep it.
     */
    public val home: Boolean?,
) {
    public fun toBuilder(): Builder = Builder(agent, model, reasoning, language, home)

    public class Builder internal constructor(
        private var agent: SettingChange<AgentId>,
        private var model: SettingChange<ModelRef>,
        private var reasoning: SettingChange<ReasoningEffort>,
        private var language: SettingChange<LanguageTag>,
        private var home: Boolean?,
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

        public fun home(): Builder = apply { home = true }

        public fun noHome(): Builder = apply { home = false }

        public fun build(): ChatSettingsUpdate = ChatSettingsUpdate(agent, model, reasoning, language, home)
    }

    public companion object {
        public fun builder(): Builder =
            Builder(SettingChange.Keep, SettingChange.Keep, SettingChange.Keep, SettingChange.Keep, null)
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
