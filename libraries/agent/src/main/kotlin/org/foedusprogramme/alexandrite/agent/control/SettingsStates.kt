package org.foedusprogramme.alexandrite.agent.control

import kotlinx.serialization.Serializable
import org.foedusprogramme.alexandrite.sdk.chat.AgentChatKey
import org.foedusprogramme.alexandrite.sdk.chat.AgentId
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.chat.ChatState
import org.foedusprogramme.alexandrite.sdk.chat.ChatStates
import org.foedusprogramme.alexandrite.sdk.chat.LanguageTag
import org.foedusprogramme.alexandrite.sdk.chat.agentState
import org.foedusprogramme.alexandrite.sdk.chat.state
import org.foedusprogramme.alexandrite.sdk.di.Singleton
import org.foedusprogramme.alexandrite.sdk.model.ReasoningEffort
import org.foedusprogramme.alexandrite.sdk.permission.PermissionMode
import org.foedusprogramme.alexandrite.sdk.transcript.ModelRef
import org.foedusprogramme.alexandrite.sdk.turn.ChatSettingsUpdate
import org.foedusprogramme.alexandrite.sdk.turn.SettingChange
import java.time.Instant
import java.time.format.DateTimeParseException

/** The chat settings the agent stores, where null is the default of the agent that serves the chat. */
@Singleton
internal class SettingsStates(states: ChatStates) {
    private val agent: ChatState<ChatAddress, Choice<AgentId>> = states.state(AGENT, Choice())
    private val model: ChatState<AgentChatKey, Choice<ModelRef>> = states.agentState(MODEL, Choice())
    private val reasoning: ChatState<AgentChatKey, Choice<ReasoningEffort>> = states.agentState(REASONING, Choice())
    private val language: ChatState<AgentChatKey, Choice<LanguageTag>> = states.agentState(LANGUAGE, Choice())
    private val mode: ChatState<AgentChatKey, ModeChoice> = states.agentState(MODE, ModeChoice())

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
        when (change) {
            is SettingChange.SetTo -> agent.set(chat, Choice(change.value))
            SettingChange.Reset -> agent.reset(chat)
            else -> return
        }
        val read = agent.get(chat)
        synchronized(selections) {
            selections[chat] = read
            if (chat.thread == null) selections.keys.removeAll { it.thread != null && it.parent == chat }
        }
    }

    suspend fun model(key: AgentChatKey): ModelRef? = model.get(key).value

    suspend fun reasoning(key: AgentChatKey): ReasoningEffort? = reasoning.get(key).value

    suspend fun language(key: AgentChatKey): LanguageTag? = language.get(key).value

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
        when (change) {
            is SettingChange.SetTo -> mode.set(key, ModeChoice(change.value, until?.toString()))
            SettingChange.Reset -> mode.reset(key)
            else -> Unit
        }
    }

    /** Stores the changes of [update] to the model, reasoning and language at [key]. */
    suspend fun update(key: AgentChatKey, update: ChatSettingsUpdate) {
        model.change(key, update.model)
        reasoning.change(key, update.reasoning)
        language.change(key, update.language)
    }

    companion object {
        const val AGENT: String = "agent.selected"
        const val MODEL: String = "settings.model"
        const val REASONING: String = "settings.reasoning"
        const val LANGUAGE: String = "settings.language"
        const val MODE: String = "settings.mode"

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

internal class StoredMode(val mode: PermissionMode, val until: Instant?)

private suspend fun <T : Any> ChatState<AgentChatKey, Choice<T>>.change(key: AgentChatKey, change: SettingChange<T>) {
    when (change) {
        is SettingChange.SetTo -> set(key, Choice(change.value))
        SettingChange.Reset -> reset(key)
        else -> Unit
    }
}
