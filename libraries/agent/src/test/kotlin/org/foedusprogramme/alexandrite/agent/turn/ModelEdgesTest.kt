package org.foedusprogramme.alexandrite.agent.turn

import org.foedusprogramme.alexandrite.agent.agentHarness
import org.foedusprogramme.alexandrite.agent.execute
import org.foedusprogramme.alexandrite.agent.logged
import org.foedusprogramme.alexandrite.sdk.channel.Delivery
import org.foedusprogramme.alexandrite.sdk.channel.DeliveryFailure
import org.foedusprogramme.alexandrite.sdk.channel.MessageKind
import org.foedusprogramme.alexandrite.sdk.channel.rebuild
import org.foedusprogramme.alexandrite.sdk.chat.AgentChatKey
import org.foedusprogramme.alexandrite.sdk.model.FinishKind
import org.foedusprogramme.alexandrite.sdk.model.ModelError
import org.foedusprogramme.alexandrite.sdk.model.ModelErrorKind
import org.foedusprogramme.alexandrite.sdk.store.TurnEndKind
import org.foedusprogramme.alexandrite.sdk.transcript.AssistantEntry
import org.foedusprogramme.alexandrite.sdk.transcript.NoticeEntry
import org.foedusprogramme.alexandrite.sdk.transcript.NoticeKind
import org.foedusprogramme.alexandrite.sdk.transcript.TextPart
import org.foedusprogramme.alexandrite.sdk.transcript.TranscriptEntry
import org.foedusprogramme.alexandrite.sdk.transcript.UserEntry
import org.foedusprogramme.alexandrite.sdk.turn.Admission
import org.foedusprogramme.alexandrite.sdk.turn.TurnOutcome
import org.foedusprogramme.alexandrite.testkit.MemoryStore
import org.foedusprogramme.alexandrite.testkit.PluginHarness
import org.foedusprogramme.alexandrite.testkit.RecordedReply.Preview
import org.foedusprogramme.alexandrite.testkit.RecordingChannel
import org.foedusprogramme.alexandrite.testkit.ScriptedModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class ModelEdgesTest {
    private val store = MemoryStore()
    private val model = ScriptedModel()
    private val key = AgentChatKey.parse("coder@test:main:chat")
    private val unavailable = "The model cannot be reached right now. Try again later."

    private fun harness(models: String = "{}", configure: PluginHarness.Builder.() -> Unit = {}): PluginHarness =
        agentHarness(
            """
            {
              "previewIntervalMillis": 0,
              "models": $models,
              "agents": {"coder": {"model": "scripted/test-model", "channels": {"test:main": {}}}}
            }
            """.trimIndent(),
            store,
        ) { channel().model(model).apply(configure) }

    private fun busy(retryAfter: Duration? = Duration.ZERO): ModelError =
        ModelError.builder(ModelErrorKind.OVERLOADED, "Busy.").retryAfter(retryAfter).build()

    private val Admission.accepted: Admission.Accepted get() = this as Admission.Accepted

    private suspend fun stored(): List<TranscriptEntry> =
        store.transcripts.entries(store.conversations.current(key).id).map { it.withoutRecord() }

    // Retries.

    @Test
    fun `a call that failed before any output and may pass is made again, and its answer delivered`() {
        model.fail(busy()).reply { text("Hello") }

        val lines = logged {
            harness().execute {
                val admission = channel().receive("Hi").accepted

                assertIs<TurnOutcome.Completed>(admission.ticket.outcome())
                assertEquals("Hello", channel().reply(admission.ticket.turn).completed?.text)
                assertEquals(2, model.requests.size)
                assertEquals(model.requests[0], model.requests[1])
                assertEquals(listOf(UserEntry::class, AssistantEntry::class), stored().map { it::class })
            }
        }

        assertEquals(1, lines.count { it.startsWith("WARN The model call of turn ") && "retry 1 of 3 in 0s" in it })
    }

    @Test
    fun `a call that failed after its output started is not made again`() {
        model.reply {
            text("Hel")
            fail(ModelErrorKind.OVERLOADED, "Busy.")
        }

        harness().execute {
            val admission = channel().receive("Hi").accepted

            assertEquals(TurnOutcome.Failed("overloaded: Busy."), admission.ticket.outcome())
            val reply = channel().reply(admission.ticket.turn)
            assertEquals(listOf(Preview(0, "Hel")), reply.previews)
            assertEquals(unavailable to MessageKind.NOTICE, reply.completed?.text to reply.completed?.kind)
            assertEquals(1, model.requests.size)
            assertEquals(listOf(NoticeEntry(null, unavailable, NoticeKind.FAILED)), stored())
        }
    }

    @Test
    fun `a call whose backend asks for a longer wait than allowed is not made again`() {
        model.fail(busy(retryAfter = 2.seconds))

        harness("""{"maxRetryAfterSeconds": 1}""").execute {
            val admission = channel().receive("Hi").accepted

            assertEquals(TurnOutcome.Failed("overloaded: Busy."), admission.ticket.outcome())
            assertEquals(1, model.requests.size)
        }
    }

    @Test
    fun `a turn whose retries run out fails with the notice of the last failure's kind`() {
        repeat(3) { model.fail(busy()) }

        harness("""{"retries": 2}""").execute {
            val admission = channel().receive("Hi").accepted

            assertEquals(TurnOutcome.Failed("overloaded: Busy."), admission.ticket.outcome())
            assertEquals(unavailable, channel().reply(admission.ticket.turn).completed?.text)
            assertEquals(3, model.requests.size)
            assertEquals(TurnEndKind.FAILED, store.conversations.turn(admission.ticket.turn)?.end)
        }
    }

    // Finishes.

    @Test
    fun `an answer that ends for any reason but the output limit is delivered as it is`() {
        val kinds = listOf(FinishKind.END_TURN, FinishKind.STOP_SEQUENCE, FinishKind.OTHER, FinishKind.of("future"))
        kinds.forEach { kind ->
            model.reply {
                text("Answer to $kind")
                finish(kind)
            }
        }

        harness().execute {
            for (kind in kinds) {
                val admission = channel().receive("Hi").accepted
                val outcome = admission.ticket.outcome()

                assertEquals("Answer to $kind", (outcome as TurnOutcome.Completed).reply?.let(::visible), "$kind")
                assertEquals("Answer to $kind", channel().reply(admission.ticket.turn).completed?.text)
            }
            assertEquals(emptyList(), channel().sent)
            assertEquals(emptyList(), stored().filterIsInstance<NoticeEntry>())
        }
    }

    @Test
    fun `an answer cut off at the output limit is delivered, followed by a notice`() {
        model.reply {
            text("Half an ans")
            finish(FinishKind.MAX_OUTPUT_TOKENS, "length")
        }
        val text = "The answer was cut off where it reached the model's output limit."

        harness().execute {
            val channel = channel()
            val admission = channel.receive("Hi").accepted
            val outcome = admission.ticket.outcome()

            val reply = channel.reply(admission.ticket.turn)
            val answer = store.transcripts.entries(store.conversations.current(key).id)[1] as AssistantEntry
            assertEquals(TurnOutcome.Completed(answer, mapOf(channel.chat() to reply.delivery!!)), outcome)
            assertEquals("Half an ans", reply.completed?.text)
            val notice = channel.sent.single().message
            assertEquals(
                listOf(text, MessageKind.NOTICE, reply.request.trigger),
                listOf(notice.text, notice.kind, notice.replyTo),
            )
            assertEquals(NoticeEntry(null, text, NoticeKind.OUTPUT_LIMIT), stored().last())
            assertEquals(3, stored().size)
            assertEquals(TurnEndKind.COMPLETED, store.conversations.turn(admission.ticket.turn)?.end)
        }
    }

    @Test
    fun `an output limit reached before any answer takes the message back with a notice`() {
        model.reply {
            reasoning("A long thought.")
            finish(FinishKind.MAX_OUTPUT_TOKENS)
        }
        val text = "The model reached its output limit before it wrote an answer."

        harness().execute {
            val admission = channel().receive("Hi").accepted

            assertEquals(TurnOutcome.TakenBack, admission.ticket.outcome())
            assertEquals(text, channel().reply(admission.ticket.turn).completed?.text)
            assertEquals(listOf(NoticeEntry(null, text, NoticeKind.OUTPUT_LIMIT)), stored())
            assertEquals(TurnEndKind.TAKEN_BACK, store.conversations.turn(admission.ticket.turn)?.end)
        }
    }

    @Test
    fun `a reply too long for its chat stays in the transcript and the chat gets a too-long notice`() {
        model.reply { text("Far too long to send") }
        val text = "The answer is too long to send here. It is kept in the conversation."

        agentHarness(CONFIG, store) { channel(partLength = 4).model(model) }.execute {
            val channel = channel()
            channel.scriptCapabilities(RecordingChannel.DEFAULT_CAPABILITIES.rebuild { maxPartsPerReply(2) })
            val admission = channel.receive("Hi").accepted
            val outcome = admission.ticket.outcome() as TurnOutcome.Completed

            val delivery = outcome.deliveries.getValue(channel.chat())
            assertEquals(DeliveryFailure.TOO_LONG, (delivery as Delivery.NotDelivered).kind)
            assertEquals(listOf(text to MessageKind.NOTICE), channel.sent.map { it.message.text to it.message.kind })
            assertEquals(NoticeEntry(null, text, NoticeKind.TOO_LONG), stored().last())
            assertEquals(TextPart("Far too long to send"), (stored()[1] as AssistantEntry).parts.single())
        }
    }

    private fun visible(entry: AssistantEntry): String = (entry.parts.single() as TextPart).text

    private companion object {
        val CONFIG = """
            {
              "agents": {"coder": {"model": "scripted/test-model", "channels": {"test:main": {}}}}
            }
        """.trimIndent()
    }
}
