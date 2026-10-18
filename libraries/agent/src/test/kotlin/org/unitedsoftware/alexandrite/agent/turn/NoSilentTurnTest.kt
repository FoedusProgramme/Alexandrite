package org.unitedsoftware.alexandrite.agent.turn

import org.unitedsoftware.alexandrite.agent.agentHarness
import org.unitedsoftware.alexandrite.agent.execute
import org.unitedsoftware.alexandrite.sdk.channel.Delivery
import org.unitedsoftware.alexandrite.sdk.channel.DeliveryFailure
import org.unitedsoftware.alexandrite.sdk.channel.Markup
import org.unitedsoftware.alexandrite.sdk.channel.MessageKind
import org.unitedsoftware.alexandrite.sdk.chat.AgentChatKey
import org.unitedsoftware.alexandrite.sdk.chat.TurnKind
import org.unitedsoftware.alexandrite.sdk.model.FinishKind
import org.unitedsoftware.alexandrite.sdk.model.ModelError
import org.unitedsoftware.alexandrite.sdk.model.ModelErrorKind
import org.unitedsoftware.alexandrite.sdk.model.ModelInfo
import org.unitedsoftware.alexandrite.sdk.model.ReasoningEffort
import org.unitedsoftware.alexandrite.sdk.transcript.AssistantEntry
import org.unitedsoftware.alexandrite.sdk.transcript.NoticeEntry
import org.unitedsoftware.alexandrite.sdk.transcript.NoticeKind
import org.unitedsoftware.alexandrite.sdk.transcript.TranscriptEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UserEntry
import org.unitedsoftware.alexandrite.sdk.turn.Admission
import org.unitedsoftware.alexandrite.sdk.turn.TurnOutcome
import org.unitedsoftware.alexandrite.testkit.MemoryStore
import org.unitedsoftware.alexandrite.testkit.RecordingChannel
import org.unitedsoftware.alexandrite.testkit.ScriptedModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration

/** A message turn never ends without a reply or a notice that reached its chat. */
class NoSilentTurnTest {
    private val key = AgentChatKey.parse("coder@test:main:chat")
    private val undelivered = "The answer could not be sent here (unknown). It is kept in the conversation."
    private val refused = Delivery.NotDelivered(DeliveryFailure.UNKNOWN, "refused")
    private val busy = ModelError.builder(ModelErrorKind.OVERLOADED, "Busy.").retryAfter(Duration.ZERO).build()

    private class Run(val store: MemoryStore = MemoryStore(), val model: ScriptedModel = ScriptedModel(models = MODELS))

    private fun Run.execute(block: suspend Run.(RecordingChannel) -> Unit) {
        agentHarness(CONFIG, store) { channel().model(model) }.execute { block(channel()) }
    }

    private suspend fun Run.stored(): List<TranscriptEntry> =
        store.transcripts.entries(store.conversations.current(key).id).map { it.withoutRecord() }

    private suspend fun RecordingChannel.ask(): Admission.Accepted = receive("Hi") as Admission.Accepted

    // Blank answers.

    @Test
    fun `a blank answer cut off by the output limit is asked once more with the least reasoning and more room`() {
        val run = Run()
        run.model.reply {
            reasoning("A long thought.")
            finish(FinishKind.MAX_OUTPUT_TOKENS)
        }.reply { text("Hello") }

        run.execute { channel ->
            val admission = channel.ask()

            assertIs<TurnOutcome.Completed>(admission.ticket.outcome())
            assertEquals("Hello", channel.reply(admission.ticket.turn).completed?.text)
            val (first, second) = model.requests
            assertEquals(1000 to ReasoningEffort.HIGH, first.options.maxOutputTokens to first.options.reasoning)
            assertEquals(2000 to ReasoningEffort.NONE, second.options.maxOutputTokens to second.options.reasoning)
            assertEquals(first.history, second.history)
            assertEquals(listOf(UserEntry::class, AssistantEntry::class), stored().map { it::class })
        }
    }

    @Test
    fun `the room of a retry stays within the model's most output tokens`() {
        val run = Run(model = ScriptedModel(models = listOf(model(maxOutputTokens = 1500))))
        repeat(2) {
            run.model.reply {
                reasoning("A long thought.")
                finish(FinishKind.MAX_OUTPUT_TOKENS)
            }
        }

        run.execute { channel ->
            val admission = channel.ask()

            assertEquals(TurnOutcome.TakenBack, admission.ticket.outcome())
            assertEquals(listOf(1000, 1500), model.requests.map { it.options.maxOutputTokens })
            val text = "The model reached its output limit before it wrote an answer."
            assertEquals(listOf(NoticeEntry(null, text, NoticeKind.OUTPUT_LIMIT)), stored())
        }
    }

    // Final delivery.

    @Test
    fun `a final reply the channel refuses goes once more as plain text`() {
        val run = Run()
        run.model.reply { text("**Hello**") }

        run.execute { channel ->
            channel.scriptDeliveries(refused)
            val admission = channel.ask()

            val outcome = assertIs<TurnOutcome.Completed>(admission.ticket.outcome())
            assertEquals(Markup.MARKDOWN, channel.reply(admission.ticket.turn).completed?.markup)
            val sent = channel.sent.single()
            assertEquals(
                Triple("**Hello**", MessageKind.REPLY, Markup.PLAIN),
                sent.message.let {
                    Triple(it.text, it.kind, it.markup)
                },
            )
            assertEquals(mapOf(channel.chat() to sent.delivery), outcome.deliveries)
            assertTrue(stored().none { it is NoticeEntry })
        }
    }

    @Test
    fun `a final reply refused as plain text too leaves a notice that says so`() {
        val run = Run()
        run.model.reply { text("Hello") }

        run.execute { channel ->
            channel.scriptDeliveries(refused, refused)
            val admission = channel.ask()

            val outcome = assertIs<TurnOutcome.Completed>(admission.ticket.outcome())
            assertEquals(mapOf(channel.chat() to refused), outcome.deliveries)
            val notice = channel.sent.last()
            assertEquals(undelivered to MessageKind.NOTICE, notice.message.text to notice.message.kind)
            assertIs<Delivery.Delivered>(notice.delivery)
            assertEquals(NoticeEntry(null, undelivered, NoticeKind.UNDELIVERED), stored().last())
        }
    }

    @Test
    fun `a chat the bot may no longer write to gets nothing more`() {
        val run = Run()
        run.model.reply { text("Hello") }

        run.execute { channel ->
            channel.scriptDeliveries(Delivery.NotDelivered(DeliveryFailure.FORBIDDEN))
            val admission = channel.ask()

            assertIs<TurnOutcome.Completed>(admission.ticket.outcome())
            assertEquals(emptyList(), channel.sent)
        }
    }

    // The invariant.

    @Test
    fun `every message turn ends with a reply or a notice that reached its chat, whatever failed`() {
        val cases: Map<String, Run.(RecordingChannel) -> Unit> = mapOf(
            "a blank answer" to { repeat(2) { model.reply { reasoning("Nothing.") } } },
            "a blank answer cut off by the output limit" to {
                repeat(2) {
                    model.reply {
                        reasoning("A long thought.")
                        finish(FinishKind.MAX_OUTPUT_TOKENS)
                    }
                }
            },
            "a stream that ends without its finish" to {
                model.reply {
                    text("Hel")
                    stop()
                }
            },
            "a provider event that cannot be read" to {
                model.reply {
                    text("Hel")
                    crash(IllegalStateException("Unexpected JSON token at offset 7"))
                }
            },
            "a model error" to { model.fail(ModelErrorKind.AUTHENTICATION) },
            "a model error after the retries" to { repeat(4) { model.fail(busy) } },
            "a refusal" to {
                model.reply {
                    text("No.")
                    finish(FinishKind.REFUSAL)
                }
            },
            "a final reply that is not delivered" to { channel ->
                model.reply { text("Hello") }
                channel.scriptDeliveries(refused, refused)
            },
            "a final notice that is not delivered" to { channel ->
                repeat(2) { model.reply { reasoning("Nothing.") } }
                channel.scriptDeliveries(refused)
            },
            "a reply too long for its chat" to { channel ->
                model.reply { text("Hello") }
                channel.scriptDeliveries(Delivery.NotDelivered(DeliveryFailure.TOO_LONG))
            },
        )

        for ((case, script) in cases) {
            Run().execute { channel ->
                script(channel)
                val admission = channel.ask()
                admission.ticket.outcome()

                val turn = admission.ticket.turn
                assertEquals(TurnKind.MESSAGE, channel.reply(turn).turn.kind, case)
                val reached = channel.events.any { event ->
                    when (event) {
                        is RecordingChannel.Event.Completed ->
                            event.turn == turn &&
                                event.delivery is Delivery.Delivered

                        is RecordingChannel.Event.Sent -> event.delivery is Delivery.Delivered

                        else -> false
                    }
                }
                assertTrue(reached, "$case: ${channel.events}")
            }
        }
    }

    private companion object {
        val CONFIG = """
            {
              "previewIntervalMillis": 0,
              "models": {"retries": 3},
              "agents": {
                "coder": {
                  "model": "scripted/test-model",
                  "reasoning": "high",
                  "maxOutputTokens": 1000,
                  "channels": {"test:main": {}}
                }
              }
            }
        """.trimIndent()

        fun model(maxOutputTokens: Int = 8000): ModelInfo =
            ScriptedModel.modelInfo().toBuilder().maxOutputTokens(maxOutputTokens).build()

        val MODELS = listOf(model())
    }
}
