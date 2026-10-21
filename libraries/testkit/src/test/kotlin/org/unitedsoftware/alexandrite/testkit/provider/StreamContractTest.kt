package org.unitedsoftware.alexandrite.testkit.provider

import org.unitedsoftware.alexandrite.sdk.chat.ToolCallId
import org.unitedsoftware.alexandrite.sdk.model.FinishKind
import org.unitedsoftware.alexandrite.sdk.model.FinishReason
import org.unitedsoftware.alexandrite.sdk.model.ModelError
import org.unitedsoftware.alexandrite.sdk.model.ModelErrorKind
import org.unitedsoftware.alexandrite.sdk.model.ModelEvent
import org.unitedsoftware.alexandrite.sdk.model.ModelException
import org.unitedsoftware.alexandrite.sdk.model.Usage
import org.unitedsoftware.alexandrite.sdk.transcript.AssistantEntry
import org.unitedsoftware.alexandrite.sdk.transcript.AssistantPart
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolCallPart
import org.unitedsoftware.alexandrite.testkit.TEST_MODEL
import org.unitedsoftware.alexandrite.testkit.assertStreamContract
import org.unitedsoftware.alexandrite.testkit.testModelRequest
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
        assertFailsWith<AssertionError> { assertStreamContract(request, events, failure) }.message.orEmpty()

    @Test
    fun `a stream that keeps the contract passes`() {
        assertStreamContract(
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
    fun `a broken stream is caught with what broke it`() {
        val quiet = ModelException(ModelError.builder(ModelErrorKind.CONNECTION, "Gone.").build())
        val cases = listOf(
            "text deltas" to listOf(
                ModelEvent.TextDelta(0, "Hel"),
                ModelEvent.PartCompleted(0, TextPart("Help")),
                completed(TextPart("Help")),
            ),
            "after its part completed" to listOf(
                ModelEvent.PartCompleted(0, TextPart("a")),
                ModelEvent.TextDelta(0, "b"),
                completed(TextPart("a")),
            ),
            "completed message's parts" to listOf(ModelEvent.PartCompleted(0, TextPart("a")), completed(TextPart("b"))),
            "has not started" to
                listOf(ModelEvent.ToolArgumentsDelta(0, "{}"), ModelEvent.PartCompleted(0, call), completed(call)),
            "without a Completed event" to listOf(ModelEvent.TextDelta(0, "a")),
        )

        for ((expected, events) in cases) {
            val message = broken(events)
            assertTrue(expected in message, message)
        }
        val message = broken(listOf(ModelEvent.TextDelta(0, "a")), quiet)
        assertTrue("outputStarted" in message, message)
    }
}
