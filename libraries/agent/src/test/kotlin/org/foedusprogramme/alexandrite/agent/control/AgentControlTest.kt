package org.foedusprogramme.alexandrite.agent.control

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import org.foedusprogramme.alexandrite.agent.agentHarness
import org.foedusprogramme.alexandrite.agent.execute
import org.foedusprogramme.alexandrite.agent.turn.Observer
import org.foedusprogramme.alexandrite.agent.worker.ticket
import org.foedusprogramme.alexandrite.runtime.TextCatalog
import org.foedusprogramme.alexandrite.sdk.channel.MessageKind
import org.foedusprogramme.alexandrite.sdk.chat.AgentId
import org.foedusprogramme.alexandrite.sdk.chat.ChannelInstanceId
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.chat.ChatSettings
import org.foedusprogramme.alexandrite.sdk.chat.LanguageTag
import org.foedusprogramme.alexandrite.sdk.chat.TurnId
import org.foedusprogramme.alexandrite.sdk.chat.TurnKind
import org.foedusprogramme.alexandrite.sdk.model.ReasoningEffort
import org.foedusprogramme.alexandrite.sdk.transcript.ModelRef
import org.foedusprogramme.alexandrite.sdk.turn.Admission
import org.foedusprogramme.alexandrite.sdk.turn.AgentControl
import org.foedusprogramme.alexandrite.sdk.turn.AgentDefaults
import org.foedusprogramme.alexandrite.sdk.turn.AgentSource
import org.foedusprogramme.alexandrite.sdk.turn.AgentSummary
import org.foedusprogramme.alexandrite.sdk.turn.ChatSettingsSnapshot
import org.foedusprogramme.alexandrite.sdk.turn.ChatSettingsUpdate
import org.foedusprogramme.alexandrite.sdk.turn.TurnOutcome
import org.foedusprogramme.alexandrite.sdk.turn.TurnPoints
import org.foedusprogramme.alexandrite.testkit.MemoryStore
import org.foedusprogramme.alexandrite.testkit.PluginHarness
import org.foedusprogramme.alexandrite.testkit.RecordedReply
import org.foedusprogramme.alexandrite.testkit.RecordingChannel
import org.foedusprogramme.alexandrite.testkit.ScriptedModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentControlTest {
    private val store = MemoryStore()
    private val model = ScriptedModel(models = listOf(ScriptedModel.modelInfo(), ScriptedModel.modelInfo("other")))
    private val commands = TestCommands()
    private val sealed = Observer(TurnPoints.CONVERSATION_SEALED)
    private val coder = AgentId("coder")
    private val helper = AgentId("helper")
    private val configured = mapOf(
        coder to AgentDefaults(model.ref, null, LanguageTag("en")),
        helper to AgentDefaults(model.ref),
    )

    /** `coder` is the default of `test:main`, and `helper` answers in its home chat `test:main:home`. */
    private fun harness(configure: PluginHarness.Builder.() -> Unit = {}): PluginHarness = agentHarness(
        """
        {
          "previewIntervalMillis": 0,
          "shutdown": {"turnGraceSeconds": 0, "cancelJoinSeconds": 2},
          "agents": {
            "coder": {"model": "scripted/test-model", "language": "en", "channels": {"test:main": {"default": true}}},
            "helper": {"model": "scripted/test-model", "channels": {"test:main": {"home": "home"}}}
          }
        }
        """.trimIndent(),
        store,
    ) { channel().model(model).commandHandler(commands).hook(sealed).apply(configure) }

    private fun PluginHarness.Running.start(): RecordingChannel {
        commands.control = get()
        return channel()
    }

    private fun snapshot(
        agent: AgentId,
        source: AgentSource,
        block: ChatSettingsSnapshot.Builder.() -> Unit = {},
    ): ChatSettingsSnapshot = ChatSettingsSnapshot.builder(agent)
        .agentSource(source)
        .defaults(configured.getValue(agent))
        .apply(block)
        .build()

    private val Admission.accepted: Admission.Accepted get() = this as Admission.Accepted

    private val Admission.turn: TurnId get() = accepted.ticket.turn

    /** The reply of the turn [admission] started, once it ended. */
    private suspend fun RecordingChannel.answered(admission: Admission): RecordedReply {
        admission.accepted.ticket.outcome()
        return reply(admission.turn).awaitEnd()
    }

    @Test
    fun `a message after new lands in the new conversation, and conversation sealed fires with the command's turn`() {
        model.reply { text("One.") }.reply { text("Two.") }

        harness().execute {
            val channel = start()
            val old = channel.answered(channel.receive("Hi")).turn.conversation
            val new = channel.receiveCommand("new")
            val after = channel.answered(channel.receive("Again")).turn

            val rotated = assertIs<TurnOutcome.Completed>(new.accepted.ticket.outcome())
            val payload = sealed.seen.single()
            assertEquals(old to after.conversation, payload.sealed to payload.successor)
            assertNotEquals(old, after.conversation)
            assertEquals(
                listOf(new.turn, TurnKind.COMMAND, old, channel.user()),
                listOf(payload.turn.id, payload.turn.kind, payload.turn.conversation, payload.turn.actor),
            )
            assertEquals(1, model.requests.last().history.size)
            val notice = channel.sent.single()
            assertEquals(
                "Started ${after.conversation}." to MessageKind.NOTICE,
                notice.message.text to notice.message.kind,
            )
            assertEquals(mapOf(channel.chat() to notice.delivery), rotated.deliveries)
        }
    }

    @Test
    fun `cancel stops the running turn that a waiting new waits for, and new seals the conversation after it`() {
        model.reply {
            text("Hel")
            hang()
        }.reply { text("Fresh.") }

        harness().execute {
            val channel = start()
            val running = channel.receive("Hi")
            model.awaitRequests(1)
            val new = channel.receiveCommand("new")
            delay(100)

            assertFalse(new.ticket.ended)
            assertTrue(channel.receiveCommand("cancel").accepted.ticket.outcome() is TurnOutcome.Completed)
            assertEquals(TurnOutcome.Cancelled, running.accepted.ticket.outcome())
            assertTrue(new.accepted.ticket.outcome() is TurnOutcome.Completed)
            val after = channel.answered(channel.receive("Again")).turn
            assertEquals(sealed.seen.single().successor, after.conversation)
            assertEquals(listOf("Cancelled.", "Started ${after.conversation}."), channel.sent.map { it.message.text })
        }
    }

    @Test
    fun `agent switches the chat at once, also beside its running turn and in a home chat`() {
        val first = CompletableDeferred<Unit>()
        model.reply {
            text("Coder.")
            pause { first.await() }
        }.reply { text("Helper.") }.reply { text("Helper at home.") }.reply { text("Coder at home.") }

        harness().execute {
            val channel = start()
            val running = channel.receive("Hi")
            model.awaitRequests(1)
            channel.receiveCommand("agent", "helper").accepted.ticket.outcome()
            val switched = channel.answered(channel.receive("Who?")).turn
            first.complete(Unit)
            val home = channel.chat("home")
            val atHome = channel.answered(channel.receive("Hi", home)).turn
            channel.receiveCommand("agent", "coder", home).accepted.ticket.outcome()
            val switchedHome = channel.answered(channel.receive("Who?", home)).turn

            assertEquals(coder, channel.answered(running).turn.agent)
            assertEquals(listOf(helper, helper, coder), listOf(switched, atHome, switchedHome).map { it.agent })
            assertEquals(listOf(helper, coder), commands.snapshots.map { it.agent })
            assertEquals(snapshot(coder, AgentSource.SELECTED), commands.control.settings(home))
        }
    }

    @Test
    fun `model, reasoning and language change after the key's running turn, and apply to its next turns`() {
        val first = CompletableDeferred<Unit>()
        model.reply {
            text("Old.")
            pause { first.await() }
        }.reply { text("New.") }
        val tuned = snapshot(coder, AgentSource.DEFAULT) {
            model(ModelRef.parse("scripted/other"))
            reasoning(ReasoningEffort.HIGH)
            language(LanguageTag("de"))
        }

        harness().execute {
            val channel = start()
            val running = channel.receive("Hi")
            model.awaitRequests(1)
            val tune = channel.receiveCommand("tune", "scripted/other high de")
            delay(100)

            assertFalse(tune.ticket.ended)
            assertEquals(LanguageTag("en"), get<ChatSettings>().language(channel.chat()))
            first.complete(Unit)
            assertTrue(tune.accepted.ticket.outcome() is TurnOutcome.Completed)
            val after = channel.answered(channel.receive("Again")).turn
            channel.answered(running)

            assertEquals(listOf(tuned), commands.snapshots)
            assertEquals(tuned, commands.control.settings(channel.chat()))
            val (old, new) = model.requests
            assertEquals(listOf(model.ref, ModelRef.parse("scripted/other")), listOf(old.model, new.model))
            assertEquals(listOf(null, ReasoningEffort.HIGH), listOf(old.options.reasoning, new.options.reasoning))
            assertEquals(LanguageTag("de"), after.language)
            assertEquals(LanguageTag("de"), get<ChatSettings>().language(channel.chat()))
            assertNull(get<ChatSettings>().language(ChatAddress.parse("test:other:1")))
        }
    }

    @Test
    fun `settings name why their agent serves the chat and what the agent is configured with`() {
        harness().execute {
            val control = get<AgentControl>()
            val chat = channel().chat()
            val home = channel().chat("home")

            val before = listOf(chat, home, channel().chat("home", "1")).map { control.settings(it) }
            val switched = control.updateSettings(chat, ChatSettingsUpdate.builder().agent(helper).build(), null)
            val homeSwitched = control.updateSettings(home, ChatSettingsUpdate.builder().agent(helper).build(), null)
            val reset = control.updateSettings(chat, ChatSettingsUpdate.builder().resetAgent().build(), null)

            assertEquals(
                listOf(
                    snapshot(coder, AgentSource.DEFAULT),
                    snapshot(helper, AgentSource.HOME) { home(true) },
                    snapshot(helper, AgentSource.HOME),
                ),
                before,
            )
            assertEquals(snapshot(helper, AgentSource.SELECTED), switched)
            assertEquals(snapshot(helper, AgentSource.SELECTED) { home(true) }, homeSwitched)
            assertEquals(snapshot(coder, AgentSource.DEFAULT), reset)
        }
    }

    @Test
    fun `agents lists the agents of an instance in config order with their homes there`() {
        val config = """
            {
              "agents": {
                "helper": {
                  "name": "Helper",
                  "description": "Answers questions.",
                  "model": "scripted/test-model",
                  "channels": {"test:main": {"home": "home"}, "test:side": {"home": "7#2"}}
                },
                "coder": {"model": "scripted/test-model", "channels": {"test:main": {"default": true}}},
                "planner": {"model": "scripted/test-model"}
              }
            }
        """.trimIndent()

        agentHarness(config, store) { channel().channel("side").model(model) }.execute {
            val control = get<AgentControl>()
            val main = channel().instance
            val side = channel("side").instance

            assertEquals(
                listOf(
                    AgentSummary(helper, "Helper", "Answers questions.", false, channel().chat("home")),
                    AgentSummary(coder, "coder", null, true, null),
                ),
                control.agents(main),
            )
            assertEquals(
                listOf(AgentSummary(helper, "Helper", "Answers questions.", true, channel("side").chat("7", "2"))),
                control.agents(side),
            )
            assertEquals(emptyList(), control.agents(ChannelInstanceId.parse("test:other")))
        }
    }

    @Test
    fun `a settings update that breaks a rule is refused with a message and changes nothing`() {
        harness().execute {
            val channel = start()
            val control = get<AgentControl>()
            val home = channel.chat("home")

            suspend fun refused(
                chat: ChatAddress = channel.chat(),
                update: ChatSettingsUpdate.Builder.() -> Unit,
            ): String? = assertFailsWith<IllegalArgumentException> {
                control.updateSettings(chat, ChatSettingsUpdate.builder().apply(update).build(), null)
            }.message

            assertEquals("Agent 'planner' does not serve test:main.", refused { agent(AgentId("planner")).home() })
            assertEquals("There is no model endpoint 'nowhere'.", refused { model(ModelRef.parse("nowhere/m")) })
            assertEquals(
                "Model endpoint 'scripted' has no model 'missing'.",
                refused { model(ModelRef.parse("scripted/missing")) },
            )
            assertEquals(
                "'extreme' is no reasoning effort: use ${ReasoningEffort.entries.joinToString()}.",
                refused { agent(helper).reasoning(ReasoningEffort.of("extreme")) },
            )
            assertEquals("test:main:home is the home chat of agent 'helper'.", refused(home) { agent(coder).home() })

            assertEquals(snapshot(coder, AgentSource.DEFAULT), control.settings(channel.chat()))
            assertEquals(snapshot(helper, AgentSource.HOME) { home(true) }, control.settings(home))
            assertEquals(
                "No agent serves test:other:1.",
                assertFailsWith<IllegalArgumentException> {
                    control.settings(ChatAddress.parse("test:other:1"))
                }.message,
            )
        }
    }

    @Test
    fun `a refusal is told in the chat's language, else its agent's, else the host's`() {
        val german = TextCatalog.builder()
            .text("alexandrite-agent", LanguageTag("de"), "control.unknown_endpoint", "Kein Endpunkt '{endpoint}'.")
            .text("alexandrite-agent", LanguageTag("de"), "control.home_taken", "{chat} gehört '{agent}'.")
            .build()

        harness { texts(german).language(LanguageTag("de")) }.execute {
            val control = get<AgentControl>()
            val chat = channel().chat()
            val nowhere = ChatSettingsUpdate.builder().model(ModelRef.parse("nowhere/m")).build()

            val english = assertFailsWith<IllegalArgumentException> { control.updateSettings(chat, nowhere, null) }
            control.updateSettings(chat, ChatSettingsUpdate.builder().language(LanguageTag("de")).build(), null)
            val chosen = assertFailsWith<IllegalArgumentException> { control.updateSettings(chat, nowhere, null) }
            val toCoder = ChatSettingsUpdate.builder().agent(coder).home().build()
            val taken = assertFailsWith<IllegalArgumentException> {
                control.updateSettings(channel().chat("home"), toCoder, null)
            }
            val unserved =
                assertFailsWith<IllegalArgumentException> { control.settings(ChatAddress.parse("test:other:1")) }

            assertEquals("There is no model endpoint 'nowhere'.", english.message)
            assertEquals("Kein Endpunkt 'nowhere'.", chosen.message)
            assertEquals("test:main:home gehört 'helper'.", taken.message)
            assertEquals("No agent serves test:other:1.", unserved.message)
        }
    }
}
