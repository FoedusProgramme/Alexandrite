package org.unitedsoftware.alexandrite.agent.control

import org.unitedsoftware.alexandrite.agent.blocking
import org.unitedsoftware.alexandrite.sdk.chat.AgentChatKey
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ChatSettingsUpdate
import org.unitedsoftware.alexandrite.sdk.chat.LanguageTag
import org.unitedsoftware.alexandrite.sdk.chat.SettingChange
import org.unitedsoftware.alexandrite.sdk.chat.agentState
import org.unitedsoftware.alexandrite.sdk.model.ReasoningEffort
import org.unitedsoftware.alexandrite.sdk.permission.PermissionMode
import org.unitedsoftware.alexandrite.sdk.transcript.ModelRef
import org.unitedsoftware.alexandrite.testkit.TEST_TIME
import org.unitedsoftware.alexandrite.testkit.TestChatStates
import org.unitedsoftware.alexandrite.testkit.testRecords
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SettingsStatesTest {
    private val stored = TestChatStates().of("alexandrite-agent")
    private val records = testRecords()
    private val settings = SettingsStates(stored, records)
    private val key = AgentChatKey.parse("coder@test:main:5")
    private val thread = AgentChatKey.parse("coder@test:main:5#2")

    @Test
    fun `the settings stored under their keys apply to the chat and its threads`() {
        blocking {
            stored.agentState("settings.model", Choice<ModelRef>()).set(key, Choice(ModelRef.parse("local/llama")))
            stored.agentState("settings.reasoning", Choice<ReasoningEffort>()).set(key, Choice(ReasoningEffort.HIGH))
            stored.agentState("settings.language", Choice<LanguageTag>()).set(thread, Choice(LanguageTag("fr")))
            settings.selectAgent(key.chat, SettingChange.SetTo(AgentId("helper")))

            assertEquals(ModelRef.parse("local/llama"), settings.model(thread))
            assertEquals(ReasoningEffort.HIGH, settings.reasoning(thread))
            assertEquals(LanguageTag("fr"), settings.language(thread))
            assertNull(settings.language(key))
            assertEquals(AgentId("helper"), settings.selectedAgent(thread.chat))
            assertEquals(AgentId("helper"), settings.selectedAgent(ChatAddress.parse("test:main:5")))
        }
    }

    @Test
    fun `a mode applies to the chat and its threads, a bypass until it ends, an unknown mode never`() {
        val end = TEST_TIME.plusSeconds(60)
        fun read(at: AgentChatKey, now: Instant = TEST_TIME) =
            blocking { settings.mode(at, now) }?.let { it.mode to it.until }

        blocking { settings.changeMode(key, SettingChange.SetTo(PermissionMode.BYPASS), end) }
        val bypass = listOf(read(thread), read(key, end.minusMillis(1)), read(key, end))
        blocking { settings.changeMode(key, SettingChange.SetTo(PermissionMode.PLAN), null) }
        val plan = read(thread)
        val states = stored.agentState(SettingsStates.MODE, ModeChoice())
        blocking { states.set(key, ModeChoice(PermissionMode.of("yolo"))) }
        val unknown = read(key)
        blocking { states.set(key, ModeChoice(PermissionMode.BYPASS)) }
        val endless = read(key)
        blocking { settings.changeMode(key, SettingChange.Reset, null) }

        assertEquals(listOf(PermissionMode.BYPASS to end, PermissionMode.BYPASS to end, null), bypass)
        assertEquals(PermissionMode.PLAN to null, plan)
        assertEquals(listOf(null, null, null), listOf(unknown, endless, read(key)))
        assertNull(blocking { states.getOwn(key) })
    }

    @Test
    fun `the agents that chats switched to are kept and read at start, and a thread follows its chat's`() {
        val chat = key.chat
        blocking {
            settings.selectAgent(chat, SettingChange.SetTo(AgentId("helper")))
            settings.selectAgent(ChatAddress.parse("test:main:6"), SettingChange.SetTo(AgentId("reviewer")))
            settings.selectAgent(ChatAddress.parse("test:main:6"), SettingChange.Reset)
            settings.selectAgent(thread.chat, SettingChange.Keep)
        }
        val inherited = settings.selectedAgent(thread.chat)
        blocking { settings.selectAgent(thread.chat, SettingChange.SetTo(AgentId("reviewer"))) }
        val restarted = SettingsStates(stored, records).also { blocking { it.onStart() } }

        assertEquals(AgentId("helper"), inherited)
        assertEquals(
            listOf(AgentId("helper"), AgentId("reviewer"), null),
            listOf(chat, thread.chat, ChatAddress.parse("test:main:6")).map(restarted::selectedAgent),
        )
        blocking { restarted.selectAgent(chat, SettingChange.Reset) }
        assertNull(restarted.selectedAgent(chat))
        assertEquals(AgentId("reviewer"), restarted.selectedAgent(thread.chat))
    }

    @Test
    fun `an update stores the changes it makes and leaves the rest`() {
        blocking {
            settings.update(key, ChatSettingsUpdate.builder().model(ModelRef.parse("local/llama")).build())
            settings.update(
                key,
                ChatSettingsUpdate.builder().reasoning(ReasoningEffort.LOW).language(LanguageTag("fr")).build(),
            )
            settings.update(key, ChatSettingsUpdate.builder().resetReasoning().build())

            assertEquals(ModelRef.parse("local/llama"), settings.model(key))
            assertNull(settings.reasoning(key))
            assertEquals(LanguageTag("fr"), settings.language(key))
        }
    }
}
