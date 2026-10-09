package org.foedusprogramme.alexandrite.testkit.provider

import org.foedusprogramme.alexandrite.sdk.chat.ToolCallId
import org.foedusprogramme.alexandrite.sdk.model.FinishKind
import org.foedusprogramme.alexandrite.sdk.model.FinishReason
import org.foedusprogramme.alexandrite.sdk.model.ModelError
import org.foedusprogramme.alexandrite.sdk.model.ModelErrorKind
import org.foedusprogramme.alexandrite.sdk.model.ModelEvent
import org.foedusprogramme.alexandrite.sdk.model.ModelException
import org.foedusprogramme.alexandrite.sdk.model.Usage
import org.foedusprogramme.alexandrite.sdk.transcript.AssistantEntry
import org.foedusprogramme.alexandrite.sdk.transcript.AssistantPart
import org.foedusprogramme.alexandrite.sdk.transcript.TextPart
import org.foedusprogramme.alexandrite.sdk.transcript.ToolCallPart
import org.foedusprogramme.alexandrite.testkit.TEST_MODEL
import org.foedusprogramme.alexandrite.testkit.testModelRequest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StreamContractTest {
    private val request = testModelRequest()
    private val usage = Usage.builder().inputTokens(10).build()
    private val call = ToolCallPart(ToolCallId("c1"), "notes.add", "{}")

    private fun completed(vararg parts: AssistantPart) = ModelEvent.Completed(
        AssistantEntry(null, parts.toList(), TEST_MODEL),
        FinishReason(FinishKind.END_TURN, "stop", null),
        usage,
    )

    private fun broken(events: List<ModelEvent>, failure: ModelException? = null): String =
        assertFailsWith<AssertionError> { checkStream(request, events, failure) }.message.orEmpty()

    @Test
    fun `a stream that keeps the contract passes`() {
        checkStream(
            request,
            listOf(
                ModelEvent.ResponseStarted("r", "m", emptyList()),
                ModelEvent.TextDelta(0, "Hel"),
                ModelEvent.TextDelta(0, "lo"),
                ModelEvent.PartCompleted(0, TextPart("Hello")),
                ModelEvent.ToolCallStarted(1, call.id, call.name),
                ModelEvent.ToolArgumentsDelta(1, "{}"),
                ModelEvent.PartCompleted(1, call),
                ModelEvent.UsageUpdated(usage),
                completed(TextPart("Hello"), call),
            ),
            null,
        )
    }

    @Test
    fun `deltas that do not add up to their part are caught`() {
        val message =
            broken(
                listOf(
                    ModelEvent.TextDelta(0, "Hel"),
                    ModelEvent.PartCompleted(0, TextPart("Help")),
                    completed(TextPart("Help")),
                ),
            )

        assertTrue("text deltas" in message, message)
    }

    @Test
    fun `a delta after its part completed is caught`() {
        val message = broken(
            listOf(ModelEvent.PartCompleted(0, TextPart("a")), ModelEvent.TextDelta(0, "b"), completed(TextPart("a"))),
        )

        assertTrue("after its part completed" in message, message)
    }

    @Test
    fun `a message that differs from its completed parts is caught`() {
        val message = broken(listOf(ModelEvent.PartCompleted(0, TextPart("a")), completed(TextPart("b"))))

        assertTrue("completed message's parts" in message, message)
    }

    @Test
    fun `arguments before the call starts are caught`() {
        val message =
            broken(listOf(ModelEvent.ToolArgumentsDelta(0, "{}"), ModelEvent.PartCompleted(0, call), completed(call)))

        assertTrue("has not started" in message, message)
    }

    @Test
    fun `a stream without its terminal event is caught`() {
        val message = broken(listOf(ModelEvent.TextDelta(0, "a")))

        assertTrue("without a Completed event" in message, message)
    }

    @Test
    fun `a failure must say whether output started`() {
        val quiet = ModelException(ModelError.builder(ModelErrorKind.CONNECTION, "Gone.").build())

        val message = broken(listOf(ModelEvent.TextDelta(0, "a")), quiet)

        assertTrue("outputStarted" in message, message)
    }
}
