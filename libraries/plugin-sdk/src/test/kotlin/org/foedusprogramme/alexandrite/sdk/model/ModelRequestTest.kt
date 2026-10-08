package org.foedusprogramme.alexandrite.sdk.model

import kotlinx.serialization.json.Json
import org.foedusprogramme.alexandrite.sdk.chat.ConversationId
import org.foedusprogramme.alexandrite.sdk.chat.ToolCallId
import org.foedusprogramme.alexandrite.sdk.chat.TurnId
import org.foedusprogramme.alexandrite.sdk.transcript.Dialect
import org.foedusprogramme.alexandrite.sdk.transcript.InlineMedia
import org.foedusprogramme.alexandrite.sdk.transcript.MediaId
import org.foedusprogramme.alexandrite.sdk.transcript.MediaKind
import org.foedusprogramme.alexandrite.sdk.transcript.MediaPart
import org.foedusprogramme.alexandrite.sdk.transcript.ProviderData
import org.foedusprogramme.alexandrite.sdk.transcript.StoredMedia
import org.foedusprogramme.alexandrite.sdk.transcript.TextPart
import org.foedusprogramme.alexandrite.sdk.transcript.ToolOutcome
import org.foedusprogramme.alexandrite.sdk.transcript.ToolResultEntry
import org.foedusprogramme.alexandrite.sdk.transcript.TranscriptEntry
import org.foedusprogramme.alexandrite.sdk.transcript.UserEntry
import org.foedusprogramme.alexandrite.sdk.transcript.call
import org.foedusprogramme.alexandrite.sdk.transcript.json
import org.foedusprogramme.alexandrite.sdk.transcript.reply
import org.foedusprogramme.alexandrite.sdk.transcript.user
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelRequestTest {
    // Requests.

    @Test
    fun `a request starts from no instructions, tools or turn context and the provider's defaults`() {
        val request = request().build()

        assertEquals(emptyList(), request.instructions)
        assertEquals(emptyList(), request.turnContext)
        assertEquals(emptyList(), request.tools)
        assertEquals(ToolChoice.Auto, request.toolChoice)
        assertEquals(ModelOptions.DEFAULT, request.options)
        assertNull(request.cacheKey)
        assertEquals(ProviderData.EMPTY, request.providerOptions)
    }

    @Test
    fun `a rebuilt request keeps what the block leaves alone`() {
        val request = request(listOf(user("Earlier"), reply(TextPart("Hi")), user("Now")), turnStart = 2)
            .instructions(listOf(PromptSection("persona", "You are Alexandrite.", stable = true)))
            .turnContext(listOf(TurnContextItem("recall", "Ada likes tea.", Trust.TRUSTED)))
            .tools(listOf(tool("notes.add")))
            .toolChoice(ToolChoice.Named("notes.add"))
            .options(ModelOptions.builder().reasoning(ReasoningEffort.HIGH).build())
            .cacheKey("c1")
            .providerOptions(ProviderData.EMPTY.with(Dialect("openai-chat"), json("""{"user":"ada"}""")))
            .build()

        val narrowed = request.rebuild {
            tools(emptyList())
            toolChoice(ToolChoice.None)
            ids(RequestIds(ConversationId("c1"), TurnId("t1"), 1))
        }

        assertEquals(request, request.toBuilder().build())
        assertEquals(request.hashCode(), request.toBuilder().build().hashCode())
        assertEquals(request.history, narrowed.history)
        assertEquals(request.instructions, narrowed.instructions)
        assertEquals(request.turnContext, narrowed.turnContext)
        assertEquals(request.cacheKey, narrowed.cacheKey)
        assertEquals(emptyList(), narrowed.tools)
        assertEquals(1, narrowed.ids.round)
        assertNotEquals(request, narrowed)
    }

    @Test
    fun `a request keeps the lists it was built from as they were`() {
        val history = mutableListOf<TranscriptEntry>(user("Hello"))
        val sections = mutableListOf(PromptSection("persona", "Be brief.", stable = true))
        val builder = request(history).instructions(sections)

        history += user("Again")
        sections.clear()
        val request = builder.build()
        history.clear()

        assertEquals(listOf(user("Hello")), request.history)
        assertEquals(listOf(PromptSection("persona", "Be brief.", stable = true)), request.instructions)
    }

    @Test
    fun `a request's turn starts within its history`() {
        val history = listOf(user("Earlier"), reply(TextPart("Hi")), user("Now"))

        for (turnStart in 0..3) assertEquals(turnStart, request(history, turnStart).build().turnStart)
        for (turnStart in listOf(-1, 4)) {
            assertFailsWith<IllegalArgumentException>("$turnStart") { request(history, turnStart).build() }
        }
    }

    @Test
    fun `a request names each prompt section and tool once`() {
        val section = PromptSection("persona", "Be brief.", stable = true)

        assertFailsWith<IllegalArgumentException> { request().instructions(listOf(section, section)).build() }
        assertFailsWith<IllegalArgumentException> {
            request().tools(listOf(tool("notes.add"), tool("notes.list"), tool("notes.add"))).build()
        }
    }

    @Test
    fun `a request offers the tools its choice forces`() {
        val notes = request().tools(listOf(tool("notes.add")))
        val add = ToolChoice.Named("notes.add")

        assertEquals(add, notes.toolChoice(add).build().toolChoice)
        assertEquals(ToolChoice.Required, notes.toolChoice(ToolChoice.Required).build().toolChoice)
        assertEquals(ToolChoice.None, request().toolChoice(ToolChoice.None).build().toolChoice)
        assertFailsWith<IllegalArgumentException> { notes.toolChoice(ToolChoice.Named("notes.list")).build() }
        assertFailsWith<IllegalArgumentException> { request().toolChoice(ToolChoice.Required).build() }
    }

    @Test
    fun `a request carries its media inline`() {
        val stored = MediaPart(MediaKind.IMAGE, "image/png", StoredMedia(MediaId("m-1")))
        val inline = MediaPart(MediaKind.IMAGE, "image/png", InlineMedia(byteArrayOf(1, 2)))
        fun photo(part: MediaPart) = UserEntry(null, listOf(TextPart("Look"), part), user("x").origin)
        fun snapshot(part: MediaPart) =
            ToolResultEntry(null, ToolCallId("a"), "notes.add", listOf(part), ToolOutcome.Succeeded)

        assertEquals(3, request(listOf(photo(inline), reply(call("a")), snapshot(inline))).build().history.size)
        assertFailsWith<IllegalArgumentException> { request(listOf(user("Hi"), photo(stored))).build() }
        assertFailsWith<IllegalArgumentException> {
            request(listOf(user("Hi"), reply(call("a")), snapshot(stored))).build()
        }
        assertFailsWith<IllegalArgumentException> { request().cacheKey("").build() }
    }

    @Test
    fun `prompt sections and turn-context items name what they are`() {
        val item = TurnContextItem("recall", "Ada likes tea.")

        assertEquals(Trust.UNTRUSTED, item.trust)
        assertEquals(TurnContextFallback.DROP, item.fallback)
        assertFailsWith<IllegalArgumentException> { TurnContextItem("", "text") }
        assertFailsWith<IllegalArgumentException> { PromptSection("", "text", stable = false) }
        assertFailsWith<IllegalArgumentException> { PromptSection("a\nb", "text", stable = false) }
    }

    // Request ids.

    @Test
    fun `a tool call id minted for a backend without ids follows from turn, round and part`() {
        val ids = RequestIds(ConversationId("c1"), TurnId("t-1"), 2)

        assertEquals(ToolCallId("call-t-1-2-0"), ids.callId(0))
        assertEquals(ids.callId(3), RequestIds(ConversationId("c2"), TurnId("t-1"), 2).callId(3))
        assertNotEquals(ids.callId(0), ids.callId(1))
        assertNotEquals(ids.callId(0), RequestIds(ConversationId("c1"), TurnId("t-1"), 3).callId(0))
        assertNotEquals(ids.callId(12), RequestIds(ConversationId("c1"), TurnId("t-1-2"), 1).callId(2))
        assertFailsWith<IllegalArgumentException> { ids.callId(-1) }
        assertFailsWith<IllegalArgumentException> { RequestIds(ConversationId("c1"), TurnId("t1"), -1) }
    }

    // Options.

    @Test
    fun `options leave everything to the provider by default`() {
        val options = ModelOptions.builder().build()

        assertEquals(ModelOptions.DEFAULT, options)
        assertNull(options.maxOutputTokens)
        assertNull(options.temperature)
        assertNull(options.topP)
        assertEquals(emptyList(), options.stopSequences)
        assertNull(options.reasoning)
        assertNull(options.parallelToolCalls)
    }

    @Test
    fun `rebuilt options keep what the block leaves alone`() {
        val options = ModelOptions.builder()
            .maxOutputTokens(1024)
            .temperature(0.0)
            .topP(1.0)
            .stopSequences(listOf("END"))
            .reasoning(ReasoningEffort.NONE)
            .parallelToolCalls(false)
            .build()

        assertEquals(options, options.toBuilder().build())
        assertEquals(
            ModelOptions.builder().maxOutputTokens(1024).temperature(0.0).topP(1.0).stopSequences(listOf("END"))
                .reasoning(ReasoningEffort.MAX).parallelToolCalls(false).build(),
            options.rebuild { reasoning(ReasoningEffort.MAX) },
        )
    }

    @Test
    fun `options are validated`() {
        val invalid = listOf<ModelOptions.Builder.() -> Unit>(
            { maxOutputTokens(0) },
            { temperature(-0.1) },
            { temperature(Double.NaN) },
            { temperature(Double.POSITIVE_INFINITY) },
            { topP(1.1) },
            { topP(Double.NaN) },
            { stopSequences(listOf("END", "")) },
        )
        for ((index, setting) in invalid.withIndex()) {
            assertFailsWith<IllegalArgumentException>("$index") { ModelOptions.builder().apply(setting).build() }
        }
    }

    @Test
    fun `a reasoning effort keeps an id it does not know`() {
        assertEquals("\"xhigh\"", Json.encodeToString(ReasoningEffort.XHIGH))
        assertEquals(ReasoningEffort.NONE, Json.decodeFromString<ReasoningEffort>("\"none\""))
        assertEquals("adaptive", Json.decodeFromString<ReasoningEffort>("\"adaptive\"").id)
    }

    @Test
    fun `a forced tool has a valid name`() {
        assertEquals("notes.add", ToolChoice.Named("notes.add").name)
        assertFailsWith<IllegalArgumentException> { ToolChoice.Named("notes-add") }
        assertEquals("Required", ToolChoice.Required.toString())
    }

    // Model info.

    @Test
    fun `a model claims nothing beyond text unless its builder is told`() {
        val info = ModelInfo.builder("deepseek-chat", Dialect("deepseek")).build()

        assertNull(info.displayName)
        assertNull(info.contextWindow)
        assertNull(info.maxOutputTokens)
        assertEquals(emptySet(), info.inputMedia)
        assertFalse(info.nativeTools)
        assertFalse(info.parallelToolCalls)
        assertEquals(emptySet(), info.reasoningEfforts)
        assertFalse(info.streaming)
    }

    @Test
    fun `rebuilt model info keeps what the block leaves alone`() {
        val info = ModelInfo.builder("claude-opus", Dialect("anthropic"))
            .displayName("Claude Opus")
            .contextWindow(1_000_000)
            .maxOutputTokens(128_000)
            .inputMedia(setOf(MediaKind.IMAGE, MediaKind.FILE))
            .nativeTools(true)
            .parallelToolCalls(true)
            .reasoningEfforts(setOf(ReasoningEffort.NONE, ReasoningEffort.HIGH, ReasoningEffort.MAX))
            .streaming(true)
            .build()

        val narrowed = info.rebuild { contextWindow(200_000) }

        assertEquals(info, info.toBuilder().build())
        assertEquals(200_000, narrowed.contextWindow)
        assertEquals(info.reasoningEfforts, narrowed.reasoningEfforts)
        assertTrue(narrowed.parallelToolCalls)
    }

    @Test
    fun `model info is validated`() {
        val invalid = listOf<ModelInfo.Builder.() -> Unit>(
            { id("") },
            { contextWindow(0) },
            { maxOutputTokens(-1) },
            { parallelToolCalls(true) },
        )
        for ((index, setting) in invalid.withIndex()) {
            assertFailsWith<IllegalArgumentException>("$index") {
                ModelInfo.builder("m", Dialect("openai-chat")).apply(setting).build()
            }
        }
    }
}
