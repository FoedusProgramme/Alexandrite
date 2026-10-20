package org.unitedsoftware.alexandrite.agent.routing

import org.unitedsoftware.alexandrite.agent.agentDirectory
import org.unitedsoftware.alexandrite.agent.blocking
import org.unitedsoftware.alexandrite.agent.control.SettingsStates
import org.unitedsoftware.alexandrite.agent.logged
import org.unitedsoftware.alexandrite.agent.provider
import org.unitedsoftware.alexandrite.agent.settings
import org.unitedsoftware.alexandrite.runtime.chat.MemoryChatStateStore
import org.unitedsoftware.alexandrite.runtime.chat.standaloneChatStates
import org.unitedsoftware.alexandrite.sdk.channel.Channel
import org.unitedsoftware.alexandrite.sdk.channel.ChannelDirectory
import org.unitedsoftware.alexandrite.sdk.chat.AgentChatKey
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.AgentSource
import org.unitedsoftware.alexandrite.sdk.chat.ChannelInstanceId
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.SettingChange
import org.unitedsoftware.alexandrite.testkit.ScriptedModel
import org.unitedsoftware.alexandrite.testkit.testRecords
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatRouterTest {
    private val config = """
        {
          "agents": {
            "coder": {
              "model": "scripted/test-model",
              "channels": {"test:main": {"default": true}, "test:solo": {}},
              "linkedChats": [["test:main:7", "test:solo:7"]]
            },
            "helper": {
              "model": "scripted/test-model",
              "channels": {"test:main": {"home": "2#7"}, "test:solo": {"default": true}}
            },
            "reviewer": {"model": "scripted/test-model", "channels": {"test:main": {"home": "1"}}},
            "planner": {"description": "Plans.", "model": "scripted/test-model"},
            "loner": {"model": "scripted/test-model", "channels": {"test:lone": {}}}
          }
        }
    """.trimIndent()

    private val states = standaloneChatStates("alexandrite-agent", MemoryChatStateStore())
    private val records = testRecords()
    private val selections = SettingsStates(states, records)
    private val directory = agentDirectory(settings(config), INSTANCES, listOf(provider(ScriptedModel())))
    private val topology = ChatTopology(directory, testRecords())
    private val router = ChatRouter(channels(), directory, topology, selections)

    private fun channels() = object : ChannelDirectory {
        override val instances = INSTANCES.map(ChannelInstanceId::parse).toSet()

        override fun channel(instance: ChannelInstanceId): Channel? = null
    }

    private fun select(chat: String, agent: String) {
        blocking { selections.selectAgent(ChatAddress.parse(chat), SettingChange.SetTo(AgentId(agent))) }
    }

    private fun resolve(chat: String): String = describe(router.resolve(ChatAddress.parse(chat)))

    private fun source(chat: String): AgentSource =
        (router.resolve(ChatAddress.parse(chat)) as Resolution.Served).source

    private fun describe(resolution: Resolution): String = when (resolution) {
        is Resolution.Served -> resolution.key.toString()
        Resolution.Unknown -> "unknown"
        Resolution.NoAgent -> "no agent"
    }

    @Test
    fun `a chat of an instance that no agent serves has no agent, and one that is not configured is unknown`() {
        assertEquals("no agent", resolve("test:quiet:1"))
        assertEquals("unknown", resolve("test:other:1"))
    }

    @Test
    fun `a chat goes to its instance's default agent`() {
        assertEquals("coder@test:main:5", resolve("test:main:5"))
        assertEquals("coder@test:main:5#3", resolve("test:main:5#3"))
        assertEquals("helper@test:solo:5", resolve("test:solo:5"))
        assertEquals("loner@test:lone:5", resolve("test:lone:5"))
    }

    @Test
    fun `a home chat and its threads go to their agent while they switched to none`() {
        assertEquals("reviewer@test:main:1", resolve("test:main:1"))
        assertEquals("reviewer@test:main:1#9", resolve("test:main:1#9"))
        assertEquals("helper@test:main:2#7", resolve("test:main:2#7"))
        assertEquals("coder@test:main:2", resolve("test:main:2"))
        assertEquals("coder@test:main:2#8", resolve("test:main:2#8"))
    }

    @Test
    fun `a chat goes to the agent it switched to, a home chat and threads too`() {
        select("test:main:5", "reviewer")
        select("test:main:1", "helper")
        select("test:main:6#1", "helper")

        assertEquals("reviewer@test:main:5", resolve("test:main:5"))
        assertEquals("reviewer@test:main:5#3", resolve("test:main:5#3"))
        assertEquals("helper@test:main:1", resolve("test:main:1"))
        assertEquals("helper@test:main:1#9", resolve("test:main:1#9"))
        assertEquals("helper@test:main:6#1", resolve("test:main:6#1"))
        assertEquals("coder@test:main:6", resolve("test:main:6"))
    }

    @Test
    fun `a resolution names the rule that chose its agent`() {
        select("test:main:5", "coder")
        select("test:lone:5", "loner")

        val reset = router.resolveAs(ChatAddress.parse("test:main:5"), null) as Resolution.Served

        assertEquals(AgentSource.SELECTED, source("test:main:5"))
        assertEquals(AgentSource.SELECTED, source("test:main:5#3"))
        assertEquals(AgentSource.HOME, source("test:main:1"))
        assertEquals(AgentSource.HOME, source("test:main:2#7"))
        assertEquals(AgentSource.DEFAULT, source("test:main:2"))
        assertEquals(AgentSource.DEFAULT, source("test:lone:5"))
        assertEquals(AgentSource.DEFAULT, reset.source)
    }

    @Test
    fun `a switch to an agent that no longer serves the instance is ignored with one warning per chat`() {
        select("test:main:5", "planner")
        select("test:main:1", "planner")

        val lines = logged { repeat(2) { listOf("test:main:5", "test:main:1").forEach(::resolve) } }

        assertEquals("coder@test:main:5", resolve("test:main:5"))
        assertEquals("reviewer@test:main:1", resolve("test:main:1"))
        assertEquals(2, lines.size, "$lines")
        assertTrue(lines.all { it.startsWith("WARN") && "'planner'" in it })
    }

    @Test
    fun `the switches of an earlier run are read at start, and a reset goes back to the home or default agent`() {
        select("test:main:5", "reviewer")
        select("test:main:1", "helper")
        blocking { selections.selectAgent(ChatAddress.parse("test:main:1"), SettingChange.Reset) }
        val restarted = SettingsStates(states, records).also { blocking { it.onStart() } }
        val again = ChatRouter(channels(), directory, topology, restarted)

        assertEquals(
            listOf("reviewer@test:main:5", "reviewer@test:main:5#3", "reviewer@test:main:1"),
            listOf("test:main:5", "test:main:5#3", "test:main:1").map {
                describe(again.resolve(ChatAddress.parse(it)))
            },
        )
    }

    @Test
    fun `a linked chat keys its agent at the group's anchor and keeps where it came from`() {
        select("test:solo:7", "coder")
        val member = ChatAddress.parse("test:solo:7")

        assertEquals(
            Resolution.Served(AgentChatKey.parse("coder@test:main:7"), member, AgentSource.SELECTED),
            router.resolve(member),
        )
        assertEquals("coder@test:main:7", resolve("test:main:7"))
        assertEquals("coder@test:main:7#1", resolve("test:main:7#1"))
        assertEquals("helper@test:solo:8", resolve("test:solo:8"))
    }

    @Test
    fun `a change of a home chat or a group applies to the next resolution`() {
        blocking {
            topology.changeHome(AgentId("helper"), ChatAddress.parse("test:main:5"), SettingChange.SetTo(true), null)
            topology.changeHome(AgentId("reviewer"), ChatAddress.parse("test:main:1"), SettingChange.SetTo(false), null)
            topology.link(AgentId("coder"), ChatAddress.parse("test:solo:6"), ChatAddress.parse("test:main:9"), null)
        }

        assertEquals("helper@test:main:5", resolve("test:main:5"))
        assertEquals("coder@test:main:1", resolve("test:main:1"))
        assertEquals("coder@test:solo:6", resolve("test:main:9"))
    }

    private companion object {
        val INSTANCES = setOf("test:main", "test:solo", "test:quiet", "test:lone")
    }
}
