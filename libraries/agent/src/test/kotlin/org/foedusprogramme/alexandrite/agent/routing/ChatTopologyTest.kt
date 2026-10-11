package org.foedusprogramme.alexandrite.agent.routing

import org.foedusprogramme.alexandrite.agent.agentDirectory
import org.foedusprogramme.alexandrite.agent.blocking
import org.foedusprogramme.alexandrite.agent.i18n.Refused
import org.foedusprogramme.alexandrite.agent.logged
import org.foedusprogramme.alexandrite.agent.provider
import org.foedusprogramme.alexandrite.agent.settings
import org.foedusprogramme.alexandrite.sdk.chat.AgentChatKey
import org.foedusprogramme.alexandrite.sdk.chat.AgentId
import org.foedusprogramme.alexandrite.sdk.chat.ChannelInstanceId
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.testkit.ScriptedModel
import org.foedusprogramme.alexandrite.testkit.TestRecords
import org.foedusprogramme.alexandrite.testkit.testTexts
import org.foedusprogramme.alexandrite.testkit.testUser
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

    private val records = TestRecords()
    private val topology = started()
    private val coder = AgentId("coder")
    private val helper = AgentId("helper")
    private val main = ChannelInstanceId.parse("test:main")
    private val solo = ChannelInstanceId.parse("test:solo")
    private val admin = testUser("ada", isAdmin = true)
    private val texts = testTexts("alexandrite-agent")

    private fun topology(config: String = this.config): ChatTopology {
        val instances = setOf("test:main", "test:solo")
        return ChatTopology(
            agentDirectory(settings(config), instances, listOf(provider(ScriptedModel()))),
            records.of(PLUGIN),
        )
    }

    /** A topology of [config] over the records kept so far, once it started. */
    private fun started(config: String = this.config): ChatTopology =
        topology(config).also { blocking { it.onStart() } }

    private fun chat(text: String) = ChatAddress.parse(text)

    private fun group(anchor: String, vararg members: String) = LinkGroup(chat(anchor), members.map(::chat))

    private fun declared(anchor: String, vararg members: String) = group(anchor, *members).copy(declared = true)

    /** Asserts that [change] is refused with the English text [message]. */
    private fun refused(message: String, change: suspend () -> Unit) {
        val refused = assertFailsWith<Refused> { blocking { change() } }
        assertEquals(message, texts.text(refused.key, null, *refused.args))
    }

    private fun setHome(agent: AgentId, instance: ChannelInstanceId, chat: ChatAddress?) {
        blocking { topology.setHome(agent, instance, chat, admin) }
    }

    private fun link(agent: AgentId, chat: ChatAddress, member: ChatAddress): LinkGroup =
        blocking { topology.link(agent, chat, member, admin) }

    private fun unlink(agent: AgentId, member: ChatAddress): Boolean = blocking { topology.unlink(agent, member) }

    /** The records of [collection] as `<key> <value> <who>`. */
    private fun stored(collection: String): List<String> =
        blocking { records.store.list(PLUGIN, collection) }.map { "${it.key} ${it.value} ${it.createdBy}" }

    @Test
    fun `the topology starts with the configured homes and groups`() {
        assertEquals(chat("test:main:1"), topology.home(coder, main))
        assertNull(topology.home(coder, solo))
        assertEquals(
            listOf(coder, coder, helper, null),
            listOf("test:main:1", "test:main:1#3", "test:main:2", "test:main:3").map { topology.homeAgent(chat(it)) },
        )
        assertEquals(listOf(declared("test:main:7", "test:solo:7", "test:main:8")), topology.groups(coder))
        assertEquals(declared("test:main:7", "test:solo:7", "test:main:8"), topology.group(coder, chat("test:solo:7")))
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
        assertEquals(emptyList(), stored("homes"))
    }

    @Test
    fun `a link starts a group at the chat or joins the chat's group`() {
        assertEquals(group("test:main:5", "test:solo:5"), link(helper, chat("test:main:5"), chat("test:solo:5")))
        link(coder, chat("test:solo:9"), chat("test:main:9"))
        link(coder, chat("test:main:9"), chat("test:solo:10"))
        link(helper, chat("test:main:7"), chat("test:solo:7"))

        assertEquals(
            listOf(
                declared("test:main:7", "test:solo:7", "test:main:8"),
                group("test:solo:9", "test:main:9", "test:solo:10"),
            ),
            topology.groups(coder),
        )
        assertEquals(
            listOf(group("test:main:5", "test:solo:5"), group("test:main:7", "test:solo:7")),
            topology.groups(helper),
        )
        assertEquals(AgentChatKey.parse("helper@test:main:5"), topology.key(helper, chat("test:solo:5")))
        assertEquals(AgentChatKey.parse("coder@test:solo:9"), topology.key(coder, chat("test:solo:10")))
    }

    @Test
    fun `a link that breaks a rule is refused and changes nothing`() {
        link(coder, chat("test:main:5"), chat("test:main:6"))

        refused("test:main:6 is linked to test:main:5 already: unlink it first.") {
            link(coder, chat("test:main:9"), chat("test:main:6"))
        }
        refused("test:main:5 is linked to test:main:5 already: unlink it first.") {
            link(coder, chat("test:main:9"), chat("test:main:5"))
        }
        refused("A chat cannot be linked to itself.") { link(coder, chat("test:main:9"), chat("test:main:9")) }
        refused("Agent 'planner' does not serve test:main.") {
            link(AgentId("planner"), chat("test:main:5"), chat("test:main:9"))
        }
        refused("Agent 'coder' does not serve test:quiet.") {
            link(coder, chat("test:main:5"), chat("test:quiet:6"))
        }

        assertEquals(
            listOf(declared("test:main:7", "test:solo:7", "test:main:8"), group("test:main:5", "test:main:6")),
            topology.groups(coder),
        )
        assertEquals(listOf("coder@test:main:6"), stored("links").map { it.substringBefore(' ') })
    }

    @Test
    fun `a group that the config declares changes only in the config`() {
        val declaredGroup = "is in a group that the config file declares: edit the config file to change the group."

        refused("test:main:8 $declaredGroup") { link(coder, chat("test:main:5"), chat("test:main:8")) }
        refused("test:main:7 $declaredGroup") { link(coder, chat("test:main:7"), chat("test:main:5")) }
        refused("test:solo:7 $declaredGroup") { link(coder, chat("test:solo:7"), chat("test:main:5")) }
        refused("test:main:8 $declaredGroup") { unlink(coder, chat("test:main:8")) }
        refused("test:main:7 $declaredGroup") { unlink(coder, chat("test:main:7")) }

        assertEquals(listOf(declared("test:main:7", "test:solo:7", "test:main:8")), topology.groups(coder))
        assertEquals(emptyList(), stored("links"))
    }

    @Test
    fun `an unlinked chat leaves its group, which dissolves with its last member`() {
        link(coder, chat("test:main:5"), chat("test:main:6"))
        link(coder, chat("test:main:5"), chat("test:solo:6"))

        assertTrue(unlink(coder, chat("test:main:6")))
        assertEquals(group("test:main:5", "test:solo:6"), topology.group(coder, chat("test:main:5")))
        refused("test:main:5 anchors its group: unlink the other chats first.") {
            unlink(coder, chat("test:main:5"))
        }

        assertTrue(unlink(coder, chat("test:solo:6")))

        assertNull(topology.group(coder, chat("test:main:5")))
        assertEquals(AgentChatKey.parse("coder@test:solo:6"), topology.key(coder, chat("test:solo:6")))
        assertFalse(unlink(coder, chat("test:main:5")))
        assertFalse(unlink(helper, chat("test:main:8")))
        assertEquals(emptyList(), stored("links"))
    }

    // Records.

    @Test
    fun `each change is a record of who made it, which the next start loads over the config`() {
        setHome(coder, main, chat("test:main:5"))
        setHome(helper, main, null)
        link(coder, chat("test:main:5"), chat("test:solo:5"))

        val restarted = started()

        assertEquals(chat("test:main:5"), restarted.home(coder, main))
        assertNull(restarted.home(helper, main))
        assertEquals(group("test:main:5", "test:solo:5"), restarted.group(coder, chat("test:solo:5")))
        assertEquals(declared("test:main:7", "test:solo:7", "test:main:8"), restarted.group(coder, chat("test:main:8")))
        assertEquals(
            listOf(
                "coder@test:main {\"agent\":\"coder\",\"instance\":\"test:main\",\"chat\":\"test:main:5\"} " +
                    "${admin.address}",
                "helper@test:main {\"agent\":\"helper\",\"instance\":\"test:main\",\"chat\":null} ${admin.address}",
            ),
            stored("homes"),
        )
        assertEquals(
            listOf(
                "coder@test:solo:5 {\"agent\":\"coder\",\"member\":\"test:solo:5\",\"anchor\":\"test:main:5\"} " +
                    "${admin.address}",
            ),
            stored("links"),
        )
    }

    @Test
    fun `a home set back to the configured chat leaves no record`() {
        setHome(coder, main, chat("test:main:5"))
        setHome(coder, main, chat("test:main:1"))
        setHome(helper, main, null)
        setHome(helper, main, chat("test:main:2"))

        assertEquals(emptyList(), stored("homes"))
        assertEquals(chat("test:main:1"), started().home(coder, main))
    }

    @Test
    fun `until it starts a topology knows only the config`() {
        setHome(coder, main, chat("test:main:5"))

        assertEquals(chat("test:main:1"), topology().home(coder, main))
    }

    @Test
    fun `a recorded change that the config no longer allows is ignored with a warning`() {
        setHome(coder, solo, chat("test:solo:4"))
        setHome(coder, main, chat("test:main:3"))
        link(coder, chat("test:main:5"), chat("test:main:9"))
        link(coder, chat("test:main:5"), chat("test:solo:9"))
        val changed = """
            {
              "agents": {
                "coder": {
                  "model": "scripted/test-model",
                  "channels": {"test:main": {"default": true}},
                  "linkedChats": [["test:main:3", "test:main:9"]]
                },
                "helper": {"model": "scripted/test-model", "channels": {"test:main": {"home": "3"}}}
              }
            }
        """.trimIndent()

        lateinit var restarted: ChatTopology
        val warnings = logged { restarted = started(changed) }.filter { it.startsWith("WARN") }

        assertNull(restarted.home(coder, main))
        assertEquals(helper, restarted.homeAgent(chat("test:main:3")))
        assertEquals(listOf(declared("test:main:3", "test:main:9")), restarted.groups(coder))
        assertEquals(
            listOf(
                "WARN The home chat test:solo:4 of agent 'coder' is ignored: agent 'coder' does not serve test:solo",
                "WARN The home chat test:main:3 of agent 'coder' is ignored: it is the home chat of agent 'helper'",
                "WARN The link of test:main:9 to test:main:5 at agent 'coder' is ignored: it is in another group",
                "WARN The link of test:solo:9 to test:main:5 at agent 'coder' is ignored: agent 'coder' does not " +
                    "serve both chats",
            ),
            warnings,
        )
    }

    private companion object {
        const val PLUGIN = "alexandrite-agent"
    }
}
