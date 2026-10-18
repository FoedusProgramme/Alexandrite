package org.unitedsoftware.alexandrite.agent.permission

import org.unitedsoftware.alexandrite.agent.agentHarness
import org.unitedsoftware.alexandrite.agent.execute
import org.unitedsoftware.alexandrite.agent.prompt.INITIATED_SOURCE
import org.unitedsoftware.alexandrite.sdk.chat.AgentChatKey
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.chat.TurnKind
import org.unitedsoftware.alexandrite.sdk.model.Trust
import org.unitedsoftware.alexandrite.sdk.plugin.PluginOrigins
import org.unitedsoftware.alexandrite.sdk.tool.Tool
import org.unitedsoftware.alexandrite.sdk.tool.ToolResult
import org.unitedsoftware.alexandrite.sdk.transcript.ContextPart
import org.unitedsoftware.alexandrite.sdk.transcript.UserEntry
import org.unitedsoftware.alexandrite.sdk.turn.Admission
import org.unitedsoftware.alexandrite.sdk.turn.InitiatedTurn
import org.unitedsoftware.alexandrite.sdk.turn.Mandate
import org.unitedsoftware.alexandrite.sdk.turn.TurnInitiation
import org.unitedsoftware.alexandrite.sdk.turn.TurnOutcome
import org.unitedsoftware.alexandrite.testkit.MemoryStore
import org.unitedsoftware.alexandrite.testkit.PluginHarness
import org.unitedsoftware.alexandrite.testkit.ScriptedModel
import org.unitedsoftware.alexandrite.testkit.recordingTool
import org.unitedsoftware.alexandrite.testkit.testTurn
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** A task an operator set up runs as them, under a mandate that only their own untainted turn grants. */
class MandatesTest {
    private val store = MemoryStore()
    private val model = ScriptedModel()
    private val key = AgentChatKey.parse("coder@test:main:chat")

    /** The mandates each call of `tasks.make` got. */
    private val granted = CopyOnWriteArrayList<Mandate?>()
    private val make = recordingTool("tasks.make") { _, context ->
        granted += context.mandate()
        ToolResult("Made.")
    }

    /** The actor of each turn that called `tasks.who`. */
    private val actors = CopyOnWriteArrayList<ChatUser?>()
    private val who = recordingTool("tasks.who") { _, context ->
        actors += context.turn.actor
        ToolResult("Seen.")
    }

    private val fetch = recordingTool("web.fetch") { _, context ->
        context.untrusted("a web page")
        ToolResult("A page.")
    }

    private fun harness(admins: Set<String> = setOf("admin")): PluginHarness = agentHarness(
        """
        {
          "previewIntervalMillis": 0,
          "agents": {
            "coder": {
              "model": "scripted/test-model",
              "channels": {"test:main": {}},
              "tools": {"forMembers": ["tasks.*"]}
            }
          }
        }
        """.trimIndent(),
        store,
    ) { channel(admins = admins).model(model).tool(make).tool(who).tool(fetch) }

    private suspend fun PluginHarness.Running.ask(user: String = "admin") {
        val admission = channel().receive("Set it up.", sender = channel().user(user))
        assertIs<TurnOutcome.Completed>(assertIs<Admission.Accepted>(admission).ticket.outcome())
    }

    /** Runs a reminder of [plugin] under [mandate] that calls `tasks.who`, and returns its opening's framing. */
    private suspend fun PluginHarness.Running.remind(mandate: Mandate?, plugin: String = plugin(make)): String {
        model.reply { toolCall("tasks.who", "{}") }.reply { text("Done.") }
        val turn = InitiatedTurn.builder(key, TurnKind.REMINDER, "Check the build.")
            .trust(Trust.TRUSTED)
            .mandate(mandate)
            .build()
        val admission = get<TurnInitiation>().initiate(plugin, turn)
        assertIs<TurnOutcome.Completed>(assertIs<Admission.Accepted>(admission).ticket.outcome())
        return model.requests.last().history.filterIsInstance<UserEntry>().last { it.parts.isNotEmpty() }.parts
            .filterIsInstance<ContextPart>().last { it.source == INITIATED_SOURCE }.text
    }

    private fun PluginHarness.Running.plugin(tool: Tool): String = checkNotNull(get<PluginOrigins>().pluginOf(tool))

    @Test
    fun `an operator's untainted turn grants a mandate, under which the plugin's reminder runs as them`() {
        model.reply { toolCall("tasks.make", "{}") }.reply { text("Set.") }

        harness().execute {
            ask()
            val mandate = assertNotNull(granted.single())
            val framing = remind(mandate)

            val actor = assertNotNull(actors.single())
            assertEquals("admin" to true, actor.address.user to actor.isAdmin)
            assertContains(framing, "On behalf of: ${actor.displayName} (operator)")
            assertEquals(Principal.OPERATOR, Principal.of(testTurn(TurnKind.REMINDER) { actor(actor) }))
        }
    }

    @Test
    fun `a member's turn and a tainted one get no mandate`() {
        model.reply { toolCall("tasks.make", "{}") }.reply { text("Set.") }
        model.reply { toolCall("web.fetch", "{}") }.reply { toolCall("tasks.make", "{}") }.reply { text("Set.") }

        harness().execute {
            ask(user = "member")
            ask()

            assertEquals(listOf<Mandate?>(null, null), granted)
        }
    }

    @Test
    fun `another plugin's mandate, a revoked one and one of a conversation tainted since run as nobody`() {
        model.reply { toolCall("tasks.make", "{}") }.reply { text("Set.") }
        model.reply { toolCall("tasks.make", "{}") }.reply { text("Set.") }

        harness().execute {
            ask()
            ask()
            val (first, second) = granted.map { assertNotNull(it) }
            val elsewhere = remind(first, plugin = "another")
            get<TurnInitiation>().revoke(plugin(make), first)
            remind(first)
            model.reply { toolCall("web.fetch", "{}") }.reply { text("Read.") }
            ask()
            remind(second)

            assertEquals(listOf<ChatUser?>(null, null, null), actors)
            assertEquals(false, "On behalf of" in elsewhere)
        }
    }

    @Test
    fun `a mandate of an operator who is no longer an admin runs as nobody, and the chat is told once`() {
        model.reply { toolCall("tasks.make", "{}") }.reply { text("Set.") }
        harness().execute { ask() }
        val mandate = assertNotNull(granted.single())

        harness(admins = emptySet()).execute {
            val framing = remind(mandate)
            remind(mandate)

            assertEquals(listOf<ChatUser?>(null, null), actors)
            assertEquals(false, "On behalf of" in framing)
            val told = channel().sent.map { it.message.text }.filter { "no longer an admin" in it }
            val text = "Admin is no longer an admin, so what plugin ${plugin(make)} runs for them here runs as no one."
            assertEquals(listOf(text), told)
        }
    }

    @Test
    fun `a reminder without a mandate runs as nobody`() {
        harness().execute {
            remind(null)

            assertNull(actors.single())
        }
    }
}
