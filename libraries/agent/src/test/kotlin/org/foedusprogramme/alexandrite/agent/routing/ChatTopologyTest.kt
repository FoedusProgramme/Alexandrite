package org.foedusprogramme.alexandrite.agent.routing

import org.foedusprogramme.alexandrite.agent.agentDirectory
import org.foedusprogramme.alexandrite.agent.i18n.Refused
import org.foedusprogramme.alexandrite.agent.provider
import org.foedusprogramme.alexandrite.agent.settings
import org.foedusprogramme.alexandrite.sdk.chat.AgentChatKey
import org.foedusprogramme.alexandrite.sdk.chat.AgentId
import org.foedusprogramme.alexandrite.sdk.chat.ChannelInstanceId
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.testkit.ScriptedModel
import org.foedusprogramme.alexandrite.testkit.testTexts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatTopologyTest {
    private val config = """
        {
          "agents": {
            "coder": {
              "model": "scripted/test-model",
              "channels": {"test:main": {"default": true, "home": "1"}, "test:solo": {}},
              "linkedChats": [["test:main:7", "test:solo:7", "test:main:8"]]
            },
            "helper": {
              "model": "scripted/test-model",
              "channels": {"test:main": {"home": "2"}, "test:solo": {"default": true}}
            },
            "planner": {"model": "scripted/test-model"}
          }
        }
    """.trimIndent()

    private val topology = ChatTopology(
        agentDirectory(settings(config), setOf("test:main", "test:solo"), listOf(provider(ScriptedModel()))),
    )
    private val coder = AgentId("coder")
    private val helper = AgentId("helper")
    private val main = ChannelInstanceId.parse("test:main")
    private val solo = ChannelInstanceId.parse("test:solo")

    private fun chat(text: String) = ChatAddress.parse(text)

    private fun group(anchor: String, vararg members: String) = LinkGroup(chat(anchor), members.map(::chat))

    private val texts = testTexts("alexandrite-agent")

    /** Asserts that [change] is refused with the English text [message]. */
    private fun refused(message: String, change: () -> Unit) {
        val refused = assertFailsWith<Refused> { change() }
        assertEquals(message, texts.text(refused.key, null, *refused.args))
    }

    private fun setHome(agent: AgentId, instance: ChannelInstanceId, chat: ChatAddress?) {
        topology.apply(topology.homeChange(agent, instance, chat))
    }

    private fun link(agent: AgentId, chat: ChatAddress, member: ChatAddress): LinkGroup? {
        topology.apply(topology.linkChange(agent, chat, member))
        return topology.group(agent, member)
    }

    private fun unlink(agent: AgentId, member: ChatAddress): Boolean =
        topology.unlinkChange(agent, member)?.also(topology::apply) != null

    @Test
    fun `the topology starts with the configured homes and groups`() {
        assertEquals(chat("test:main:1"), topology.home(coder, main))
        assertNull(topology.home(coder, solo))
        assertEquals(
            listOf(coder, coder, helper, null),
            listOf("test:main:1", "test:main:1#3", "test:main:2", "test:main:3").map { topology.homeAgent(chat(it)) },
        )
        assertEquals(listOf(group("test:main:7", "test:solo:7", "test:main:8")), topology.groups(coder))
        assertEquals(group("test:main:7", "test:solo:7", "test:main:8"), topology.group(coder, chat("test:solo:7")))
        assertEquals(
            listOf("coder@test:main:7", "coder@test:main:7", "coder@test:main:7#1", "helper@test:solo:7"),
            listOf(
                topology.key(coder, chat("test:main:7")),
                topology.key(coder, chat("test:solo:7")),
                topology.key(coder, chat("test:main:7#1")),
                topology.key(helper, chat("test:solo:7")),
            ).map(AgentChatKey::toString),
        )
    }

    @Test
    fun `a home is set per agent and instance and cleared with null`() {
        setHome(coder, main, chat("test:main:5"))
        setHome(coder, solo, chat("test:solo:4"))

        assertEquals(chat("test:main:5"), topology.home(coder, main))
        assertEquals(chat("test:solo:4"), topology.home(coder, solo))
        assertNull(topology.homeAgent(chat("test:main:1")))
        assertEquals(coder, topology.homeAgent(chat("test:main:5")))

        setHome(coder, main, chat("test:main:5"))
        setHome(coder, main, null)

        assertNull(topology.home(coder, main))
        assertNull(topology.homeAgent(chat("test:main:5")))
        assertEquals(chat("test:solo:4"), topology.home(coder, solo))
    }

    @Test
    fun `a home that breaks a rule is refused and changes nothing`() {
        refused("test:main:2 is the home chat of agent 'helper'.") {
            setHome(coder, main, chat("test:main:2"))
        }
        refused("test:solo:5 is no chat of test:main.") { setHome(coder, main, chat("test:solo:5")) }
        refused("Agent 'planner' does not serve test:main.") {
            setHome(AgentId("planner"), main, chat("test:main:5"))
        }
        refused("Agent 'other' does not serve test:main.") { setHome(AgentId("other"), main, null) }

        assertEquals(chat("test:main:1"), topology.home(coder, main))
        assertEquals(helper, topology.homeAgent(chat("test:main:2")))
    }

    @Test
    fun `a link starts a group at the chat or joins the chat's group`() {
        assertEquals(
            group("test:main:5", "test:solo:5"),
            link(helper, chat("test:main:5"), chat("test:solo:5")),
        )
        link(coder, chat("test:solo:7"), chat("test:main:9"))
        link(coder, chat("test:main:7"), chat("test:solo:9"))
        link(helper, chat("test:main:7"), chat("test:solo:7"))

        assertEquals(
            listOf(group("test:main:7", "test:solo:7", "test:main:8", "test:main:9", "test:solo:9")),
            topology.groups(coder),
        )
        assertEquals(
            listOf(group("test:main:5", "test:solo:5"), group("test:main:7", "test:solo:7")),
            topology.groups(helper),
        )
        assertEquals(AgentChatKey.parse("helper@test:main:5"), topology.key(helper, chat("test:solo:5")))
        assertEquals(AgentChatKey.parse("coder@test:main:7"), topology.key(coder, chat("test:solo:9")))
    }

    @Test
    fun `a link that breaks a rule is refused and changes nothing`() {
        val already = "test:main:8 is linked to test:main:7 already: unlink it first."

        refused(already) { link(coder, chat("test:main:5"), chat("test:main:8")) }
        refused("test:main:7 is linked to test:main:7 already: unlink it first.") {
            link(coder, chat("test:main:5"), chat("test:main:7"))
        }
        refused("A chat cannot be linked to itself.") { link(coder, chat("test:main:5"), chat("test:main:5")) }
        refused("Agent 'planner' does not serve test:main.") {
            link(AgentId("planner"), chat("test:main:5"), chat("test:main:6"))
        }
        refused("Agent 'coder' does not serve test:quiet.") {
            link(coder, chat("test:main:5"), chat("test:quiet:6"))
        }

        assertEquals(listOf(group("test:main:7", "test:solo:7", "test:main:8")), topology.groups(coder))
    }

    @Test
    fun `a checked change changes nothing until it is applied, and only onto the topology it was checked against`() {
        val home = topology.homeChange(coder, main, chat("test:main:5"))
        val linked = topology.linkChange(coder, chat("test:main:5"), chat("test:main:6"))

        assertEquals(chat("test:main:1"), topology.home(coder, main))
        assertEquals(chat("test:main:5"), home.after.home(coder, main))
        assertNull(topology.group(coder, chat("test:main:6")))
        topology.apply(home)
        assertFailsWith<IllegalStateException> { topology.apply(linked) }

        assertEquals(chat("test:main:5"), topology.home(coder, main))
        assertNull(topology.group(coder, chat("test:main:6")))
        assertNull(topology.unlinkChange(coder, chat("test:main:6")))
    }

    @Test
    fun `an unlinked chat leaves its group, which dissolves with its last member`() {
        assertTrue(unlink(coder, chat("test:main:8")))
        assertEquals(listOf(group("test:main:7", "test:solo:7")), topology.groups(coder))
        refused("test:main:7 anchors its group: unlink the other chats first.") {
            unlink(coder, chat("test:main:7"))
        }

        assertTrue(unlink(coder, chat("test:solo:7")))

        assertEquals(emptyList(), topology.groups(coder))
        assertEquals(AgentChatKey.parse("coder@test:solo:7"), topology.key(coder, chat("test:solo:7")))
        assertFalse(unlink(coder, chat("test:main:7")))
        assertFalse(unlink(helper, chat("test:main:8")))
    }
}
