package org.foedusprogramme.alexandrite.agent.control

import org.foedusprogramme.alexandrite.agent.blocking
import org.foedusprogramme.alexandrite.sdk.chat.AgentChatKey
import org.foedusprogramme.alexandrite.sdk.chat.AgentId
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.chat.LanguageTag
import org.foedusprogramme.alexandrite.sdk.chat.agentState
import org.foedusprogramme.alexandrite.sdk.chat.state
import org.foedusprogramme.alexandrite.sdk.model.ReasoningEffort
import org.foedusprogramme.alexandrite.sdk.transcript.ModelRef
import org.foedusprogramme.alexandrite.testkit.TestChatStates
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SettingsStatesTest {
    private val stored = TestChatStates().of("alexandrite-agent")
    private val settings = SettingsStates(stored)
    private val key = AgentChatKey.parse("coder@test:main:5")
    private val thread = AgentChatKey.parse("coder@test:main:5#2")

    @Test
    fun `nothing stored reads as the agent's default`() {
        blocking {
            assertNull(settings.model(key))
            assertNull(settings.reasoning(key))
            assertNull(settings.language(key))
        }
        assertNull(settings.selectedAgent(key.chat))
    }

    @Test
    fun `the stored settings of a chat apply to its threads`() {
        blocking {
            stored.agentState(SettingsStates.MODEL, Choice<ModelRef>()).set(key, Choice(ModelRef.parse("local/llama")))
            stored.agentState(
                SettingsStates.REASONING,
                Choice<ReasoningEffort>(),
            ).set(key, Choice(ReasoningEffort.HIGH))
            stored.agentState(SettingsStates.LANGUAGE, Choice<LanguageTag>()).set(thread, Choice(LanguageTag("fr")))
            stored.state(SettingsStates.AGENT, Choice<AgentId>()).set(key.chat, Choice(AgentId("helper")))

            assertEquals(ModelRef.parse("local/llama"), settings.model(thread))
            assertEquals(ReasoningEffort.HIGH, settings.reasoning(thread))
            assertEquals(LanguageTag("fr"), settings.language(thread))
            assertNull(settings.language(key))
        }
        assertEquals(AgentId("helper"), settings.selectedAgent(thread.chat))
        assertEquals(AgentId("helper"), settings.selectedAgent(ChatAddress.parse("test:main:5")))
    }

    @Test
    fun `the agent a chat switched to is kept after its first read`() {
        val chat = key.chat
        val selected = stored.state(SettingsStates.AGENT, Choice<AgentId>())
        blocking { selected.set(chat, Choice(AgentId("helper"))) }

        assertEquals(AgentId("helper"), settings.selectedAgent(chat))
        blocking { selected.set(chat, Choice(AgentId("reviewer"))) }
        assertEquals(AgentId("helper"), settings.selectedAgent(chat))
    }
}
