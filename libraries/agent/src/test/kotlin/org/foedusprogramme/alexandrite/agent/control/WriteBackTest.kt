package org.foedusprogramme.alexandrite.agent.control

import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.foedusprogramme.alexandrite.agent.agentHarness
import org.foedusprogramme.alexandrite.agent.execute
import org.foedusprogramme.alexandrite.agent.turn.Observer
import org.foedusprogramme.alexandrite.sdk.chat.AgentChatKey
import org.foedusprogramme.alexandrite.sdk.chat.AgentId
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.chat.TurnKind
import org.foedusprogramme.alexandrite.sdk.hook.HookDecision
import org.foedusprogramme.alexandrite.sdk.hook.InterceptorHook
import org.foedusprogramme.alexandrite.sdk.model.ReasoningEffort
import org.foedusprogramme.alexandrite.sdk.turn.Admission
import org.foedusprogramme.alexandrite.sdk.turn.AgentControl
import org.foedusprogramme.alexandrite.sdk.turn.ChatLinkGroup
import org.foedusprogramme.alexandrite.sdk.turn.ChatSettingsSnapshot
import org.foedusprogramme.alexandrite.sdk.turn.ChatSettingsUpdate
import org.foedusprogramme.alexandrite.sdk.turn.TurnPoints
import org.foedusprogramme.alexandrite.sdk.turn.TurnStart
import org.foedusprogramme.alexandrite.testkit.MemoryStore
import org.foedusprogramme.alexandrite.testkit.PluginHarness
import org.foedusprogramme.alexandrite.testkit.RecordedReply
import org.foedusprogramme.alexandrite.testkit.RecordingChannel
import org.foedusprogramme.alexandrite.testkit.ScriptedModel
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WriteBackTest {
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

    /** `coder` is the default of `test:main` and `test:side`, and `helper` serves `test:main` too. */
    private fun harness(configure: PluginHarness.Builder.() -> Unit = {}): PluginHarness = agentHarness(
        """
        {
          "previewIntervalMillis": 0,
          "shutdown": {"turnGraceSeconds": 0, "cancelJoinSeconds": 2},
          "agents": {
            "coder": {"model": "scripted/test-model", "channels": {"test:main": {"default": true}, "test:side": {}}},
            "helper": {"model": "scripted/test-model", "channels": {"test:main": {}}}
          }
        }
        """.trimIndent(),
        store,
    ) { channel().channel("side").model(model).hook(sealed).writeConfigFile(file).apply(configure) }

    private fun PluginHarness.Running.configured(agent: AgentId, path: String): JsonElement? {
        val parts = path.split('/')
        return readConfig().tree(
            listOf("agent", "agents", "$agent", *parts.dropLast(1).toTypedArray()).joinToString("."),
        )
            ?.get(parts.last())
    }

    private fun PluginHarness.Running.channelOf(chat: ChatAddress): RecordingChannel = channel(chat.instance)

    /** The reply of a message in [chat], once its turn ended. */
    private suspend fun PluginHarness.Running.ask(chat: ChatAddress, text: String = "Hi"): RecordedReply {
        val channel = channelOf(chat)
        val admission = channel.receive(text, chat) as Admission.Accepted
        admission.ticket.outcome()
        return channel.reply(admission.ticket.turn).awaitEnd()
    }

    private fun groups(vararg groups: List<ChatAddress>): JsonArray =
        JsonArray(groups.map { group -> JsonArray(group.map { JsonPrimitive(it.toString()) }) })

    @Test
    fun `a linked chat continues the anchor's conversation, each reply in its own chat, until it is unlinked`() {
        repeat(4) { model.reply { text("Answer $it.") } }

        harness().execute {
            val control = get<AgentControl>()
            val before = ask(member).turn.conversation

            val group = control.link(coder, anchor, member, channel().user("admin"))
            val fromAnchor = ask(anchor)
            val fromMember = ask(member)
            while (sealed.seen.isEmpty()) delay(5)

            assertEquals(ChatLinkGroup(coder, anchor, listOf(member)), group)
            assertEquals(listOf(group), control.links())
            assertEquals(groups(listOf(anchor, member)), configured(coder, "linkedChats"))
            assertEquals(fromAnchor.turn.conversation, fromMember.turn.conversation)
            assertEquals(listOf(anchor, member), listOf(fromAnchor, fromMember).map { it.turn.chat })
            assertTrue(listOf(fromAnchor, fromMember).all { it.completed != null })
            val seal = sealed.seen.single()
            assertEquals(before, seal.sealed)
            assertEquals(
                Triple(member, TurnKind.COMMAND, coder),
                Triple(seal.turn.chat, seal.turn.kind, seal.turn.agent),
            )

            assertTrue(control.unlink(coder, member, null))
            assertFalse(control.unlink(coder, member, null))
            val alone = ask(member)

            assertEquals(emptyList(), control.links(coder))
            assertNull(configured(coder, "linkedChats"))
            assertEquals(seal.successor, alone.turn.conversation)
            assertEquals(1, model.requests.last().history.size)
        }
    }

    @Test
    fun `a runtime link survives a restart as the config file holds it`() {
        model.reply { text("Linked.") }

        harness().execute { get<AgentControl>().link(coder, anchor, member, null) }
        harness().execute {
            val group = ChatLinkGroup(coder, anchor, listOf(member))

            assertEquals(listOf(group), get<AgentControl>().links(coder))
            assertEquals(AgentChatKey(coder, anchor), ask(member).turn.key)
        }
    }

    @Test
    fun `a home chat is written into the config file and routes at once`() {
        harness().execute {
            val control = get<AgentControl>()
            val chat = ChatAddress.parse("test:main:5")
            fun update(block: ChatSettingsUpdate.Builder.() -> Unit) = ChatSettingsUpdate.builder().apply(block).build()

            val home = control.updateSettings(chat, update { agent(helper).home() }, null)
            val written = configured(helper, "channels/test:main/home")
            val reset = control.updateSettings(chat, update { resetAgent() }, null)
            val thread = control.settings(ChatAddress.parse("test:main:5#9"))
            val left = control.updateSettings(chat, update { noHome() }, null)

            assertEquals(ChatSettingsSnapshot.builder(helper).home(true).build(), home)
            assertEquals(JsonPrimitive("5"), written)
            assertEquals(ChatSettingsSnapshot.builder(helper).home(true).build(), reset)
            assertEquals(ChatSettingsSnapshot.builder(helper).build(), thread)
            assertEquals(ChatSettingsSnapshot.builder(coder).build(), left)
            assertNull(configured(helper, "channels/test:main/home"))
        }
    }

    @Test
    fun `a change the config file refuses changes nothing`() {
        model.reply { text("Alone.") }

        harness().execute {
            val control = get<AgentControl>()
            val chat = ChatAddress.parse("test:main:5")
            val home = ChatSettingsUpdate.builder().agent(helper).home().build()
            val bytes = Files.readAllBytes(file)
            val permissions = Files.getPosixFilePermissions(file)
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("r--r--r--"))

            val readOnly = try {
                listOf(
                    assertFailsWith<IllegalArgumentException> { control.updateSettings(chat, home, null) },
                    assertFailsWith<IllegalArgumentException> { control.link(coder, anchor, member, null) },
                ).map { it.message }
            } finally {
                Files.setPosixFilePermissions(file, permissions)
            }
            val handEdited = String(bytes).replaceFirst(EMPTY_MAIN, "\"test:main\": {\"home\": \"5\"}")
            Files.writeString(file, handEdited)
            val invalid = assertFailsWith<IllegalArgumentException> {
                control.updateSettings(chat, ChatSettingsUpdate.builder().home().build(), null)
            }.message

            assertEquals(
                List(2) { "The config file did not take the change (read_only), so nothing changed." },
                readOnly,
            )
            assertEquals("The config file did not take the change (invalid_result), so nothing changed.", invalid)
            assertEquals(handEdited, Files.readString(file))
            assertEquals(ChatSettingsSnapshot.builder(coder).build(), control.settings(chat))
            assertEquals(emptyList(), control.links())
            assertEquals(AgentChatKey(coder, member), ask(member).turn.key)
        }
    }

    @Test
    fun `a turn's own call for a change that would wait for the turn is refused, and a link waits for nothing`() {
        model.reply { text("Done.") }
        val calls = SelfControl()

        harness { hook(calls) }.execute {
            calls.control = get()
            val reply = ask(member)
            while (sealed.seen.isEmpty()) delay(5)

            val own =
                "A turn at coder@test:side:2 cannot wait for a change of its own key, which runs after the turn: " +
                    "make it outside the turn."
            assertEquals(listOf(own, own, "linked"), calls.outcomes)
            assertTrue(reply.completed != null)
            assertEquals(reply.turn.conversation, sealed.seen.single().sealed)
            assertNull(get<AgentControl>().settings(member).reasoning)
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
            outcomes += attempt { control.newConversation(chat, null) }
            outcomes += attempt { control.updateSettings(chat, effort, null) }
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

    private companion object {
        /** The attachment of `helper` to `test:main` as the harness writes it. */
        val EMPTY_MAIN = Regex("\"test:main\":\\s*\\{\\s*}")
    }
}
