package org.unitedsoftware.alexandrite.agent.turn

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.unitedsoftware.alexandrite.agent.agentHarness
import org.unitedsoftware.alexandrite.agent.blocking
import org.unitedsoftware.alexandrite.agent.control.Choice
import org.unitedsoftware.alexandrite.agent.control.SettingsStates
import org.unitedsoftware.alexandrite.agent.execute
import org.unitedsoftware.alexandrite.agent.model.LARGE_PROMPT_TOKENS
import org.unitedsoftware.alexandrite.agent.model.crossedTo
import org.unitedsoftware.alexandrite.runtime.chat.standaloneChatStates
import org.unitedsoftware.alexandrite.sdk.channel.MessageKind
import org.unitedsoftware.alexandrite.sdk.chat.AgentChatKey
import org.unitedsoftware.alexandrite.sdk.chat.ToolCallId
import org.unitedsoftware.alexandrite.sdk.chat.agentState
import org.unitedsoftware.alexandrite.sdk.hook.HookDecision
import org.unitedsoftware.alexandrite.sdk.model.FinishKind
import org.unitedsoftware.alexandrite.sdk.model.ModelError
import org.unitedsoftware.alexandrite.sdk.model.ModelErrorKind
import org.unitedsoftware.alexandrite.sdk.model.Trust
import org.unitedsoftware.alexandrite.sdk.model.TurnContextFallback
import org.unitedsoftware.alexandrite.sdk.model.TurnContextItem
import org.unitedsoftware.alexandrite.sdk.model.TurnContextMode
import org.unitedsoftware.alexandrite.sdk.tool.ToolResult
import org.unitedsoftware.alexandrite.sdk.transcript.AssistantEntry
import org.unitedsoftware.alexandrite.sdk.transcript.Dialect
import org.unitedsoftware.alexandrite.sdk.transcript.EndpointId
import org.unitedsoftware.alexandrite.sdk.transcript.ModelRef
import org.unitedsoftware.alexandrite.sdk.transcript.NoticeEntry
import org.unitedsoftware.alexandrite.sdk.transcript.NoticeKind
import org.unitedsoftware.alexandrite.sdk.transcript.ProviderData
import org.unitedsoftware.alexandrite.sdk.transcript.ReasoningPart
import org.unitedsoftware.alexandrite.sdk.transcript.ReasoningSeal
import org.unitedsoftware.alexandrite.sdk.transcript.SealKind
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolCallPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolResultEntry
import org.unitedsoftware.alexandrite.sdk.transcript.TranscriptEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UserEntry
import org.unitedsoftware.alexandrite.sdk.turn.Admission
import org.unitedsoftware.alexandrite.sdk.turn.TurnOutcome
import org.unitedsoftware.alexandrite.sdk.turn.TurnPoints
import org.unitedsoftware.alexandrite.testkit.MemoryStore
import org.unitedsoftware.alexandrite.testkit.PluginHarness
import org.unitedsoftware.alexandrite.testkit.ScriptedModel
import org.unitedsoftware.alexandrite.testkit.recordingTool
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration

/** An agent's equal models, and a turn that moves on from one that fails. */
class EqualModelsTest {
    private val key = AgentChatKey.parse("coder@test:main:chat")
    private val first = ModelRef.parse("scripted/test-model")
    private val second = ModelRef.parse("other/big")
    private val read = recordingTool("fs.read") { _, _ -> ToolResult("Contents.") }

    private class Run(
        val store: MemoryStore = MemoryStore(),
        val main: ScriptedModel = ScriptedModel(),
        val other: ScriptedModel = ScriptedModel(EndpointId("other"), listOf(ScriptedModel.modelInfo("big"))),
    )

    private fun Run.harness(
        models: String = "[\"$first\", \"$second\"]",
        retries: Int = 0,
        configure: PluginHarness.Builder.() -> Unit = {},
    ): PluginHarness = agentHarness(
        """
            {
              "previewIntervalMillis": 0,
              "models": {"retries": $retries},
              "agents": {"coder": {"models": $models, "channels": {"test:main": {}}}}
            }
        """.trimIndent(),
        store,
    ) { channel(operators = setOf("operator")).model(main).model(other).tool(read).apply(configure) }

    private fun PluginHarness.Running.ask(): Admission.Accepted =
        channel().receive("Hi", sender = channel().user("operator")) as Admission.Accepted

    private suspend fun Run.stored(): List<TranscriptEntry> =
        store.transcripts.entries(store.conversations.current(key).id).map { it.withoutRecord() }

    private fun error(kind: ModelErrorKind, status: Int? = null): ModelError =
        ModelError.builder(kind, "Scripted $kind.").status(status).retryAfter(Duration.ZERO).build()

    private fun switched(why: String, from: ModelRef = first, to: ModelRef = second): String =
        "The model $from did not answer ($why), so $to answered instead."

    @Test
    fun `a model that answers 5xx moves the turn on to the next, which answers, and the chat is told`() {
        val run = Run()
        run.main.fail(error(ModelErrorKind.SERVER_ERROR, 503))
        run.other.reply { text("Hello") }

        run.harness().execute {
            val admission = ask()

            assertIs<TurnOutcome.Completed>(admission.ticket.outcome())
            assertEquals("Hello", channel().reply(admission.ticket.turn).completed?.text)
            val notice = channel().sent.single().message
            assertEquals(switched("server_error") to MessageKind.NOTICE, notice.text to notice.kind)
            val stored = run.stored()
            assertEquals(second, (stored[1] as AssistantEntry).producedBy)
            assertEquals(NoticeEntry(null, switched("server_error"), NoticeKind.MODEL_SWITCHED), stored.last())
            assertEquals(run.main.requests.single().history, run.other.requests.single().history)
        }
    }

    @Test
    fun `an untrusted turn-context item that only the next model reads taints the turn as it moves on`() {
        val dropped = TurnContextItem("web.ad", "An ad.", Trust.UNTRUSTED, TurnContextFallback.DROP)
        val run = Run(
            main = ScriptedModel(untrustedTurnContextMode = TurnContextMode.NOT_SUPPORTED),
            other = ScriptedModel(EndpointId("other"), listOf(ScriptedModel.modelInfo("big"))),
        )
        run.main.fail(error(ModelErrorKind.SERVER_ERROR, 503))
        run.other.reply { text("Hello") }
        val inject = Interceptor(TurnPoints.CONTEXT_INJECT) { HookDecision.Replace(it.plus(dropped)) }

        run.harness { hook(inject) }.execute {
            assertIs<TurnOutcome.Completed>(ask().ticket.outcome())

            assertEquals(emptyList(), run.main.requests.single().turnContext)
            assertEquals(listOf(dropped), run.other.requests.single().turnContext)
            val conversation = run.store.conversations.current(key).id
            assertEquals("untrusted turn context", run.store.conversations.taint(conversation)?.reason)
        }
    }

    @Test
    fun `an unreachable model, one still rate-limited after its retries, or an unknown one moves on too`() {
        val cases = mapOf(
            "connection" to error(ModelErrorKind.CONNECTION),
            "timeout" to error(ModelErrorKind.TIMEOUT),
            "overloaded" to error(ModelErrorKind.OVERLOADED, 529),
            "rate_limited" to error(ModelErrorKind.RATE_LIMITED, 429),
            "model_not_found" to error(ModelErrorKind.MODEL_NOT_FOUND, 404),
            "protocol" to error(ModelErrorKind.PROTOCOL, 502),
        )
        for ((why, error) in cases) {
            val retries = if (error.retryable) 1 else 0
            val run = Run()
            repeat(retries + 1) { run.main.fail(error) }
            run.other.reply { text("Hello") }

            run.harness(retries = retries).execute {
                val admission = ask()

                assertIs<TurnOutcome.Completed>(admission.ticket.outcome(), why)
                assertEquals("Hello", channel().reply(admission.ticket.turn).completed?.text, why)
                assertEquals(switched(why), channel().sent.single().message.text, why)
                assertEquals(0, run.main.remaining, why)
            }
        }
    }

    @Test
    fun `a large request is sent at most twice to each model, a small one as often as its retries allow`() {
        for (large in listOf(true, false)) {
            val run = Run()
            val sends = if (large) 2 else 4
            repeat(sends) { run.main.fail(error(ModelErrorKind.TIMEOUT)) }
            run.other.reply { text("Hello") }

            run.harness(retries = 3).execute {
                val text = if (large) "x".repeat(LARGE_PROMPT_TOKENS.toInt() * 4) else "Hi"
                val admission = channel().receive(text, sender = channel().user("operator")) as Admission.Accepted

                assertIs<TurnOutcome.Completed>(admission.ticket.outcome(), "large: $large")
                assertEquals(sends, run.main.requests.size, "large: $large")
                assertEquals(1, run.other.requests.size, "large: $large")
            }
        }
    }

    @Test
    fun `a model its endpoint does not list moves on before the first request`() {
        val run = Run()
        run.other.reply { text("Hello") }

        run.harness("[\"scripted/gone\", \"$second\"]").execute {
            val admission = ask()

            assertIs<TurnOutcome.Completed>(admission.ticket.outcome())
            assertEquals(0, run.main.requests.size)
            assertEquals(switched("unknown", ModelRef.parse("scripted/gone")), channel().sent.single().message.text)
        }
    }

    @Test
    fun `a refusal or another 4xx keeps the turn on its model`() {
        val cases = mapOf<String, Run.() -> Unit>(
            "authentication" to { main.fail(error(ModelErrorKind.AUTHENTICATION, 401)) },
            "invalid request" to { main.fail(error(ModelErrorKind.INVALID_REQUEST, 400)) },
            "quota" to { main.fail(error(ModelErrorKind.QUOTA_EXHAUSTED, 402)) },
            "refusal" to {
                main.reply {
                    text("No.")
                    finish(FinishKind.REFUSAL)
                }
            },
        )
        for ((case, script) in cases) {
            val run = Run().apply(script)

            run.harness().execute {
                val admission = ask()

                admission.ticket.outcome()
                assertEquals(0, run.other.requests.size, case)
                assertEquals(MessageKind.NOTICE, channel().reply(admission.ticket.turn).completed?.kind, case)
                assertEquals(emptyList(), channel().sent, case)
            }
        }
    }

    @Test
    fun `a turn moves on once, and a chat's own pick moves on to the model after it`() {
        val run = Run()
        val model = standaloneChatStates("alexandrite-agent", run.store.chatStates)
            .agentState(SettingsStates.MODEL, Choice<ModelRef>())
        blocking { model.set(key, Choice(second)) }
        run.other.fail(error(ModelErrorKind.SERVER_ERROR, 500))
        run.main.fail(error(ModelErrorKind.SERVER_ERROR, 500))

        run.harness().execute {
            val admission = ask()

            assertEquals(TurnOutcome.Failed("server_error: Scripted server_error."), admission.ticket.outcome())
            assertEquals(listOf(1, 1), listOf(run.other.requests.size, run.main.requests.size))
            assertEquals(
                "The model cannot be reached right now. Try again later.",
                channel().reply(admission.ticket.turn).completed?.text,
            )
        }
    }

    @Test
    fun `a turn that moves on in a later round goes on with its tool rounds and without the other's seals`() {
        val run = Run()
        run.main.reply {
            reasoning("Read it.", seal = "sig")
            toolCall("fs.read", """{"path": "a.txt"}""")
        }.fail(error(ModelErrorKind.SERVER_ERROR, 500))
        run.other.reply { text("Done.") }

        run.harness().execute {
            val admission = ask()

            assertIs<TurnOutcome.Completed>(admission.ticket.outcome())
            assertEquals("Done.", channel().reply(admission.ticket.turn).completed?.text)
            val history = run.other.requests.single().history
            assertEquals(
                listOf(UserEntry::class, AssistantEntry::class, ToolResultEntry::class),
                history.map { it::class },
            )
            val parts = (history[1] as AssistantEntry).parts
            assertEquals(ReasoningPart("Read it.", null, null), parts.first())
            assertEquals("fs.read", (parts.last() as ToolCallPart).name)
            assertEquals(2, run.main.requests.size)
        }
    }

    @Test
    fun `history for another endpoint leaves out the seals and provider data that cannot cross`() {
        val data = ProviderData.EMPTY.with(Dialect("scripted"), JsonObject(mapOf("k" to JsonPrimitive("v"))))
        val seal = ReasoningSeal(first, Dialect("scripted"), SealKind.SIGNATURE, "sig")
        val call = ToolCallPart(ToolCallId("c1"), "fs.read", "{}", data)
        val entry = AssistantEntry(
            null,
            listOf(ReasoningPart("Thought.", null, seal), ReasoningPart(null, null, seal), TextPart("Hi"), call),
            first,
            data,
        )

        assertEquals(listOf(entry), crossedTo(ModelRef.parse("scripted/other"), listOf(entry)))
        assertEquals(
            listOf(
                AssistantEntry(
                    null,
                    listOf(
                        ReasoningPart("Thought.", null, null),
                        TextPart("Hi"),
                        ToolCallPart(call.id, call.name, "{}"),
                    ),
                    first,
                ),
            ),
            crossedTo(second, listOf(entry)),
        )
    }
}
