package org.unitedsoftware.alexandrite.testkit

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.unitedsoftware.alexandrite.sdk.chat.ToolCallId
import org.unitedsoftware.alexandrite.sdk.chat.TurnId
import org.unitedsoftware.alexandrite.sdk.model.FinishKind
import org.unitedsoftware.alexandrite.sdk.model.FinishReason
import org.unitedsoftware.alexandrite.sdk.model.ModelError
import org.unitedsoftware.alexandrite.sdk.model.ModelErrorKind
import org.unitedsoftware.alexandrite.sdk.model.ModelEvent
import org.unitedsoftware.alexandrite.sdk.model.ModelException
import org.unitedsoftware.alexandrite.sdk.model.Usage
import org.unitedsoftware.alexandrite.sdk.model.rebuild
import org.unitedsoftware.alexandrite.sdk.transcript.AssistantEntry
import org.unitedsoftware.alexandrite.sdk.transcript.EndpointId
import org.unitedsoftware.alexandrite.sdk.transcript.ReasoningPart
import org.unitedsoftware.alexandrite.sdk.transcript.ReasoningSeal
import org.unitedsoftware.alexandrite.sdk.transcript.SealKind
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolCallPart
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
            request(model, 0, turn = testTurn(id = other)),
            request(model, 1),
            request(model, 0),
            request(model, 0),
        ).map { (assertIs<ModelEvent.Completed>(model.collect(it).last()).message.parts.single() as TextPart).text }

        assertEquals(listOf("other turn", "round one", "with tools", "anything"), texts)
    }

    @Test
    fun `a request that no step answers fails clearly, as no retry repeats, and is a problem`() {
        model.reply(RequestMatch.round(3)) { text("later") }

        val unmatched = assertFailsWith<ModelException> { model.collect(request(model, 0)) }.error
        val exhausted = assertFailsWith<ModelException> { ScriptedModel().let { it.collect(request(it)) } }.error

        assertEquals(
            "Scripted model 'scripted' got request 1 (round 0 of turn test-turn, model scripted/test-model), " +
                "but none of its 1 step(s) matches it: round 3.",
            unmatched.message,
        )
        assertEquals(ModelErrorKind.INVALID_REQUEST to false, unmatched.kind to unmatched.retryable)
        assertContains(exhausted.message, "but its script has no step left.")
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

        assertFailsWith<ModelException> { model.collect(elsewhere) }
        assertFailsWith<ModelException> { model.collect(broken) }

        assertEquals(2, model.problems.size)
        assertContains(model.problems[0], "for endpoint 'elsewhere'")
        assertContains(model.problems[1], "whose history breaks the transcript rules: The tool calls 'c1'")
        assertEquals(1, model.remaining)
    }

    // Errors and cancellation.

    @Test
    fun `a scripted failure is thrown before any event as one before the output, or after the events of its reply`() {
        val started = ModelError.builder(ModelErrorKind.SERVER_ERROR, "Gone.").outputStarted(true).build()
        val error = ModelError.builder(ModelErrorKind.RATE_LIMITED, "Slow down.").build()
        model.fail(started).fail(error).fail(ModelErrorKind.OVERLOADED)
        val events = mutableListOf<ModelEvent>()

        val thrown = List(2) {
            assertFailsWith<ModelException> { blocking { model.stream(request(model)).collect(events::add) } }
        }
        val overloaded = assertFailsWith<ModelException> { model.collect(request(model)) }

        assertEquals(emptyList(), events)
        assertEquals(listOf(started.rebuild { outputStarted(false) }, error), thrown.map { it.error })
        assertFalse(thrown.any { it.error.outputStarted })
        assertStreamContract(request(model), emptyList(), thrown.first())
        assertEquals(ModelErrorKind.OVERLOADED, overloaded.error.kind)
        assertTrue(overloaded.error.retryable)

        model.reply {
            text("Hal")
            fail(ModelErrorKind.SERVER_ERROR, "Gone.")
        }
        val within = assertFailsWith<ModelException> { blocking { model.stream(request(model)).collect(events::add) } }
        assertEquals(ModelErrorKind.SERVER_ERROR to true, within.error.kind to within.error.outputStarted)
        assertEquals(listOf("ResponseStarted", "TextDelta", "PartCompleted"), events.map { it::class.simpleName })
    }
}
