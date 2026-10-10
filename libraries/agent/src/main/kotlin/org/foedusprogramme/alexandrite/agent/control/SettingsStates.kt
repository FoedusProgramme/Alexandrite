package org.foedusprogramme.alexandrite.agent.control

import kotlinx.coroutines.runBlocking
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
import java.util.concurrent.ConcurrentHashMap

/** The chat settings the agent stores, where null is the default of the agent that serves the chat. */
@Singleton
internal class SettingsStates(states: ChatStates) {
    private val agent: ChatState<ChatAddress, Choice<AgentId>> = states.state(AGENT, Choice())
    private val model: ChatState<AgentChatKey, Choice<ModelRef>> = states.agentState(MODEL, Choice())
    private val reasoning: ChatState<AgentChatKey, Choice<ReasoningEffort>> = states.agentState(REASONING, Choice())
    private val language: ChatState<AgentChatKey, Choice<LanguageTag>> = states.agentState(LANGUAGE, Choice())
    private val selections = ConcurrentHashMap<ChatAddress, Choice<AgentId>>()

    /** The agent that [chat] or its parent switched to, read with a blocking call the first time. */
    fun selectedAgent(chat: ChatAddress): AgentId? =
        (selections[chat] ?: runBlocking { agent.get(chat) }.also { selections[chat] = it }).value

    suspend fun model(key: AgentChatKey): ModelRef? = model.get(key).value

    suspend fun reasoning(key: AgentChatKey): ReasoningEffort? = reasoning.get(key).value

    suspend fun language(key: AgentChatKey): LanguageTag? = language.get(key).value

    companion object {
        const val AGENT: String = "agent.selected"
        const val MODEL: String = "settings.model"
        const val REASONING: String = "settings.reasoning"
        const val LANGUAGE: String = "settings.language"
    }
}

/** A stored setting, whose [value] is null where the agent's default applies. */
@Serializable
internal data class Choice<T : Any>(val value: T? = null)
