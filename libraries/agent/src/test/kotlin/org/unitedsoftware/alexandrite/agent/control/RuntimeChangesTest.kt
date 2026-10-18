package org.unitedsoftware.alexandrite.agent.control

import kotlinx.coroutines.delay
import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.agent.agentHarness
import org.unitedsoftware.alexandrite.agent.blocking
import org.unitedsoftware.alexandrite.agent.execute
import org.unitedsoftware.alexandrite.agent.turn.Observer
import org.unitedsoftware.alexandrite.sdk.chat.AgentChatKey
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.TurnKind
import org.unitedsoftware.alexandrite.sdk.hook.HookDecision
import org.unitedsoftware.alexandrite.sdk.hook.InterceptorHook
import org.unitedsoftware.alexandrite.sdk.model.ReasoningEffort
import org.unitedsoftware.alexandrite.sdk.turn.Admission
import org.unitedsoftware.alexandrite.sdk.turn.AgentControl
import org.unitedsoftware.alexandrite.sdk.turn.AgentSource
import org.unitedsoftware.alexandrite.sdk.turn.AgentSummary
import org.unitedsoftware.alexandrite.sdk.turn.ChatLink
import org.unitedsoftware.alexandrite.sdk.turn.ChatLinkGroup
import org.unitedsoftware.alexandrite.sdk.turn.ChatSettingsSnapshot
import org.unitedsoftware.alexandrite.sdk.turn.ChatSettingsUpdate
import org.unitedsoftware.alexandrite.sdk.turn.Revocation
import org.unitedsoftware.alexandrite.sdk.turn.TurnPoints
import org.unitedsoftware.alexandrite.sdk.turn.TurnStart
import org.unitedsoftware.alexandrite.testkit.MemoryStore
import org.unitedsoftware.alexandrite.testkit.PluginHarness
import org.unitedsoftware.alexandrite.testkit.RecordedReply
import org.unitedsoftware.alexandrite.testkit.ScriptedModel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RuntimeChangesTest {
    @TempDir
    lateinit var directory: Path

    private val store = MemoryStore()
    private val model = ScriptedModel()
    private val sealed = Observer(TurnPoints.CONVERSATION_SEALED)
    private val coder = AgentId("coder")
    private val helper = AgentId("helper")
    private val anchor = ChatAddress.parse("test:main:1")
    private val member = ChatAddress.parse("test:side:2")

    private val file: Path get() = directory.resolve("alexandrite.json")

    /**
     * `coder` is the default of `test:main` and `test:side` with [coderConfig] beside, and `helper` serves `test:main`
     * too, with [helperConfig] as its attachment there.
     */
    private fun harness(
        coderConfig: String = "",
        helperConfig: String = "{}",
        configure: PluginHarness.Builder.() -> Unit = {},
    ): PluginHarness = agentHarness(
        """
        {
          "previewIntervalMillis": 0,
          "shutdown": {"turnGraceSeconds": 0, "cancelJoinSeconds": 2},
          "agents": {
            "coder": {
              "model": "scripted/test-model",
              "channels": {"test:main": {"default": true}, "test:side": {}}$coderConfig
            },
            "helper": {"model": "scripted/test-model", "channels": {"test:main": $helperConfig}}
          }
        }
        """.trimIndent(),
        store,
    ) { channel().channel("side").model(model).hook(sealed).writeConfigFile(file).apply(configure) }

    /** The agent's records of [collection] as `<key> by <who>`. */
    private fun stored(collection: String): List<String> =
        blocking { store.records.list("alexandrite-agent", collection) }.map { "${it.key} by ${it.createdBy}" }

    private fun snapshot(
        agent: AgentId,
        source: AgentSource,
        block: ChatSettingsSnapshot.Builder.() -> Unit = {},
    ): ChatSettingsSnapshot = ChatSettingsSnapshot.builder(agent)
        .agentSource(source)
        .defaultModel(model.ref)
        .apply(block)
        .build()

    private fun update(block: ChatSettingsUpdate.Builder.() -> Unit) = ChatSettingsUpdate.builder().apply(block).build()

    /** The reply of a message in [chat], once its turn ended. */
    private suspend fun PluginHarness.Running.ask(chat: ChatAddress, text: String = "Hi"): RecordedReply {
        val channel = channel(chat.instance)
        val admission = channel.receive(text, chat) as Admission.Accepted
        admission.ticket.outcome()
        return channel.reply(admission.ticket.turn).awaitEnd()
    }

    @Test
    fun `a linked chat continues the anchor's conversation, each reply in its own chat, until it is unlinked`() {
        repeat(4) { model.reply { text("Answer $it.") } }

        harness().execute {
            val control = get<AgentControl>()
            val config = Files.readString(file)
            val before = ask(member).turn.conversation
            val operator = channel().user("operator")

            val group = control.link(coder, anchor, member, operator)
            val linked = stored("links")
            val fromAnchor = ask(anchor)
            val fromMember = ask(member)
            while (sealed.seen.isEmpty()) delay(5)

            val link = ChatLink(group.links.getValue(member).linkedAt, operator)
            assertEquals(ChatLinkGroup(coder, anchor, listOf(member), links = mapOf(member to link)), group)
            assertEquals(listOf(group), control.links())
            assertEquals(listOf("coder@$member by ${operator.address}"), linked)
            assertEquals(fromAnchor.turn.conversation, fromMember.turn.conversation)
            assertEquals(listOf(anchor, member), listOf(fromAnchor, fromMember).map { it.turn.chat })
            assertTrue(listOf(fromAnchor, fromMember).all { it.completed != null })
            val seal = sealed.seen.single()
            assertEquals(before, seal.sealed)
            assertEquals(
                Triple(member, TurnKind.COMMAND, coder),
                Triple(seal.turn.chat, seal.turn.kind, seal.turn.agent),
            )

            assertEquals(Revocation(removed = true, declared = false), control.unlink(coder, member, null))
            assertEquals(Revocation(removed = false, declared = false), control.unlink(coder, member, null))
            val alone = ask(member)

            assertEquals(emptyList(), control.links(coder))
            assertEquals(emptyList(), stored("links"))
            assertEquals(seal.successor, alone.turn.conversation)
            assertEquals(1, model.requests.last().history.size)
            assertEquals(config, Files.readString(file))
        }
    }

    @Test
    fun `a runtime link and home survive a restart as records, and the config file is untouched`() {
        model.reply { text("Linked.") }
        val home = ChatAddress.parse("test:main:5")
        lateinit var config: String
        lateinit var linked: ChatLinkGroup
        lateinit var homed: AgentSummary

        harness().execute {
            config = Files.readString(file)
            val control = get<AgentControl>()
            linked = control.link(coder, anchor, member, null)
            control.updateSettings(home, update { agent(helper).home() }, channel().user("operator"))
            homed = control.agents(home.instance).single { it.id == helper }
        }
        harness().execute {
            val control = get<AgentControl>()

            assertEquals(listOf(linked), control.links(coder))
            assertEquals(AgentChatKey(coder, anchor), ask(member).turn.key)
            assertEquals(homed, control.agents(home.instance).single { it.id == helper })
            assertEquals(listOf(home, null), listOf(homed.home, homed.configuredHome))
            assertEquals("Operator", homed.homeSetBy?.displayName)
            assertEquals(snapshot(helper, AgentSource.SELECTED) { home(true) }, control.settings(home))
        }

        assertEquals(config, Files.readString(file))
        assertEquals(listOf("helper@test:main by test:main@operator"), stored("homes"))
    }

    @Test
    fun `the groups and homes in force are the config's and the store's, and a declared group changes only there`() {
        model.reply { text("Not home.") }
        val declaredMember = ChatAddress.parse("test:side:4")
        val declared = ChatLinkGroup(coder, ChatAddress.parse("test:main:3"), listOf(declaredMember), declared = true)
        val configured = ChatAddress.parse("test:main:6")
        val moved = ChatAddress.parse("test:main:5")
        val reason = "is in a group that the config file declares: edit the config file to change the group."
        lateinit var linked: ChatLinkGroup

        val coderConfig = ", \"linkedChats\": [[\"test:main:3\", \"test:side:4\"]]"
        harness(coderConfig, helperConfig = "{\"home\": \"6\"}").execute {
            val control = get<AgentControl>()
            val config = Files.readString(file)

            linked = control.link(coder, anchor, member, null)
            control.updateSettings(moved, update { agent(helper).home() }, null)
            val refused = listOf(
                assertFailsWith<IllegalArgumentException> {
                    control.link(coder, declared.anchor, ChatAddress.parse("test:side:9"), null)
                },
                assertFailsWith<IllegalArgumentException> { control.link(coder, anchor, declaredMember, null) },
            ).map { it.message }

            assertEquals(Revocation(removed = false, declared = true), control.unlink(coder, declaredMember, null))
            assertEquals(listOf(declared, linked), control.links(coder))
            assertEquals(listOf("${declared.anchor} $reason", "$declaredMember $reason"), refused)
            assertEquals(listOf(null, moved), control.agents(moved.instance).map { it.home })
            assertEquals(listOf(null, configured), control.agents(moved.instance).map { it.configuredHome })
            assertEquals(AgentChatKey(coder, configured), ask(configured).turn.key)
            assertEquals(config, Files.readString(file))
        }
        harness(coderConfig, helperConfig = "{\"home\": \"6\"}").execute {
            val control = get<AgentControl>()

            assertEquals(listOf(declared, linked), control.links(coder))
            assertEquals(listOf(null, moved), control.agents(moved.instance).map { it.home })
        }
    }

    @Test
    fun `unlinking a chat that the config links too drops its runtime link, which no later start brings back`() {
        val declaredGroup = ", \"linkedChats\": [[\"test:main:1\", \"test:side:2\"]]"

        harness().execute { get<AgentControl>().link(coder, anchor, member, null) }
        Files.delete(file)
        harness(declaredGroup).execute {
            val control = get<AgentControl>()
            val group = control.links(coder).single()

            assertEquals(listOf(true, true), listOf(group.declared, member in group.links))
            assertEquals(Revocation(removed = true, declared = true), control.unlink(coder, member, null))
            assertEquals(listOf(ChatLinkGroup(coder, anchor, listOf(member), declared = true)), control.links(coder))
        }
        Files.delete(file)
        harness().execute {
            assertEquals(emptyList(), get<AgentControl>().links(coder))
            assertEquals(emptyList(), stored("links"))
        }
    }

    @Test
    fun `a home chat is recorded and routes at once, and set back to none it leaves no record`() {
        harness().execute {
            val control = get<AgentControl>()
            val chat = ChatAddress.parse("test:main:5")

            val home = control.updateSettings(chat, update { agent(helper).home() }, null)
            val recorded = stored("homes")
            val listed = control.agents(chat.instance)
            val reset = control.updateSettings(chat, update { resetAgent() }, null)
            val thread = control.settings(ChatAddress.parse("test:main:5#9"))
            val left = control.updateSettings(chat, update { noHome() }, null)

            assertEquals(snapshot(helper, AgentSource.SELECTED) { home(true) }, home)
            assertEquals(listOf("helper@test:main by null"), recorded)
            val setAt = checkNotNull(listed[1].homeSetAt)
            assertEquals(
                listOf(
                    AgentSummary(coder, "coder", null, true, null),
                    AgentSummary(helper, "helper", null, false, chat, null, setAt, null),
                ),
                listed,
            )
            assertEquals(snapshot(helper, AgentSource.HOME) { home(true) }, reset)
            assertEquals(snapshot(helper, AgentSource.HOME) { home(true) }, thread)
            assertEquals(snapshot(coder, AgentSource.DEFAULT), left)
            assertEquals(listOf(null, null), control.agents(chat.instance).map { it.home })
            assertEquals(emptyList(), stored("homes"))
        }
    }

    @Test
    fun `a change needs no config file it could write`() {
        model.reply { text("Alone.") }
        val chat = ChatAddress.parse("test:main:5")

        harness().execute {
            val control = get<AgentControl>()
            val permissions = Files.getPosixFilePermissions(file)
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("r--r--r--"))

            try {
                control.updateSettings(chat, update { agent(helper).home() }, null)
                control.link(coder, anchor, member, null)
            } finally {
                Files.setPosixFilePermissions(file, permissions)
            }

            assertEquals(snapshot(helper, AgentSource.SELECTED) { home(true) }, control.settings(chat))
            assertEquals(listOf(anchor to listOf(member)), control.links().map { it.anchor to it.members })
            assertEquals(AgentChatKey(coder, anchor), ask(member).turn.key)
        }
    }

    @Test
    fun `a change that waits for a worker is refused in a turn before it is made, and a link waits for nothing`() {
        model.reply { text("Done.") }
        val calls = SelfControl()

        harness { hook(calls) }.execute {
            calls.control = get()
            val reply = ask(member)
            while (sealed.seen.isEmpty()) delay(5)

            val refused = "A turn cannot wait for a change of a chat, which runs after the turns queued at the chat: " +
                "make it outside the turn."
            assertEquals(List(5) { refused } + "linked", calls.outcomes)
            assertTrue(reply.completed != null)
            assertEquals(reply.turn.conversation, sealed.seen.single().sealed)
            val control = get<AgentControl>()
            assertEquals(listOf(null, null), listOf(member, anchor).map { control.settings(it).reasoning })
            assertEquals(AgentSource.DEFAULT, control.settings(anchor).agentSource)
            assertEquals(listOf(null, null), control.agents(anchor.instance).map { it.home })
            assertEquals(emptyList(), stored("homes"))
        }
    }

    /** Calls the agent's control from the first turn it sees, as a plugin's hook may. */
    private inner class SelfControl : InterceptorHook<TurnStart> {
        override val point = TurnPoints.TURN_START

        lateinit var control: AgentControl
        val outcomes: MutableList<String> = CopyOnWriteArrayList()

        override suspend fun intercept(payload: TurnStart): HookDecision<TurnStart> {
            if (outcomes.isNotEmpty()) return HookDecision.Continue
            val chat = payload.turn.chat
            val effort = ChatSettingsUpdate.builder().reasoning(ReasoningEffort.LOW).build()
            val elsewhere = ChatSettingsUpdate.builder().agent(helper).home().reasoning(ReasoningEffort.LOW).build()
            outcomes += attempt { control.newConversation(chat, null) }
            outcomes += attempt { control.updateSettings(chat, effort, null) }
            outcomes += attempt { control.newConversation(anchor, null) }
            outcomes += attempt { control.updateSettings(anchor, effort, null) }
            outcomes += attempt { control.updateSettings(anchor, elsewhere, null) }
            outcomes += attempt { control.link(coder, anchor, chat, null) }
            return HookDecision.Continue
        }

        private suspend fun attempt(call: suspend () -> Any): String = try {
            call()
            "linked"
        } catch (e: IllegalStateException) {
            e.message.orEmpty()
        }
    }
}
