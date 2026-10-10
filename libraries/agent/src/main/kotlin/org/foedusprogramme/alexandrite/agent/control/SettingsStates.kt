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
import org.foedusprogramme.alexandrite.sdk.transcript.ModelRef
import org.foedusprogramme.alexandrite.sdk.turn.ChatSettingsUpdate
import org.foedusprogramme.alexandrite.sdk.turn.SettingChange

/** The chat settings the agent stores, where null is the default of the agent that serves the chat. */
@Singleton
internal class SettingsStates(states: ChatStates) {
    private val agent: ChatState<ChatAddress, Choice<AgentId>> = states.state(AGENT, Choice())
    private val model: ChatState<AgentChatKey, Choice<ModelRef>> = states.agentState(MODEL, Choice())
    private val reasoning: ChatState<AgentChatKey, Choice<ReasoningEffort>> = states.agentState(REASONING, Choice())
    private val language: ChatState<AgentChatKey, Choice<LanguageTag>> = states.agentState(LANGUAGE, Choice())

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

        const val CAPACITY: Int = 4096
    }
}

/** A stored setting, whose [value] is null where the agent's default applies. */
@Serializable
internal data class Choice<T : Any>(val value: T? = null)

private suspend fun <T : Any> ChatState<AgentChatKey, Choice<T>>.change(key: AgentChatKey, change: SettingChange<T>) {
    when (change) {
        is SettingChange.SetTo -> set(key, Choice(change.value))
        SettingChange.Reset -> reset(key)
        else -> Unit
    }
}
