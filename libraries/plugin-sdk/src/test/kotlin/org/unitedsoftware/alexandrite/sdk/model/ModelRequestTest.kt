package org.unitedsoftware.alexandrite.sdk.model

import org.unitedsoftware.alexandrite.sdk.chat.ConversationId
import org.unitedsoftware.alexandrite.sdk.chat.ToolCallId
import org.unitedsoftware.alexandrite.sdk.chat.TurnId
import org.unitedsoftware.alexandrite.sdk.transcript.Dialect
import org.unitedsoftware.alexandrite.sdk.transcript.InlineMedia
import org.unitedsoftware.alexandrite.sdk.transcript.MediaId
import org.unitedsoftware.alexandrite.sdk.transcript.MediaKind
import org.unitedsoftware.alexandrite.sdk.transcript.MediaPart
import org.unitedsoftware.alexandrite.sdk.transcript.StoredMedia
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolOutcome
import org.unitedsoftware.alexandrite.sdk.transcript.ToolResultEntry
import org.unitedsoftware.alexandrite.sdk.transcript.TranscriptEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UserEntry
import org.unitedsoftware.alexandrite.sdk.transcript.call
import org.unitedsoftware.alexandrite.sdk.transcript.reply
import org.unitedsoftware.alexandrite.sdk.transcript.user
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class ModelRequestTest {
    // Requests.

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
    fun `a forced tool has a valid name`() {
        assertFailsWith<IllegalArgumentException> { ToolChoice.Named("notes-add") }
    }

    // Model info.

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
