package org.unitedsoftware.alexandrite.agent.turn

import org.unitedsoftware.alexandrite.agent.agentHarness
import org.unitedsoftware.alexandrite.agent.delivery.SENT_SOURCE
import org.unitedsoftware.alexandrite.agent.execute
import org.unitedsoftware.alexandrite.sdk.chat.AgentChatKey
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.TurnKind
import org.unitedsoftware.alexandrite.sdk.model.Trust
import org.unitedsoftware.alexandrite.sdk.tool.ToolRisk
import org.unitedsoftware.alexandrite.sdk.transcript.ContextPart
import org.unitedsoftware.alexandrite.sdk.transcript.NoticeEntry
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolOutcome
import org.unitedsoftware.alexandrite.sdk.transcript.ToolResultEntry
import org.unitedsoftware.alexandrite.sdk.transcript.TranscriptEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UserEntry
import org.unitedsoftware.alexandrite.sdk.turn.Admission
import org.unitedsoftware.alexandrite.sdk.turn.InitiatedTurn
import org.unitedsoftware.alexandrite.sdk.turn.RefusalReason
import org.unitedsoftware.alexandrite.sdk.turn.TurnInitiation
import org.unitedsoftware.alexandrite.sdk.turn.TurnOutcome
import org.unitedsoftware.alexandrite.sdk.turn.TurnPoints
import org.unitedsoftware.alexandrite.testkit.MemoryStore
import org.unitedsoftware.alexandrite.testkit.PluginHarness
import org.unitedsoftware.alexandrite.testkit.ScriptedModel
import org.unitedsoftware.alexandrite.testkit.recordingTool
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** A heartbeat belongs to no chat: its reply reaches nobody, it asks nobody, and it speaks through chat.send. */
class HeartbeatTurnsTest {
    private val store = MemoryStore()
    private val model = ScriptedModel()
    private val coder = AgentId("coder")
    private val own = AgentChatKey(coder, ChatAddress.ofAgent(coder))
    private val exec = recordingTool("exec.run", ToolRisk.EXEC)

    private fun harness(configure: PluginHarness.Builder.() -> Unit = {}): PluginHarness = agentHarness(
        """
        {
          "previewIntervalMillis": 0,
          "agents": {"coder": {"model": "scripted/test-model", "channels": {"test:main": {"home": "home"}}}}
        }
        """.trimIndent(),
        store,
    ) { channel(operators = setOf("operator")).model(model).tool(exec).apply(configure) }

    private suspend fun PluginHarness.Running.beat(): TurnOutcome {
        val turn = InitiatedTurn.heartbeat(coder, "Look around.").trust(Trust.TRUSTED).build()
        return assertIs<Admission.Accepted>(get<TurnInitiation>().initiate("beat", turn)).ticket.outcome()
    }

    private suspend fun beats(): List<TranscriptEntry> =
        store.transcripts.entries(store.conversations.heartbeatBase(own).id)

    @Test
    fun `a beat runs at its agent's own chat, in its base conversation, and may end saying nothing`() {
        model.reply { text("") }
        val started = Interceptor(TurnPoints.TURN_START)

        harness { hook(started) }.execute {
            assertIs<TurnOutcome.Completed>(beat())

            val turn = started.seen.single().turn
            assertEquals(
                listOf(ChatAddress.ofAgent(coder), own, TurnKind.HEARTBEAT),
                listOf(turn.chat, turn.key, turn.kind),
            )
            assertEquals(emptyList(), channel().events)
            assertEquals(2, beats().size)
            assertTrue(beats().none { it is NoticeEntry })
            assertEquals(1, model.requests.size)
        }
    }

    @Test
    fun `a beat's gated call is refused unasked, and it tells the home chat through chat_send`() {
        model.reply { toolCall("exec.run", "{}") }
            .reply { toolCall("chat.send", """{"to": "home", "text": "The disk is almost full."}""") }
            .reply { text("") }
            .reply { text("I will clean it up.") }

        harness().execute {
            assertIs<TurnOutcome.Completed>(beat())
            channel().receive("What did you say?", channel().chat("home"), channel().user("operator"))
                .let { assertIs<Admission.Accepted>(it).ticket.outcome() }

            val (refused, sent) = beats().filterIsInstance<ToolResultEntry>()
            assertIs<ToolOutcome.NotRun>(refused.outcome)
            assertEquals(emptyList(), exec.calls)
            assertEquals(emptyList(), channel().approvals)
            assertTrue((sent.content.single() as TextPart).text.startsWith("Sent to test:main:home"))
            assertEquals("The disk is almost full.", channel().sent.single().message.text)
            val told = model.requests.last().history.filterIsInstance<UserEntry>().flatMap { it.parts }
                .filterIsInstance<ContextPart>().single { it.source == SENT_SOURCE }
            assertContains(told.text, "from a heartbeat:\n  > The disk is almost full.")
        }
    }

    @Test
    fun `only a heartbeat of a configured agent belongs to no chat`() {
        harness().execute {
            val stranger = InitiatedTurn.heartbeat(AgentId("stranger"), "Beat.").build()

            assertEquals(
                Admission.Refused(RefusalReason.UNKNOWN_CHAT),
                get<TurnInitiation>().initiate("beat", stranger),
            )
            assertFailsWith<IllegalArgumentException> {
                InitiatedTurn.builder(own, TurnKind.REMINDER, "Remind.").build()
            }
        }
    }
}
