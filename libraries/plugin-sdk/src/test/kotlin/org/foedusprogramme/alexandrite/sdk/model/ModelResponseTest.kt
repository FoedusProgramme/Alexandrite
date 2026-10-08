package org.foedusprogramme.alexandrite.sdk.model

import kotlinx.serialization.json.Json
import org.foedusprogramme.alexandrite.sdk.chat.ToolCallId
import org.foedusprogramme.alexandrite.sdk.transcript.AssistantEntry
import org.foedusprogramme.alexandrite.sdk.transcript.ReasoningSeal
import org.foedusprogramme.alexandrite.sdk.transcript.SealKind
import org.foedusprogramme.alexandrite.sdk.transcript.TextPart
import org.foedusprogramme.alexandrite.sdk.transcript.anthropic
import org.foedusprogramme.alexandrite.sdk.transcript.claude
import org.foedusprogramme.alexandrite.sdk.transcript.json
import org.foedusprogramme.alexandrite.sdk.transcript.record
import org.foedusprogramme.alexandrite.sdk.transcript.reply
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class ModelResponseTest {
    private val finish = FinishReason(FinishKind.END_TURN, "end_turn", null)

    // Events.

    @Test
    fun `an event names a part by a position in the message`() {
        val seal = ReasoningSeal(claude, anthropic, SealKind.SIGNATURE, "sig")
        val events = listOf(
            { index: Int -> ModelEvent.TextDelta(index, "Hi") },
            { index: Int -> ModelEvent.ReasoningDelta(index, "Hm", null) },
            { index: Int -> ModelEvent.ReasoningSealed(index, seal) },
            { index: Int -> ModelEvent.ToolCallStarted(index, ToolCallId("a"), "notes.add") },
            { index: Int -> ModelEvent.ToolArgumentsDelta(index, "{") },
            { index: Int -> ModelEvent.PartCompleted(index, TextPart("Hi")) },
        )
        for (event in events) {
            event(0)
            assertFailsWith<IllegalArgumentException>("${event(0)}") { event(-1) }
        }
        assertFailsWith<IllegalArgumentException> { ModelEvent.ReasoningDelta(0, null, null) }
        assertEquals("Thought", ModelEvent.ReasoningDelta(0, null, "Thought").summary)
    }

    @Test
    fun `a completed response's message is not stored yet`() {
        val usage = Usage.builder().build()

        val completed = ModelEvent.Completed(reply(TextPart("Hi")), finish, usage)

        assertEquals(emptyMap(), completed.providerData.entries)
        assertFailsWith<IllegalArgumentException> {
            ModelEvent.Completed(AssistantEntry(record(1), listOf(TextPart("Hi")), claude), finish, usage)
        }
    }

    @Test
    fun `a finish kind keeps an id it does not know`() {
        assertEquals("\"max_output_tokens\"", Json.encodeToString(FinishKind.MAX_OUTPUT_TOKENS))
        assertEquals(FinishKind.PAUSED, Json.decodeFromString<FinishKind>("\"paused\""))
        assertEquals("compaction", Json.decodeFromString<FinishKind>("\"compaction\"").id)
    }

    @Test
    fun `a warning says what happened`() {
        assertFailsWith<IllegalArgumentException> { Warning(" ", "Temperature ignored.") }
        assertFailsWith<IllegalArgumentException> { Warning("unsupported_option", "") }
    }

    // Usage.

    @Test
    fun `usage knows nothing but one request by default`() {
        val usage = Usage.builder().build()

        for (count in listOf(
            usage.inputTokens,
            usage.cacheReadTokens,
            usage.cacheWriteTokens,
            usage.outputTokens,
            usage.reasoningTokens,
            usage.contextTokens,
        )) {
            assertNull(count)
        }
        assertEquals(1, usage.requests)
        assertNull(usage.raw)
    }

    @Test
    fun `rebuilt usage keeps what the block leaves alone`() {
        val usage = Usage.builder()
            .inputTokens(12_000)
            .cacheReadTokens(10_000)
            .cacheWriteTokens(1_500)
            .outputTokens(800)
            .reasoningTokens(300)
            .contextTokens(7_000)
            .requests(2)
            .raw(json("""{"service_tier":"standard"}"""))
            .build()

        assertEquals(usage, usage.toBuilder().build())
        assertEquals(
            Usage.builder().inputTokens(12_000).cacheReadTokens(10_000).cacheWriteTokens(1_500).outputTokens(900)
                .reasoningTokens(300).contextTokens(7_000).requests(2).raw(json("""{"service_tier":"standard"}"""))
                .build(),
            usage.rebuild { outputTokens(900) },
        )
    }

    @Test
    fun `usage counts are never negative and a call makes a request at least`() {
        val invalid = listOf<Usage.Builder.() -> Unit>(
            { inputTokens(-1) },
            { cacheReadTokens(-1) },
            { cacheWriteTokens(-1) },
            { outputTokens(-1) },
            { reasoningTokens(-1) },
            { contextTokens(-1) },
            { requests(0) },
        )
        for ((index, setting) in invalid.withIndex()) {
            assertFailsWith<IllegalArgumentException>("$index") { Usage.builder().apply(setting).build() }
        }
    }

    @Test
    fun `input tokens include cached and context tokens, output tokens include reasoning`() {
        fun usage(setting: Usage.Builder.() -> Unit) = Usage.builder().apply(setting).build()

        usage { inputTokens(100).cacheReadTokens(60).cacheWriteTokens(40).contextTokens(100) }
        usage { cacheReadTokens(60).contextTokens(500).reasoningTokens(10) }
        usage { outputTokens(10).reasoningTokens(10) }
        val invalid = listOf<Usage.Builder.() -> Unit>(
            { inputTokens(100).cacheReadTokens(60).cacheWriteTokens(41) },
            { inputTokens(100).cacheReadTokens(101) },
            { inputTokens(100).contextTokens(101) },
            { outputTokens(10).reasoningTokens(11) },
        )
        for ((index, setting) in invalid.withIndex()) {
            assertFailsWith<IllegalArgumentException>("$index") { usage(setting) }
        }
    }

    // Errors.

    @Test
    fun `an error is retryable by default exactly when its kind is transient`() {
        val transient = listOf(
            ModelErrorKind.RATE_LIMITED,
            ModelErrorKind.OVERLOADED,
            ModelErrorKind.SERVER_ERROR,
            ModelErrorKind.TIMEOUT,
            ModelErrorKind.CONNECTION,
        )
        val lasting = listOf(
            ModelErrorKind.AUTHENTICATION,
            ModelErrorKind.PERMISSION_DENIED,
            ModelErrorKind.QUOTA_EXHAUSTED,
            ModelErrorKind.INVALID_REQUEST,
            ModelErrorKind.CONTEXT_WINDOW_EXCEEDED,
            ModelErrorKind.MODEL_NOT_FOUND,
            ModelErrorKind.CONTENT_FILTERED,
            ModelErrorKind.UNSUPPORTED,
            ModelErrorKind.PROTOCOL,
        )

        for (kind in transient) assertTrue(ModelError.builder(kind, "Failed.").build().retryable, "$kind")
        for (kind in lasting) assertFalse(ModelError.builder(kind, "Failed.").build().retryable, "$kind")
        assertFalse(ModelError.builder(ModelErrorKind.SERVER_ERROR, "No.").retryable(false).build().retryable)
        assertTrue(ModelError.builder(ModelErrorKind.PROTOCOL, "Cut off.").retryable(true).build().retryable)
        assertTrue(
            ModelError.builder(ModelErrorKind.PROTOCOL, "Busy.").kind(ModelErrorKind.OVERLOADED).build().retryable,
        )
    }

    @Test
    fun `a rebuilt error keeps what the block leaves alone`() {
        val error = ModelError.builder(ModelErrorKind.RATE_LIMITED, "Slow down.")
            .retryAfter(30.seconds)
            .status(429)
            .requestId("req_1")
            .rawType("rate_limit_error")
            .outputStarted(true)
            .build()

        assertEquals(error, error.toBuilder().build())
        assertEquals(
            ModelError.builder(ModelErrorKind.RATE_LIMITED, "Slow down.").retryAfter(30.seconds).status(429)
                .requestId("req_1").rawType("rate_limit_error").outputStarted(true).retryable(false).build(),
            error.rebuild { retryable(false) },
        )
        assertFalse(ModelError.builder(ModelErrorKind.TIMEOUT, "Too slow.").build().outputStarted)
    }

    @Test
    fun `an error is validated`() {
        val invalid = listOf<ModelError.Builder.() -> Unit>(
            { message(" ") },
            { status(99) },
            { status(600) },
            { retryAfter((-1).seconds) },
            { retryAfter(Duration.INFINITE) },
        )
        for ((index, setting) in invalid.withIndex()) {
            assertFailsWith<IllegalArgumentException>("$index") {
                ModelError.builder(ModelErrorKind.SERVER_ERROR, "Failed.").apply(setting).build()
            }
        }
        val now = ModelError.builder(ModelErrorKind.OVERLOADED, "Busy.").retryAfter(Duration.ZERO).build()
        assertEquals(Duration.ZERO, now.retryAfter)
    }

    @Test
    fun `a model exception carries its error`() {
        val error = ModelError.builder(ModelErrorKind.CONNECTION, "Connection refused.").build()
        val cause = IOException("refused")

        val exception = ModelException(error, cause)

        assertSame(error, exception.error)
        assertSame(cause, exception.cause)
        assertEquals("connection: Connection refused.", exception.message)
        assertNull(ModelException(error).cause)
    }

    @Test
    fun `an error kind keeps an id it does not know`() {
        assertEquals("\"quota_exhausted\"", Json.encodeToString(ModelErrorKind.QUOTA_EXHAUSTED))
        assertEquals(ModelErrorKind.PROTOCOL, Json.decodeFromString<ModelErrorKind>("\"protocol\""))
        assertEquals("billing_suspended", Json.decodeFromString<ModelErrorKind>("\"billing_suspended\"").id)
    }

    // Values.

    @Test
    fun `values are equal by their properties and print them`() {
        val cases = listOf(
            Triple({ ModelEvent.TextDelta(0, "Hi") }, ModelEvent.TextDelta(1, "Hi"), "TextDelta(index=0, text=Hi)"),
            Triple(
                { FinishReason(FinishKind.REFUSAL, "refusal", "cyber") },
                FinishReason(FinishKind.REFUSAL, "refusal", null),
                "FinishReason(kind=refusal, raw=refusal, detail=cyber)",
            ),
            Triple(
                { Warning("unsupported_option", "Temperature ignored.") },
                Warning("unsupported_option", "Top-p ignored."),
                "Warning(kind=unsupported_option, message=Temperature ignored.)",
            ),
            Triple(
                { PromptSection("persona", "Be brief.", stable = true) },
                PromptSection("persona", "Be brief.", stable = false),
                "PromptSection(id=persona, text=Be brief., stable=true)",
            ),
            Triple({ ids }, RequestIds(ids.conversation, ids.turn, 1), "RequestIds(conversation=c1, turn=t1, round=0)"),
            Triple(
                { TurnContextItem("recall", "Ada likes tea.") },
                TurnContextItem("recall", "Ada likes tea.", Trust.TRUSTED),
                "TurnContextItem(source=recall, text=Ada likes tea., trust=UNTRUSTED, fallback=DROP)",
            ),
            Triple({ ToolChoice.Named("notes.add") }, ToolChoice.Named("notes.list"), "Named(name=notes.add)"),
            Triple(
                { Usage.builder().inputTokens(5).build() },
                Usage.builder().inputTokens(6).build(),
                "Usage(inputTokens=5, cacheReadTokens=null, cacheWriteTokens=null, outputTokens=null, " +
                    "reasoningTokens=null, contextTokens=null, requests=1, raw=null)",
            ),
            Triple(
                { ModelError.builder(ModelErrorKind.TIMEOUT, "Too slow.").build() },
                ModelError.builder(ModelErrorKind.TIMEOUT, "Too slow.").outputStarted(true).build(),
                "ModelError(kind=timeout, message=Too slow., retryable=true, retryAfter=null, status=null, " +
                    "requestId=null, rawType=null, outputStarted=false)",
            ),
        )
        for ((make, differing, printed) in cases) {
            assertEquals(make(), make())
            assertEquals(make().hashCode(), make().hashCode(), printed)
            assertNotEquals(make(), differing)
            assertEquals(printed, make().toString())
            assertTrue(make().javaClass.methods.none { it.name == "copy" || it.name.startsWith("component") }, printed)
        }
    }
}
