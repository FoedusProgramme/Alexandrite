package org.foedusprogramme.alexandrite.sdk.turn

import kotlinx.serialization.json.JsonObject
import org.foedusprogramme.alexandrite.sdk.channel.IncomingMessage
import org.foedusprogramme.alexandrite.sdk.channel.Markup
import org.foedusprogramme.alexandrite.sdk.channel.MessageKind
import org.foedusprogramme.alexandrite.sdk.channel.OutboundMessage
import org.foedusprogramme.alexandrite.sdk.channel.rebuild
import org.foedusprogramme.alexandrite.sdk.chat.ChannelMessageRef
import org.foedusprogramme.alexandrite.sdk.chat.ChatInfo
import org.foedusprogramme.alexandrite.sdk.chat.ChatKind
import org.foedusprogramme.alexandrite.sdk.chat.ConversationId
import org.foedusprogramme.alexandrite.sdk.chat.TurnId
import org.foedusprogramme.alexandrite.sdk.chat.TurnInfo
import org.foedusprogramme.alexandrite.sdk.chat.TurnKind
import org.foedusprogramme.alexandrite.sdk.model.FinishKind
import org.foedusprogramme.alexandrite.sdk.model.FinishReason
import org.foedusprogramme.alexandrite.sdk.model.ModelEvent
import org.foedusprogramme.alexandrite.sdk.model.ModelRequest
import org.foedusprogramme.alexandrite.sdk.model.PromptSection
import org.foedusprogramme.alexandrite.sdk.model.RequestIds
import org.foedusprogramme.alexandrite.sdk.model.Trust
import org.foedusprogramme.alexandrite.sdk.model.TurnContextItem
import org.foedusprogramme.alexandrite.sdk.model.Usage
import org.foedusprogramme.alexandrite.sdk.model.rebuild
import org.foedusprogramme.alexandrite.sdk.tool.ToolDefinition
import org.foedusprogramme.alexandrite.sdk.tool.ToolRisk
import org.foedusprogramme.alexandrite.sdk.transcript.InlineMedia
import org.foedusprogramme.alexandrite.sdk.transcript.MediaId
import org.foedusprogramme.alexandrite.sdk.transcript.MediaKind
import org.foedusprogramme.alexandrite.sdk.transcript.MediaPart
import org.foedusprogramme.alexandrite.sdk.transcript.StoredMedia
import org.foedusprogramme.alexandrite.sdk.transcript.TextPart
import org.foedusprogramme.alexandrite.sdk.transcript.ToolOutcome
import org.foedusprogramme.alexandrite.sdk.transcript.ToolResultEntry
import org.foedusprogramme.alexandrite.sdk.transcript.UserEntry
import org.foedusprogramme.alexandrite.sdk.transcript.ada
import org.foedusprogramme.alexandrite.sdk.transcript.call
import org.foedusprogramme.alexandrite.sdk.transcript.chat
import org.foedusprogramme.alexandrite.sdk.transcript.claude
import org.foedusprogramme.alexandrite.sdk.transcript.record
import org.foedusprogramme.alexandrite.sdk.transcript.reply
import org.foedusprogramme.alexandrite.sdk.transcript.result
import org.foedusprogramme.alexandrite.sdk.transcript.user
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class PayloadsTest {
    private val turn = TurnInfo.builder(TurnId("t1"), chat, ConversationId("c1"), TurnKind.MESSAGE).actor(ada).build()
    private val ids = RequestIds(ConversationId("c1"), TurnId("t1"), 0)
    private val message = IncomingMessage.builder(
        ChannelMessageRef(chat, "42"),
        ada,
        ChatInfo(ChatKind.DIRECT, null, null),
        Instant.parse("2026-10-08T09:00:00Z"),
        forwarded = null,
    ).text("Hello").build()

    private fun tool(name: String) = ToolDefinition(name, "Does $name.", JsonObject(emptyMap()), ToolRisk.READ_ONLY)

    private fun inline(): MediaPart = MediaPart(MediaKind.IMAGE, "image/png", InlineMedia(byteArrayOf(1, 2)))

    // Interceptor payloads.

    @Test
    fun `each with method replaces its own property and keeps the turn`() {
        val sections = PromptSections(turn, listOf(PromptSection("persona", "You are Athena.", stable = true)))
        val start = TurnStart(turn, message, listOf(tool("notes.add"), tool("notes.find")))
        val input = TurnInput(turn, message, "Hello", followUp = false)
        val preview = ReplyPreview(turn, 1, "Hel")

        val newSections = sections.withSections(emptyList())
        val narrowed = start.withTools(listOf(tool("notes.find")))
        val rewritten = input.withText("Hello!")
        val longer = preview.withText("Hello")

        assertEquals(PromptSections(turn, emptyList()), newSections)
        assertEquals(TurnStart(turn, message, listOf(tool("notes.find"))), narrowed)
        assertEquals(TurnInput(turn, message, "Hello!", followUp = false), rewritten)
        assertEquals(ReplyPreview(turn, 1, "Hello"), longer)
        listOf(newSections, narrowed, rewritten, longer).forEach { assertSame(turn, it.turn) }
    }

    @Test
    fun `turn context only grows`() {
        val recall = TurnContextItem("recall", "Ada likes tea.")
        val marker = TurnContextItem("marker", "Answer briefly.", Trust.TRUSTED)

        val context = TurnContext(turn, "Hello", emptyList()) + recall + marker

        assertEquals(listOf(recall, marker), context.items)
        assertEquals("Hello", context.text)
    }

    @Test
    fun `a model call belongs to its turn and round, also after a hook replaced the request`() {
        val request = ModelRequest.builder(claude, listOf(user("Hello")), 0, ids).build()
        val call = ModelCall(turn, 0, request)

        val replaced = call.withRequest(request.rebuild { cacheKey("telegram:work:-100") })

        assertEquals("telegram:work:-100", replaced.request.cacheKey)
        assertFailsWith<IllegalArgumentException> { ModelCall(turn, 1, request) }
        assertFailsWith<IllegalArgumentException> {
            call.withRequest(request.rebuild { ids(RequestIds(ConversationId("c2"), TurnId("t1"), 0)) })
        }
        assertFailsWith<IllegalArgumentException> {
            call.withRequest(request.rebuild { ids(RequestIds(ConversationId("c1"), TurnId("t2"), 0)) })
        }
        assertFailsWith<IllegalArgumentException> { ModelCall(turn, -1, request) }
    }

    @Test
    fun `a replaced reply keeps its kind and conversation`() {
        val final = OutboundMessage.builder("Hello, Ada.", MessageKind.REPLY).conversation(ConversationId("c1")).build()
        val draft = ReplyDraft(turn, final)

        val redacted = draft.withMessage(final.rebuild { text("Hello.").markup(Markup.MARKDOWN) })

        assertEquals("Hello.", redacted.message.text)
        assertFailsWith<IllegalArgumentException> { draft.withMessage(final.rebuild { kind(MessageKind.NOTICE) }) }
        assertFailsWith<IllegalArgumentException> { draft.withMessage(final.rebuild { conversation(null) }) }
    }

    @Test
    fun `interceptor payloads reject impossible values`() {
        val section = PromptSection("persona", "You are Athena.", stable = true)

        assertFailsWith<IllegalArgumentException> { PromptSections(turn, listOf(section, section)) }
        assertFailsWith<IllegalArgumentException> { TurnInput(turn, null, "Hello", followUp = true) }
        assertFailsWith<IllegalArgumentException> { ReplyPreview(turn, -1, "Hel") }
        assertFailsWith<IllegalArgumentException> {
            ToolCallCheck(turn, call("toolu_01"), tool("notes.find"), ToolRisk.EXEC)
        }
        assertEquals(ToolRisk.EXEC, ToolCallCheck(turn, call("toolu_01"), tool("notes.add"), ToolRisk.EXEC).risk)
        val finish = FinishReason(FinishKind.END_TURN, null, null)
        val completed = ModelEvent.Completed(reply(TextPart("Hi.")), finish, Usage.builder().build())
        assertFailsWith<IllegalArgumentException> { ModelReply(turn, -1, completed) }
    }

    // Observer payloads.

    @Test
    fun `observed payloads refer to media by its stored id`() {
        val stored = MediaPart(MediaKind.IMAGE, "image/png", StoredMedia(MediaId("m-1")))
        val photo = UserEntry(record(1), listOf(TextPart("Look."), stored), user("x").origin)
        val inlinePhoto = UserEntry(record(1), listOf(inline()), user("x").origin)
        val chart =
            ToolResultEntry(record(2), call("toolu_01").id, "notes.add", listOf(inline()), ToolOutcome.Succeeded)

        assertEquals(listOf(photo), ContextLoaded(turn, listOf(photo)).history)
        assertFailsWith<IllegalArgumentException> { ContextLoaded(turn, listOf(photo, inlinePhoto)) }
        assertFailsWith<IllegalArgumentException> { ToolCallDone(turn, call("toolu_01"), chart) }
        assertFailsWith<IllegalArgumentException> {
            TurnCommitted(turn, listOf(inlinePhoto), TurnOutcome.Completed(null), null)
        }
    }

    @Test
    fun `a tool call is done with its own result, and a turn commits stored entries`() {
        val done = ToolCallDone(turn, call("toolu_01"), result("toolu_01"))

        assertEquals("toolu_01", done.result.callId.value)
        assertFailsWith<IllegalArgumentException> { ToolCallDone(turn, call("toolu_01"), result("toolu_02")) }
        assertFailsWith<IllegalArgumentException> {
            TurnCommitted(turn, listOf(user("Hello")), TurnOutcome.Completed(null), null)
        }
        assertEquals(1, TurnCommitted(turn, listOf(user("Hello", record(1))), TurnOutcome.Cancelled, null).entries.size)
    }

    @Test
    fun `a sealed conversation has another successor`() {
        val sealed = ConversationSealed(turn, ConversationId("c1"), ConversationId("c2"))

        assertEquals(ConversationId("c2"), sealed.successor)
        assertFailsWith<IllegalArgumentException> {
            ConversationSealed(turn, ConversationId("c1"), ConversationId("c1"))
        }
    }
}
