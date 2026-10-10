package org.foedusprogramme.alexandrite.testkit

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.foedusprogramme.alexandrite.sdk.chat.ToolCallId
import org.foedusprogramme.alexandrite.sdk.chat.TurnId
import org.foedusprogramme.alexandrite.sdk.model.FinishKind
import org.foedusprogramme.alexandrite.sdk.model.FinishReason
import org.foedusprogramme.alexandrite.sdk.model.ModelError
import org.foedusprogramme.alexandrite.sdk.model.ModelErrorKind
import org.foedusprogramme.alexandrite.sdk.model.ModelEvent
import org.foedusprogramme.alexandrite.sdk.model.ModelException
import org.foedusprogramme.alexandrite.sdk.model.ModelOptions
import org.foedusprogramme.alexandrite.sdk.model.Trust
import org.foedusprogramme.alexandrite.sdk.model.TurnContextMode
import org.foedusprogramme.alexandrite.sdk.model.Usage
import org.foedusprogramme.alexandrite.sdk.model.Warning
import org.foedusprogramme.alexandrite.sdk.model.rebuild
import org.foedusprogramme.alexandrite.sdk.transcript.AssistantEntry
import org.foedusprogramme.alexandrite.sdk.transcript.Dialect
import org.foedusprogramme.alexandrite.sdk.transcript.EndpointId
import org.foedusprogramme.alexandrite.sdk.transcript.ModelRef
import org.foedusprogramme.alexandrite.sdk.transcript.OpaquePart
import org.foedusprogramme.alexandrite.sdk.transcript.ReasoningPart
import org.foedusprogramme.alexandrite.sdk.transcript.ReasoningSeal
import org.foedusprogramme.alexandrite.sdk.transcript.SealKind
import org.foedusprogramme.alexandrite.sdk.transcript.TextPart
import org.foedusprogramme.alexandrite.sdk.transcript.ToolCallPart
import org.foedusprogramme.alexandrite.sdk.transcript.ToolOutcome
import org.foedusprogramme.alexandrite.sdk.transcript.ToolResultEntry
import org.foedusprogramme.alexandrite.sdk.transcript.TranscriptRules
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ScriptedModelTest {
    private val model = ScriptedModel()

    // The stream contract.

    @Test
    fun `a reply streams each part's deltas, then the part, in index order, and completes with the message`() {
        val usage = Usage.builder().inputTokens(10).outputTokens(4).contextTokens(10).build()
        model.reply {
            reasoning("Let me see.", summary = "Thinking", seal = "sig-1")
            text("Sav", "ing.")
            toolCall("notes.add", """{"text":"milk"}""")
            usage(usage)
        }
        val request = request(model)

        val events = model.collect(request)

        val seal = ReasoningSeal(model.ref, ScriptedModel.DIALECT, SealKind.SIGNATURE, "sig-1")
        val reasoning = ReasoningPart("Let me see.", "Thinking", seal)
        val call = ToolCallPart(request.ids.callId(2), "notes.add", """{"text":"milk"}""")
        val parts = listOf(reasoning, TextPart("Saving."), call)
        assertEquals(
            listOf(
                ModelEvent.ResponseStarted("scripted-1", "test-model", emptyList()),
                ModelEvent.ReasoningDelta(0, "Let me see.", "Thinking"),
                ModelEvent.ReasoningSealed(0, seal),
                ModelEvent.PartCompleted(0, reasoning),
                ModelEvent.TextDelta(1, "Sav"),
                ModelEvent.TextDelta(1, "ing."),
                ModelEvent.PartCompleted(1, TextPart("Saving.")),
                ModelEvent.ToolCallStarted(2, call.id, "notes.add"),
                ModelEvent.ToolArgumentsDelta(2, """{"text":"milk"}"""),
                ModelEvent.PartCompleted(2, call),
                ModelEvent.UsageUpdated(usage),
                ModelEvent.Completed(
                    AssistantEntry(null, parts, model.ref),
                    FinishReason(FinishKind.TOOL_USE, null, null),
                    usage,
                ),
            ),
            events,
        )
    }

    @Test
    fun `every kind of reply keeps the stream contract`() {
        val replies = listOf(
            scriptedReply {},
            scriptedReply { text("Hello") },
            scriptedReply { text() },
            scriptedReply { reasoning(seal = "redacted", sealKind = SealKind.REDACTED) },
            scriptedReply { reasoning(summary = "short") },
            scriptedReply { toolCall("notes.list", arguments = "") },
            scriptedReply { opaque("citation", JsonObject(mapOf("url" to JsonPrimitive("https://example.org")))) },
            scriptedReply {
                text("One")
                toolCall("notes.add", id = ToolCallId("mine"))
                toolCall("notes.list")
                text("Two")
            },
        )
        replies.forEach { model.reply(it) }

        for (reply in replies) assertStreamContract(model.collect(request(model)))
    }

    @Test
    fun `parts carry what the request and the endpoint name`() {
        val odd = ScriptedModel.modelInfo("other-model").rebuild { dialect(Dialect("odd")) }
        val other = ScriptedModel(models = listOf(odd))
        other.reply {
            reasoning("Hm.", seal = "plain", sealKind = SealKind.PLAIN)
            opaque("note", JsonObject(emptyMap()))
            toolCall("notes.add", id = ToolCallId("given"))
            toolCall("notes.list")
        }
        val request = request(other)

        val message = assertIs<ModelEvent.Completed>(other.collect(request).last()).message

        assertEquals(ModelRef(EndpointId("scripted"), "other-model"), message.producedBy)
        val seal = (message.parts[0] as ReasoningPart).seal!!
        assertEquals(listOf(other.ref, Dialect("odd"), SealKind.PLAIN), listOf(seal.origin, seal.dialect, seal.kind))
        assertEquals(Dialect("odd"), (message.parts[1] as OpaquePart).dialect)
        assertEquals(ToolCallId("given"), (message.parts[2] as ToolCallPart).id)
        assertEquals(request.ids.callId(3), (message.parts[3] as ToolCallPart).id)
    }

    @Test
    fun `the finish follows the parts unless it is scripted`() {
        model.reply { text("Done.") }
        model.reply { toolCall("notes.add") }
        model.reply {
            text("Cut")
            finish(FinishKind.MAX_OUTPUT_TOKENS, "length", "too long")
        }

        val finishes = List(3) { assertIs<ModelEvent.Completed>(model.collect(request(model)).last()).finish }

        assertEquals(
            listOf(
                FinishReason(FinishKind.END_TURN, null, null),
                FinishReason(FinishKind.TOOL_USE, null, null),
                FinishReason(FinishKind.MAX_OUTPUT_TOKENS, "length", "too long"),
            ),
            finishes,
        )
    }

    @Test
    fun `a scripted warning is part of the response's start`() {
        model.reply { warning("option", "top-p ignored") }

        val started = assertIs<ModelEvent.ResponseStarted>(model.collect(request(model)).first())

        assertEquals(listOf(Warning("option", "top-p ignored")), started.warnings)
    }

    @Test
    fun `the transcript rules hold for a turn of two rounds built from its responses`() {
        val turn = testTurn()
        model.reply {
            toolCall("notes.add", """{"text":"milk"}""")
            toolCall("notes.list")
        }
        model.reply(RequestMatch.round(1)) { text("Saved.") }
        val user = testUserEntry(turn)

        val first = assertIs<ModelEvent.Completed>(model.collect(request(model, 0, listOf(user))).last()).message
        val results = first.parts.filterIsInstance<ToolCallPart>().map {
            ToolResultEntry(null, it.id, it.name, listOf(TextPart("ok")), ToolOutcome.Succeeded)
        }
        val history = listOf(user, first) + results
        val second = assertIs<ModelEvent.Completed>(model.collect(request(model, 1, history)).last()).message

        assertEquals(emptyList(), TranscriptRules.check(history + second))
        assertEquals(emptyList(), model.problems)
        val ids = first.parts.filterIsInstance<ToolCallPart>().map { it.id }
        assertEquals(listOf(ToolCallId("call-test-turn-0-0"), ToolCallId("call-test-turn-0-1")), ids)
    }

    // Requests and the script.

    @Test
    fun `the model records the requests whose streams were collected`() {
        model.reply { text("a") }.reply { text("b") }
        val first = request(model, 0)
        val second = request(model, 1)

        model.stream(first)
        assertEquals(emptyList(), model.requests)
        model.collect(first)
        model.collect(second)

        assertEquals(listOf(first, second), model.requests)
        assertEquals(listOf(first), blocking { model.awaitRequests(1) })
        assertEquals(0, model.remaining)
        model.assertFinished()
    }

    @Test
    fun `a request takes the first queued step that matches it`() {
        val other = TurnId("other")
        model.reply(RequestMatch.round(1)) { text("round one") }
        model.reply(RequestMatch.turn(other)) { text("other turn") }
        model.reply(RequestMatch("tools offered") { it.tools.isNotEmpty() }) { text("with tools") }
        model.reply { text("anything") }

        val texts = listOf(
            request(model, 0, turn = testTurn { id(other) }),
            request(model, 1),
            request(model, 0),
            request(model, 0),
        ).map { (assertIs<ModelEvent.Completed>(model.collect(it).last()).message.parts.single() as TextPart).text }

        assertEquals(listOf("other turn", "round one", "with tools", "anything"), texts)
    }

    @Test
    fun `a request that no step answers fails clearly and is a problem`() {
        model.reply(RequestMatch.round(3)) { text("later") }

        val unmatched = assertFailsWith<AssertionError> { model.collect(request(model, 0)) }
        val exhausted = assertFailsWith<AssertionError> { ScriptedModel().let { it.collect(request(it)) } }

        assertEquals(
            "Scripted model 'scripted' got request 1 (round 0 of turn test-turn, model scripted/test-model), " +
                "but none of its 1 step(s) matches it: round 3.",
            unmatched.message,
        )
        assertContains(exhausted.message!!, "but its script has no step left.")
        assertEquals(listOf(unmatched.message), model.problems)
        val finished = assertFailsWith<AssertionError> { model.assertFinished() }
        assertEquals(
            "${unmatched.message}\nScripted model 'scripted' has 1 step(s) no request took: round 3.",
            finished.message,
        )
    }

    @Test
    fun `a request to another endpoint or with a broken history is a problem`() {
        model.reply { text("never") }
        val elsewhere = request(ScriptedModel(EndpointId("elsewhere")))
        val call = ToolCallPart(ToolCallId("c1"), "notes.add", "{}")
        val broken = request(model, history = listOf(testUserEntry(), AssistantEntry(null, listOf(call), model.ref)))

        assertFailsWith<AssertionError> { model.collect(elsewhere) }
        assertFailsWith<AssertionError> { model.collect(broken) }

        assertEquals(2, model.problems.size)
        assertContains(model.problems[0], "for endpoint 'elsewhere'")
        assertContains(model.problems[1], "whose history breaks the transcript rules: The tool calls 'c1'")
        assertEquals(1, model.remaining)
    }

    @Test
    fun `a request for a model the endpoint does not serve fails as the backend would`() {
        model.reply { text("kept") }
        val request = request(model).rebuild { model(ModelRef(EndpointId("scripted"), "missing")) }

        val error = assertFailsWith<ModelException> { model.collect(request) }

        assertEquals(ModelErrorKind.MODEL_NOT_FOUND, error.error.kind)
        assertEquals(emptyList(), model.problems)
        assertEquals(1, model.remaining)
    }

    // Errors and cancellation.

    @Test
    fun `a scripted failure is thrown before any event`() {
        val error = ModelError.builder(ModelErrorKind.RATE_LIMITED, "Slow down.").build()
        model.fail(error).fail(ModelErrorKind.OVERLOADED)
        val events = mutableListOf<ModelEvent>()

        val thrown = assertFailsWith<ModelException> { blocking { model.stream(request(model)).collect(events::add) } }
        val overloaded = assertFailsWith<ModelException> { model.collect(request(model)) }

        assertEquals(error, thrown.error)
        assertFalse(thrown.error.outputStarted)
        assertEquals(emptyList(), events)
        assertEquals(ModelErrorKind.OVERLOADED, overloaded.error.kind)
        assertTrue(overloaded.error.retryable)
    }

    @Test
    fun `a failure within a reply follows the events before it and says that output started`() {
        model.reply {
            text("Hal")
            fail(ModelErrorKind.SERVER_ERROR, "Gone.")
        }
        val events = mutableListOf<ModelEvent>()

        val thrown = assertFailsWith<ModelException> { blocking { model.stream(request(model)).collect(events::add) } }

        assertEquals(ModelErrorKind.SERVER_ERROR, thrown.error.kind)
        assertTrue(thrown.error.outputStarted)
        assertEquals(
            listOf("ResponseStarted", "TextDelta", "PartCompleted"),
            events.map { it::class.simpleName },
        )
    }

    @Test
    fun `a hanging reply emits what comes before and ends when its collector is cancelled`() {
        model.reply {
            text("Thinking")
            hang()
        }
        val events = mutableListOf<ModelEvent>()

        blocking {
            coroutineScope {
                val job = launch { model.stream(request(model)).collect(events::add) }
                model.awaitRequests(1)
                while (events.size < 3) yield()
                job.cancelAndJoin()
                assertTrue(job.isCancelled)
            }
        }

        assertEquals(listOf("ResponseStarted", "TextDelta", "PartCompleted"), events.map { it::class.simpleName })
    }

    @Test
    fun `a paused reply holds its later events until the gate opens`() {
        val gate = CompletableDeferred<Unit>()
        model.reply {
            text("One")
            pause { gate.await() }
            text("Two")
        }
        val events = mutableListOf<ModelEvent>()

        blocking {
            coroutineScope {
                val stream = async(start = CoroutineStart.UNDISPATCHED) {
                    model.stream(request(model)).collect(events::add)
                }
                while (events.size < 3) yield()
                assertEquals(3, events.size)
                gate.complete(Unit)
                stream.await()
            }
        }

        assertStreamContract(events)
    }

    @Test
    fun `a reply ends where it hangs or fails`() {
        assertFailsWith<IllegalStateException> {
            scriptedReply {
                hang()
                text("never")
            }
        }
        assertFailsWith<IllegalStateException> {
            scriptedReply {
                fail(ModelErrorKind.TIMEOUT)
                hang()
            }
        }
        assertFailsWith<IllegalArgumentException> { scriptedReply { toolCall("") } }
    }

    // The endpoint.

    @Test
    fun `the endpoint lists its models and answers the turn-context modes it was given`() {
        val models = listOf(ScriptedModel.modelInfo("a"), ScriptedModel.modelInfo("b"))
        val endpoint = ScriptedModel(EndpointId("side"), models, TurnContextMode.NOT_SUPPORTED)
        val split = ScriptedModel(untrustedTurnContextMode = TurnContextMode.KEPT_UNRENDERED)

        assertEquals(models, blocking { endpoint.models() })
        assertEquals(ModelRef(EndpointId("side"), "a"), endpoint.ref)
        assertEquals(TurnContextMode.NOT_SUPPORTED, endpoint.turnContextMode("a", ModelOptions.DEFAULT, Trust.TRUSTED))
        assertEquals(
            TurnContextMode.NOT_SUPPORTED,
            endpoint.turnContextMode("a", ModelOptions.DEFAULT, Trust.UNTRUSTED),
        )
        val mode = model.turnContextMode("test-model", ModelOptions.DEFAULT, Trust.UNTRUSTED)
        assertEquals(TurnContextMode.TRANSIENT, mode)
        assertEquals(
            listOf(TurnContextMode.TRANSIENT, TurnContextMode.KEPT_UNRENDERED),
            listOf(Trust.TRUSTED, Trust.UNTRUSTED).map {
                split.turnContextMode("test-model", ModelOptions.DEFAULT, it)
            },
        )
        assertFailsWith<IllegalArgumentException> { ScriptedModel(models = emptyList()) }
        assertFailsWith<IllegalArgumentException> { ScriptedModel(models = listOf(models[0], models[0])) }
    }

    @Test
    fun `the default model takes tools, streams and reasons`() {
        val info = ScriptedModel.modelInfo()

        assertEquals("test-model", info.id)
        assertEquals(ScriptedModel.DIALECT, info.dialect)
        assertTrue(info.nativeTools && info.parallelToolCalls && info.streaming)
        assertTrue(info.reasoningEfforts.isNotEmpty())
        assertEquals(TEST_MODEL, model.ref)
    }

    @Test
    fun `responses of concurrent requests do not mix`() {
        repeat(20) { n -> model.reply(RequestMatch.round(n)) { text("$n") } }

        val texts = blocking {
            coroutineScope {
                (0 until 20).map { n -> async { model.stream(request(model, n)).toList() } }.map { it.await() }
            }
        }.map { (assertIs<ModelEvent.Completed>(it.last()).message.parts.single() as TextPart).text }

        assertEquals((0 until 20).map { "$it" }, texts)
    }
}
