package org.foedusprogramme.alexandrite.agent.control

import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.foedusprogramme.alexandrite.agent.agentHarness
import org.foedusprogramme.alexandrite.agent.execute
import org.foedusprogramme.alexandrite.agent.logged
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
import org.foedusprogramme.alexandrite.sdk.turn.AgentDefaults
import org.foedusprogramme.alexandrite.sdk.turn.AgentSource
import org.foedusprogramme.alexandrite.sdk.turn.AgentSummary
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
import org.junit.jupiter.api.Assumptions.assumeTrue
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

    private fun snapshot(
        agent: AgentId,
        source: AgentSource,
        block: ChatSettingsSnapshot.Builder.() -> Unit = {},
    ): ChatSettingsSnapshot = ChatSettingsSnapshot.builder(agent)
        .agentSource(source)
        .defaults(AgentDefaults(model.ref))
        .apply(block)
        .build()

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
    fun `a hand edit of another group since the start survives a link, and that group links at the next start`() {
        val hand = "[[\"test:main:3\",   \"test:side:4\"]]"
        val lines = logged {
            harness().execute {
                val control = get<AgentControl>()
                Files.writeString(file, Files.readString(file).replaceFirst(CODER, "$0\"linkedChats\": $hand, "))
                val handEdited = Files.readString(file)

                val refused = assertFailsWith<IllegalArgumentException> {
                    control.link(coder, anchor, ChatAddress.parse("test:side:4"), null)
                }.message
                val group = control.link(coder, anchor, member, null)

                assertEquals("test:side:4 is linked to test:main:3 already: unlink it first.", refused)
                assertEquals(listOf(group), control.links(coder))
                val grown = "[[\"test:main:3\",   \"test:side:4\"], [\"$anchor\", \"$member\"]]"
                assertEquals(handEdited.replace(hand, grown), Files.readString(file))
            }
        }

        assertEquals(emptyList(), lines.filter { it.startsWith("WARN") })
        harness().execute {
            val groups = listOf(
                ChatLinkGroup(coder, ChatAddress.parse("test:main:3"), listOf(ChatAddress.parse("test:side:4"))),
                ChatLinkGroup(coder, anchor, listOf(member)),
            )
            assertEquals(groups, get<AgentControl>().links(coder))
        }
    }

    @Test
    fun `a link onto a group the operator extended by hand is made to the file's group, with a warning`() {
        val extra = ChatAddress.parse("test:main:3")
        val late = ChatAddress.parse("test:side:5")
        harness().execute { get<AgentControl>().link(coder, anchor, member, null) }

        val lines = logged {
            harness().execute {
                val control = get<AgentControl>()
                val text = Files.readString(file)
                val separator = text.substringAfter("\"$anchor\"").substringBefore("\"$member\"")
                Files.writeString(file, text.replace("\"$member\"", "\"$member\",  \"$extra\""))
                val handEdited = Files.readString(file)

                val group = control.link(coder, anchor, late, null)
                val grown = Files.readString(file)
                val unlinked = control.unlink(coder, member, null)

                assertEquals(ChatLinkGroup(coder, anchor, listOf(member, late)), group)
                assertEquals(handEdited.replace("\"$extra\"", "\"$extra\"$separator\"$late\""), grown)
                assertTrue(unlinked)
                assertEquals(listOf(ChatLinkGroup(coder, anchor, listOf(late))), control.links(coder))
                assertEquals(groups(listOf(anchor, extra, late)), configured(coder, "linkedChats"))
            }
        }

        val warning = "WARN The linked chats of agent 'coder' in the config file were edited by hand since the " +
            "start: the change is made to the file's group of %s as the file holds it, and the running groups " +
            "follow the file at the next start"
        assertEquals(
            listOf(warning.format(anchor), warning.format(member)),
            lines.filter { it.startsWith("WARN") },
        )
        harness().execute {
            assertEquals(listOf(ChatLinkGroup(coder, anchor, listOf(extra, late))), get<AgentControl>().links(coder))
        }
    }

    @Test
    fun `a chat whose id holds a variable's syntax is written so that the config file reads it back as it is`() {
        val odd = ChatAddress.parse("test:side:\${x}")
        val home = ChatAddress.parse("test:main:\${env:HOME}")

        harness().execute {
            val control = get<AgentControl>()

            control.link(coder, anchor, odd, null)
            control.updateSettings(home, ChatSettingsUpdate.builder().agent(helper).home().build(), null)

            assertEquals(groups(listOf(anchor, odd)), configured(coder, "linkedChats"))
            assertEquals(JsonPrimitive("\${env:HOME}"), configured(helper, "channels/test:main/home"))
            assertTrue("\"test:side:\$\${x}\"" in Files.readString(file))
        }
        harness().execute {
            assertEquals(listOf(ChatLinkGroup(coder, anchor, listOf(odd))), get<AgentControl>().links(coder))
            assertEquals(home, get<AgentControl>().agents(home.instance).single { it.id == helper }.home)
        }
    }

    @Test
    fun `a link or unlink of a chat the config file names through a variable is refused and changes nothing`() {
        val home = System.getenv("HOME")
        assumeTrue(home != null && ChatAddress.parseOrNull("test:main:$home") != null)
        val named = ChatAddress.parse("test:main:$home")
        val namedMember = ChatAddress.parse("test:side:$home")
        harness().execute {}
        val linked = "[[\"test:main:\${env:HOME}\", \"$member\"], [\"$anchor\", \"test:side:\${env:HOME}\"]]"
        val inserted = "$0" + Regex.escapeReplacement("\"linkedChats\": $linked, ")
        Files.writeString(file, Files.readString(file).replaceFirst(CODER, inserted))
        val written = Files.readString(file)

        val lines = logged {
            harness().execute {
                val control = get<AgentControl>()
                val before = control.links(coder)

                val refused = listOf(
                    assertFailsWith<IllegalArgumentException> {
                        control.link(coder, named, ChatAddress.parse("test:side:3"), null)
                    },
                    assertFailsWith<IllegalArgumentException> { control.unlink(coder, namedMember, null) },
                ).map { it.message }

                val reason = "is in a group that the config file names through an environment variable: edit the " +
                    "config file to change the group."
                assertEquals(listOf("$named $reason", "$namedMember $reason"), refused)
                val groups = listOf(
                    ChatLinkGroup(coder, named, listOf(member)),
                    ChatLinkGroup(coder, anchor, listOf(namedMember)),
                )
                assertEquals(groups, before)
                assertEquals(before, control.links(coder))
                assertEquals(written, Files.readString(file))
                assertTrue(control.unlink(coder, member, null))
            }
        }

        assertEquals(emptyList(), lines.filter { it.startsWith("WARN") && "by hand" in it })
        harness().execute {
            assertEquals(listOf(ChatLinkGroup(coder, anchor, listOf(namedMember))), get<AgentControl>().links(coder))
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
            val listed = control.agents(chat.instance)
            val reset = control.updateSettings(chat, update { resetAgent() }, null)
            val thread = control.settings(ChatAddress.parse("test:main:5#9"))
            val left = control.updateSettings(chat, update { noHome() }, null)

            assertEquals(snapshot(helper, AgentSource.SELECTED) { home(true) }, home)
            assertEquals(JsonPrimitive("5"), written)
            assertEquals(
                listOf(
                    AgentSummary(coder, "coder", null, true, null),
                    AgentSummary(helper, "helper", null, false, chat),
                ),
                listed,
            )
            assertEquals(snapshot(helper, AgentSource.HOME) { home(true) }, reset)
            assertEquals(snapshot(helper, AgentSource.HOME), thread)
            assertEquals(snapshot(coder, AgentSource.DEFAULT), left)
            assertEquals(listOf(null, null), control.agents(chat.instance).map { it.home })
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
            assertEquals(snapshot(coder, AgentSource.DEFAULT), control.settings(chat))
            assertEquals(emptyList(), control.links())
            assertEquals(AgentChatKey(coder, member), ask(member).turn.key)
        }
    }

    @Test
    fun `a change that waits for a worker is refused in a turn before it writes, and a link waits for nothing`() {
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
            assertNull(configured(helper, "channels/test:main/home"))
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

    private companion object {
        /** The attachment of `helper` to `test:main` as the harness writes it. */
        val EMPTY_MAIN = Regex("\"test:main\":\\s*\\{\\s*}")

        val CODER = Regex("\"coder\":\\s*\\{")
    }
}
