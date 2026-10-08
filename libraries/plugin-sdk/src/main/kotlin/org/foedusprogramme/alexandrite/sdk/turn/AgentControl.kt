package org.foedusprogramme.alexandrite.sdk.turn

import dev.drewhamilton.poko.Poko
import kotlinx.serialization.Serializable
import org.foedusprogramme.alexandrite.sdk.InternalAlexandriteApi
import org.foedusprogramme.alexandrite.sdk.chat.AgentId
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
import java.time.Instant

/** The agent's control primitives, which check no permissions and record who asked as `by`. */
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

    /** The settings at [chat], of the agent that serves it. */
    public suspend fun settings(chat: ChatAddress): ChatSettingsSnapshot

    /** Applies [update] at [chat] after its running turn and returns the settings then. */
    public suspend fun updateSettings(
        chat: ChatAddress,
        update: ChatSettingsUpdate,
        by: ChatUser?,
    ): ChatSettingsSnapshot
}

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
) {
    public fun toBuilder(): Builder = Builder(agent)
        .model(model)
        .reasoning(reasoning)
        .language(language)

    public class Builder internal constructor(private var agent: AgentId) {
        private var model: ModelRef? = null
        private var reasoning: ReasoningEffort? = null
        private var language: LanguageTag? = null

        public fun agent(agent: AgentId): Builder = apply { this.agent = agent }

        public fun model(model: ModelRef?): Builder = apply { this.model = model }

        public fun reasoning(reasoning: ReasoningEffort?): Builder = apply { this.reasoning = reasoning }

        public fun language(language: LanguageTag?): Builder = apply { this.language = language }

        public fun build(): ChatSettingsSnapshot = ChatSettingsSnapshot(agent, model, reasoning, language)
    }

    public companion object {
        public fun builder(agent: AgentId): Builder = Builder(agent)
    }
}

public inline fun ChatSettingsSnapshot.rebuild(block: ChatSettingsSnapshot.Builder.() -> Unit): ChatSettingsSnapshot =
    toBuilder().apply(block).build()

/** Changes to the settings of a chat, which keep each setting a builder leaves alone. */
@Poko
public class ChatSettingsUpdate private constructor(
    /** The agent that serves the chat, whose own settings the other changes apply to. */
    public val agent: SettingChange<AgentId>,
    public val model: SettingChange<ModelRef>,
    public val reasoning: SettingChange<ReasoningEffort>,
    public val language: SettingChange<LanguageTag>,
) {
    public fun toBuilder(): Builder = Builder(agent, model, reasoning, language)

    public class Builder internal constructor(
        private var agent: SettingChange<AgentId>,
        private var model: SettingChange<ModelRef>,
        private var reasoning: SettingChange<ReasoningEffort>,
        private var language: SettingChange<LanguageTag>,
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

        public fun build(): ChatSettingsUpdate = ChatSettingsUpdate(agent, model, reasoning, language)
    }

    public companion object {
        public fun builder(): Builder =
            Builder(SettingChange.Keep, SettingChange.Keep, SettingChange.Keep, SettingChange.Keep)
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
