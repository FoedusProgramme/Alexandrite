package org.unitedsoftware.alexandrite.sdk.turn

import dev.drewhamilton.poko.Poko
import kotlinx.serialization.Serializable
import org.unitedsoftware.alexandrite.sdk.InternalAlexandriteApi
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.chat.LanguageTag
import org.unitedsoftware.alexandrite.sdk.model.ReasoningEffort
import org.unitedsoftware.alexandrite.sdk.permission.PermissionMode
import org.unitedsoftware.alexandrite.sdk.runtime.HostApi
import org.unitedsoftware.alexandrite.sdk.transcript.ModelRef
import java.time.Instant
import java.time.ZoneId

/** The settings of each chat, kept by the agent that serves it and changed without a permission check. */
@HostApi
@SubclassOptInRequired(InternalAlexandriteApi::class)
public interface ChatSettings {
    /** The settings at [chat], of the agent that serves it. */
    public suspend fun snapshot(chat: ChatAddress): ChatSettingsSnapshot

    /**
     * Applies [update] at [chat], recording who asked as [by], and returns the settings then.
     *
     * The agent, the home and the permission mode change at once, the model, reasoning, language and zone once the
     * turns queued at the chat's key before the call have ended. An update of the model, reasoning, language or zone
     * throws [IllegalStateException] when called in a turn or in a change of the chat's key or a hook it fires,
     * changing nothing, and when the agent stops before it is made, with the agent, home and mode changed. An update
     * that breaks a rule throws [ControlRefusedException] with a message for the user and changes nothing.
     */
    public suspend fun update(chat: ChatAddress, update: ChatSettingsUpdate, by: ChatUser?): ChatSettingsSnapshot
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

    public class Builder internal constructor(private val agent: AgentId) {
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
