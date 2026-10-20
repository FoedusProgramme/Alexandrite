package org.unitedsoftware.alexandrite.agent.control

import kotlinx.serialization.Serializable
import org.unitedsoftware.alexandrite.agent.config.Agent
import org.unitedsoftware.alexandrite.internal.time.zoneOrNull
import org.unitedsoftware.alexandrite.sdk.chat.AgentChatKey
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ChatSettingsUpdate
import org.unitedsoftware.alexandrite.sdk.chat.ChatState
import org.unitedsoftware.alexandrite.sdk.chat.ChatStates
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.chat.ConversationId
import org.unitedsoftware.alexandrite.sdk.chat.LanguageTag
import org.unitedsoftware.alexandrite.sdk.chat.SettingChange
import org.unitedsoftware.alexandrite.sdk.chat.TurnId
import org.unitedsoftware.alexandrite.sdk.chat.TurnInfo
import org.unitedsoftware.alexandrite.sdk.chat.TurnKind
import org.unitedsoftware.alexandrite.sdk.chat.agentState
import org.unitedsoftware.alexandrite.sdk.chat.state
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.model.ReasoningEffort
import org.unitedsoftware.alexandrite.sdk.permission.PermissionMode
import org.unitedsoftware.alexandrite.sdk.transcript.ModelRef
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeParseException

/** The chat settings the agent stores, where null is the default of the agent that serves the chat. */
@Singleton
internal class SettingsStates(states: ChatStates) {
    private val agent: ChatState<ChatAddress, Choice<AgentId>> = states.state(AGENT, Choice())
    private val model: ChatState<AgentChatKey, Choice<ModelRef>> = states.agentState(MODEL, Choice())
    private val reasoning: ChatState<AgentChatKey, Choice<ReasoningEffort>> = states.agentState(REASONING, Choice())
    private val language: ChatState<AgentChatKey, Choice<LanguageTag>> = states.agentState(LANGUAGE, Choice())
    private val mode: ChatState<AgentChatKey, ModeChoice> = states.agentState(MODE, ModeChoice())

    /** The zone's id, kept as text so that a zone a later JDK drops reads as none. */
    private val zone: ChatState<AgentChatKey, Choice<String>> = states.agentState(ZONE, Choice())

    /** The agents that the [CAPACITY] chats read last switched to. */
    private val selections = object : LinkedHashMap<ChatAddress, Choice<AgentId>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ChatAddress, Choice<AgentId>>) = size > CAPACITY
    }

    /** The agent that [chat] or its parent switched to, null while [loadAgent] has not read it. */
    fun cachedAgent(chat: ChatAddress): Choice<AgentId>? = synchronized(selections) { selections[chat] }

    /** Reads the agent that [chat] or its parent switched to, unless it is cached. */
    suspend fun loadAgent(chat: ChatAddress): Choice<AgentId> {
        cachedAgent(chat)?.let { return it }
        val read = agent.get(chat)
        return synchronized(selections) { selections.putIfAbsent(chat, read) ?: read }
    }

    /** Stores [change] of the agent that [chat] switched to, and caches what [chat] reads then. */
    suspend fun selectAgent(chat: ChatAddress, change: SettingChange<AgentId>) {
        if (!agent.change(chat, change) { Choice(it) }) return
        val read = agent.get(chat)
        synchronized(selections) {
            selections[chat] = read
            if (chat.thread == null) selections.keys.removeAll { it.thread != null && it.parent == chat }
        }
    }

    suspend fun model(key: AgentChatKey): ModelRef? = model.get(key).value

    suspend fun reasoning(key: AgentChatKey): ReasoningEffort? = reasoning.get(key).value

    suspend fun language(key: AgentChatKey): LanguageTag? = language.get(key).value

    suspend fun zone(key: AgentChatKey): ZoneId? = zone.get(key).value?.let(::zoneOrNull)

    /** The permission mode stored at [key] that holds at [now], null when none does. */
    suspend fun mode(key: AgentChatKey, now: Instant): StoredMode? {
        val stored = mode.get(key)
        val value = stored.value?.takeIf { it in PermissionMode.entries } ?: return null
        if (value != PermissionMode.BYPASS) return StoredMode(value, null)
        val until = stored.until?.let(::instantOrNull) ?: return null
        return StoredMode(value, until).takeIf { now.isBefore(until) }
    }

    /** Stores [change] of the permission mode at [key], where [until] ends a bypass. */
    suspend fun changeMode(key: AgentChatKey, change: SettingChange<PermissionMode>, until: Instant?) {
        mode.change(key, change) { ModeChoice(it, until?.toString()) }
    }

    /** Stores the changes of [update] to the model, reasoning, language and zone at [key]. */
    suspend fun update(key: AgentChatKey, update: ChatSettingsUpdate) {
        model.change(key, update.model) { Choice(it) }
        reasoning.change(key, update.reasoning) { Choice(it) }
        language.change(key, update.language) { Choice(it) }
        zone.change(key, update.zone) { Choice(it.id) }
    }

    companion object {
        const val AGENT: String = "agent.selected"
        const val MODEL: String = "settings.model"
        const val REASONING: String = "settings.reasoning"
        const val LANGUAGE: String = "settings.language"
        const val MODE: String = "settings.mode"
        const val ZONE: String = "settings.zone"

        const val CAPACITY: Int = 4096
    }
}

/** A stored setting, whose [value] is null where the agent's default applies. */
@Serializable
internal data class Choice<T : Any>(val value: T? = null)

/** A stored permission mode, whose bypass ends at the ISO-8601 instant [until]. */
@Serializable
internal data class ModeChoice(val value: PermissionMode? = null, val until: String? = null)

private fun instantOrNull(text: String): Instant? = try {
    Instant.parse(text)
} catch (e: DateTimeParseException) {
    null
}

/** The zone that [agent]'s times at [key] are told in: the chat's, else the agent's, else the host's of [clock]. */
internal suspend fun SettingsStates.effectiveZone(key: AgentChatKey, agent: Agent?, clock: Clock): ZoneId =
    zone(key) ?: agent?.zone ?: clock.zone

/** The COMMAND turn [id] of [by] in [chat] at [key]'s [conversation], told in the language and zone that hold there. */
internal suspend fun SettingsStates.commandTurn(
    id: TurnId,
    key: AgentChatKey,
    chat: ChatAddress,
    conversation: ConversationId,
    by: ChatUser?,
    agent: Agent?,
    clock: Clock,
): TurnInfo = TurnInfo.builder(id, chat, conversation, TurnKind.COMMAND)
    .actor(by)
    .language(language(key) ?: agent?.language)
    .zone(effectiveZone(key, agent, clock))
    .agent(key.agent)
    .key(key)
    .build()

internal class StoredMode(val mode: PermissionMode, val until: Instant?)

/** Stores [change] at [key] as [stored] keeps its value, and returns false when it keeps the setting as it is. */
private suspend fun <K : Any, S : Any, T : Any> ChatState<K, S>.change(
    key: K,
    change: SettingChange<T>,
    stored: (T) -> S,
): Boolean {
    when (change) {
        is SettingChange.SetTo -> set(key, stored(change.value))
        SettingChange.Reset -> reset(key)
        else -> return false
    }
    return true
}
