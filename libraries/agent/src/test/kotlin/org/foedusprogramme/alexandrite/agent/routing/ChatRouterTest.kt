package org.foedusprogramme.alexandrite.agent.routing

import org.foedusprogramme.alexandrite.agent.agentDirectory
import org.foedusprogramme.alexandrite.agent.blocking
import org.foedusprogramme.alexandrite.agent.control.Choice
import org.foedusprogramme.alexandrite.agent.control.SettingsStates
import org.foedusprogramme.alexandrite.agent.logged
import org.foedusprogramme.alexandrite.agent.provider
import org.foedusprogramme.alexandrite.agent.settings
import org.foedusprogramme.alexandrite.runtime.chat.MemoryChatStateStore
import org.foedusprogramme.alexandrite.runtime.chat.standaloneChatStates
import org.foedusprogramme.alexandrite.sdk.channel.Channel
import org.foedusprogramme.alexandrite.sdk.channel.ChannelDirectory
import org.foedusprogramme.alexandrite.sdk.chat.AgentChatKey
import org.foedusprogramme.alexandrite.sdk.chat.AgentId
import org.foedusprogramme.alexandrite.sdk.chat.ChannelInstanceId
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.chat.state
import org.foedusprogramme.alexandrite.sdk.store.ChatStateStore
import org.foedusprogramme.alexandrite.testkit.ScriptedModel
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

class ChatRouterTest {
    private val config = """
        {
          "agents": {
            "coder": {"model": "scripted/test-model", "channels": {"test:main": {"default": true, "home": "1"}}},
            "helper": {
              "model": "scripted/test-model",
              "channels": {"test:main": {"home": "2#7"}, "test:solo": {"default": true}}
            },
            "reviewer": {"model": "scripted/test-model", "channels": {"test:main": {}}},
            "planner": {"model": "scripted/test-model"}
          }
        }
    """.trimIndent()

    private val reads = AtomicInteger()
    private val store = object : ChatStateStore {
        private val memory = MemoryChatStateStore()

        override suspend fun read(plugin: String, name: String, agent: AgentId?, chat: ChatAddress): String? {
            reads.incrementAndGet()
            return memory.read(plugin, name, agent, chat)
        }

        override suspend fun write(plugin: String, name: String, agent: AgentId?, chat: ChatAddress, json: String?) {
            memory.write(plugin, name, agent, chat, json)
        }
    }
    private val states = standaloneChatStates("alexandrite-agent", store)
    private val router = router()

    private fun router(): ChatRouter {
        val directory = agentDirectory(settings(config), INSTANCES, listOf(provider(ScriptedModel())))
        val channels = object : ChannelDirectory {
            override val instances = INSTANCES.map(ChannelInstanceId::parse).toSet()

            override fun channel(instance: ChannelInstanceId): Channel? = null
        }
        return ChatRouter(channels, directory, SettingsStates(states))
    }

    private fun select(chat: String, agent: String) {
        val state = states.state(SettingsStates.AGENT, Choice<AgentId>())
        blocking { state.set(ChatAddress.parse(chat), Choice(AgentId(agent))) }
    }

    private fun resolve(chat: String): String = when (val resolution = router.resolve(ChatAddress.parse(chat))) {
        is Resolution.Served -> resolution.key.toString()
        Resolution.Unknown -> "unknown"
        Resolution.NoAgent -> "no agent"
    }

    @Test
    fun `a chat of an instance that is not configured is unknown`() {
        assertEquals("unknown", resolve("test:other:1"))
    }

    @Test
    fun `a chat of an instance that no agent serves has no agent`() {
        assertEquals("no agent", resolve("test:quiet:1"))
    }

    @Test
    fun `a chat goes to its instance's default agent`() {
        assertEquals("coder@test:main:5", resolve("test:main:5"))
        assertEquals("coder@test:main:5#3", resolve("test:main:5#3"))
        assertEquals("helper@test:solo:5", resolve("test:solo:5"))
    }

    @Test
    fun `a home chat and its threads go to their agent whatever they switched to`() {
        select("test:main:1", "reviewer")
        select("test:main:2#7", "reviewer")

        assertEquals("coder@test:main:1", resolve("test:main:1"))
        assertEquals("coder@test:main:1#9", resolve("test:main:1#9"))
        assertEquals("helper@test:main:2#7", resolve("test:main:2#7"))
        assertEquals("coder@test:main:2", resolve("test:main:2"))
    }

    @Test
    fun `a chat goes to the agent it switched to, and its threads too`() {
        select("test:main:5", "reviewer")
        select("test:main:6#1", "helper")

        assertEquals("reviewer@test:main:5", resolve("test:main:5"))
        assertEquals("reviewer@test:main:5#3", resolve("test:main:5#3"))
        assertEquals("helper@test:main:6#1", resolve("test:main:6#1"))
        assertEquals("coder@test:main:6", resolve("test:main:6"))
    }

    @Test
    fun `a switch to an agent that no longer serves the instance is ignored with one warning per chat`() {
        select("test:main:5", "planner")
        select("test:main:6", "planner")

        val lines = logged { repeat(2) { listOf("test:main:5", "test:main:6").forEach(::resolve) } }

        assertEquals("coder@test:main:5", resolve("test:main:5"))
        assertEquals(
            listOf("test:main:5", "test:main:6").map {
                "WARN Chat $it switched to agent 'planner', which no longer serves test:main: the chat goes to its " +
                    "default agent"
            },
            lines,
        )
    }

    @Test
    fun `the switch of a chat is read once and not at all where one agent serves the instance`() {
        repeat(3) { resolve("test:main:5") }
        repeat(3) { resolve("test:solo:5") }
        resolve("test:main:1")

        assertEquals(1, reads.get())
    }

    @Test
    fun `a resolved chat keys the agent at the chat itself`() {
        val resolution = router.resolve(ChatAddress.parse("test:main:5#3"))

        assertEquals(Resolution.Served(AgentChatKey.parse("coder@test:main:5#3")), resolution)
    }

    private companion object {
        val INSTANCES = setOf("test:main", "test:solo", "test:quiet")
    }
}
