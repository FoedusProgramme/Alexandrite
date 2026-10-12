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
import org.foedusprogramme.alexandrite.sdk.turn.Revocation
import org.foedusprogramme.alexandrite.sdk.turn.SettingChange
import org.foedusprogramme.alexandrite.testkit.ScriptedModel
import org.foedusprogramme.alexandrite.testkit.TestRecords
import org.foedusprogramme.alexandrite.testkit.testTexts
import org.foedusprogramme.alexandrite.testkit.testUser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

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
            "planner": {"description": "Plans.", "model": "scripted/test-model"}
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

    private fun change(agent: AgentId, at: String, change: SettingChange<Boolean>) {
        blocking { topology.changeHome(agent, chat(at), change, admin) }
    }

    private fun home(agent: AgentId, at: String) = change(agent, at, SettingChange.SetTo(true))

    private fun noHome(agent: AgentId, at: String) = change(agent, at, SettingChange.SetTo(false))

    private fun resetHome(agent: AgentId, at: String) = change(agent, at, SettingChange.Reset)

    private fun link(agent: AgentId, chat: ChatAddress, member: ChatAddress): LinkGroup =
        blocking { topology.link(agent, chat, member, admin) }

    private fun unlink(agent: AgentId, member: ChatAddress, topology: ChatTopology = this.topology): Revocation =
        blocking { topology.unlink(agent, member) }

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
    fun `a home moves to the chat that takes it, and only the home or its thread leaves the instance without one`() {
        home(coder, "test:main:5")
        home(coder, "test:solo:4")

        assertEquals(chat("test:main:5"), topology.home(coder, main))
        assertEquals(chat("test:solo:4"), topology.home(coder, solo))
        assertNull(topology.homeAgent(chat("test:main:1")))
        assertEquals(coder, topology.homeAgent(chat("test:main:5")))

        noHome(coder, "test:main:6")
        assertEquals(chat("test:main:5"), topology.home(coder, main))
        noHome(coder, "test:main:5#2")

        assertNull(topology.home(coder, main))
        assertNull(topology.homeAgent(chat("test:main:5")))
        assertEquals(chat("test:solo:4"), topology.home(coder, solo))
    }

    @Test
    fun `a chat that is the home already, or a thread of it, keeps the home as it is`() {
        home(coder, "test:main:1#3")
        home(coder, "test:main:1")

        assertEquals(chat("test:main:1"), topology.home(coder, main))
        assertEquals(coder, topology.homeAgent(chat("test:main:1#4")))
        assertEquals(emptyList(), stored("homes"))
    }

    @Test
    fun `a home that breaks a rule is refused and changes nothing`() {
        refused("test:main:2 is the home chat of agent 'helper'.") { home(coder, "test:main:2") }
        refused("Agent 'planner' does not serve test:main.") { home(AgentId("planner"), "test:main:5") }
        refused("Agent 'other' does not serve test:main.") { noHome(AgentId("other"), "test:main:5") }

        assertEquals(chat("test:main:1"), topology.home(coder, main))
        assertEquals(helper, topology.homeAgent(chat("test:main:2")))
        assertEquals(emptyList(), stored("homes"))
    }

    @Test
    fun `a reset from any chat of the instance drops the home set at runtime, also one left without a home`() {
        noHome(coder, "test:main:1")
        resetHome(coder, "test:main:9")
        val afterOff = topology.home(coder, main)
        home(coder, "test:main:5")
        resetHome(coder, "test:main:5")
        resetHome(coder, "test:main:5")

        assertEquals(listOf(chat("test:main:1"), chat("test:main:1")), listOf(afterOff, topology.home(coder, main)))
        assertEquals(emptyList(), stored("homes"))
        assertNull(topology.homeGrant(coder, main))
    }

    @Test
    fun `a reset that would give back a configured home another agent took is refused and changes nothing`() {
        noHome(coder, "test:main:1")
        home(helper, "test:main:1")

        refused("test:main:1 is the home chat of agent 'helper'.") { resetHome(coder, "test:main:9") }

        assertNull(topology.home(coder, main))
        assertEquals(listOf("coder@test:main", "helper@test:main"), stored("homes").map { it.substringBefore(' ') })
    }

    @Test
    fun `a home set at runtime shows when and by whom, and the configured home stays known`() {
        home(coder, "test:main:5")
        val grant = topology.homeGrant(coder, main)

        assertEquals(chat("test:main:1"), topology.configuredHome(coder, main))
        assertEquals(admin, grant?.by)
        assertEquals(listOf(true, false), listOf(grant != null, topology.homeGrant(helper, main) != null))
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
        val group = topology.group(coder, chat("test:solo:9"))!!
        assertEquals(admin, topology.linkGrant(coder, group, chat("test:main:9"))?.by)
        assertNull(topology.linkGrant(coder, group, chat("test:solo:9")))
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

        assertEquals(Revocation(false, true), unlink(coder, chat("test:main:8")))
        assertEquals(Revocation(false, true), unlink(coder, chat("test:main:7")))
        assertEquals(listOf(declared("test:main:7", "test:solo:7", "test:main:8")), topology.groups(coder))
        assertEquals(emptyList(), stored("links"))
    }

    @Test
    fun `an unlinked chat leaves its group, which dissolves with its last member`() {
        link(coder, chat("test:main:5"), chat("test:main:6"))
        link(coder, chat("test:main:5"), chat("test:solo:6"))

        assertEquals(Revocation(true, false), unlink(coder, chat("test:main:6")))
        assertEquals(group("test:main:5", "test:solo:6"), topology.group(coder, chat("test:main:5")))
        refused("test:main:5 anchors its group: unlink the other chats first.") {
            unlink(coder, chat("test:main:5"))
        }

        assertEquals(Revocation(true, false), unlink(coder, chat("test:solo:6")))

        assertNull(topology.group(coder, chat("test:main:5")))
        assertEquals(AgentChatKey.parse("coder@test:solo:6"), topology.key(coder, chat("test:solo:6")))
        assertEquals(Revocation(false, false), unlink(coder, chat("test:main:5")))
        assertEquals(Revocation(false, false), unlink(helper, chat("test:main:8")))
        assertEquals(emptyList(), stored("links"))
    }

    // Records.

    @Test
    fun `each change is a record of who made it, which the next start loads over the config`() {
        home(coder, "test:main:5")
        noHome(helper, "test:main:2")
        link(coder, chat("test:main:5"), chat("test:solo:5"))

        val restarted = started()

        assertEquals(chat("test:main:5"), restarted.home(coder, main))
        assertNull(restarted.home(helper, main))
        assertEquals(group("test:main:5", "test:solo:5"), restarted.group(coder, chat("test:solo:5")))
        assertEquals(declared("test:main:7", "test:solo:7", "test:main:8"), restarted.group(coder, chat("test:main:8")))
        assertEquals(listOf(admin, admin), listOf(coder, helper).map { restarted.homeGrant(it, main)?.by })
        val by = "{\"address\":\"${admin.address}\",\"name\":\"Ada\",\"username\":null,\"bot\":false,\"admin\":true}"
        assertEquals(
            listOf(
                "coder@test:main {\"agent\":\"coder\",\"instance\":\"test:main\",\"chat\":\"test:main:5\"," +
                    "\"by\":$by} ${admin.address}",
                "helper@test:main {\"agent\":\"helper\",\"instance\":\"test:main\",\"chat\":null,\"by\":$by} " +
                    "${admin.address}",
            ),
            stored("homes"),
        )
        assertEquals(
            listOf(
                "coder@test:solo:5 {\"agent\":\"coder\",\"member\":\"test:solo:5\",\"anchor\":\"test:main:5\"," +
                    "\"by\":$by} ${admin.address}",
            ),
            stored("links"),
        )
    }

    @Test
    fun `a home set back to the configured chat leaves no record`() {
        home(coder, "test:main:5")
        home(coder, "test:main:1")
        noHome(helper, "test:main:2")
        home(helper, "test:main:2")

        assertEquals(emptyList(), stored("homes"))
        assertEquals(chat("test:main:1"), started().home(coder, main))
    }

    @Test
    fun `until it starts a topology knows only the config`() {
        home(coder, "test:main:5")

        assertEquals(chat("test:main:1"), topology().home(coder, main))
    }

    @Test
    fun `homes swapped at runtime are as swapped after the next start`() {
        noHome(helper, "test:main:2")
        home(coder, "test:main:2")
        home(helper, "test:main:1")

        val restarted = started()
        val homes = listOf(coder, helper).map { restarted.home(it, main) }

        assertEquals(listOf("test:main:2", "test:main:1").map(::chat), homes)
        assertEquals(listOf(helper, coder), listOf("test:main:1", "test:main:2").map { restarted.homeAgent(chat(it)) })
    }

    @Test
    fun `a recorded change that the config no longer allows is ignored with a warning`() {
        home(coder, "test:solo:4")
        home(coder, "test:main:3")
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

        assertEquals(chat("test:main:3"), restarted.home(coder, main))
        assertNull(restarted.home(helper, main))
        assertEquals(listOf(declared("test:main:3", "test:main:9")), restarted.groups(coder))
        assertEquals(
            listOf(
                "WARN The home chat test:solo:4 of agent 'coder' is ignored: agent 'coder' does not serve test:solo",
                "WARN The configured home chat test:main:3 of agent 'helper' is the home chat of agent 'coder' now: " +
                    "'helper' has no home chat on test:main",
                "WARN The link of test:main:9 to test:main:5 at agent 'coder' is ignored: it is in another group",
                "WARN The link of test:solo:9 to test:main:5 at agent 'coder' is ignored: agent 'coder' does not " +
                    "serve both chats",
            ),
            warnings,
        )
    }

    @Test
    fun `unlinking a chat that the config links since drops its runtime link, which no later start brings back`() {
        link(coder, chat("test:main:5"), chat("test:main:6"))
        val declaring = config.replace("""[["test:main:7"""", """[["test:main:5", "test:main:6"], ["test:main:7"""")

        val linked = started(declaring)
        val revoked = unlink(coder, chat("test:main:6"), linked)

        assertEquals(Revocation(true, true), revoked)
        assertEquals(declared("test:main:5", "test:main:6"), linked.group(coder, chat("test:main:6")))
        assertNull(started().group(coder, chat("test:main:6")))
        assertEquals(emptyList(), stored("links"))
    }

    private companion object {
        const val PLUGIN = "alexandrite-agent"
    }
}
