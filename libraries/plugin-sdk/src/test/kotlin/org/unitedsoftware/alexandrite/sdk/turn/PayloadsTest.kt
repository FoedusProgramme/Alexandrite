package org.unitedsoftware.alexandrite.sdk.turn

import kotlinx.serialization.json.JsonObject
import org.unitedsoftware.alexandrite.sdk.channel.IncomingMessage
import org.unitedsoftware.alexandrite.sdk.channel.Markup
import org.unitedsoftware.alexandrite.sdk.channel.MessageKind
import org.unitedsoftware.alexandrite.sdk.channel.OutboundMessage
import org.unitedsoftware.alexandrite.sdk.channel.rebuild
import org.unitedsoftware.alexandrite.sdk.chat.ChannelMessageRef
import org.unitedsoftware.alexandrite.sdk.chat.ChatInfo
import org.unitedsoftware.alexandrite.sdk.chat.ChatKind
import org.unitedsoftware.alexandrite.sdk.chat.ConversationId
import org.unitedsoftware.alexandrite.sdk.chat.TurnId
import org.unitedsoftware.alexandrite.sdk.chat.TurnInfo
import org.unitedsoftware.alexandrite.sdk.chat.TurnKind
import org.unitedsoftware.alexandrite.sdk.model.FinishKind
import org.unitedsoftware.alexandrite.sdk.model.FinishReason
import org.unitedsoftware.alexandrite.sdk.model.ModelEvent
import org.unitedsoftware.alexandrite.sdk.model.ModelRequest
import org.unitedsoftware.alexandrite.sdk.model.PromptSection
import org.unitedsoftware.alexandrite.sdk.model.RequestIds
import org.unitedsoftware.alexandrite.sdk.model.Usage
import org.unitedsoftware.alexandrite.sdk.model.rebuild
import org.unitedsoftware.alexandrite.sdk.tool.ToolDefinition
import org.unitedsoftware.alexandrite.sdk.tool.ToolRisk
import org.unitedsoftware.alexandrite.sdk.transcript.EntryId
import org.unitedsoftware.alexandrite.sdk.transcript.InlineMedia
import org.unitedsoftware.alexandrite.sdk.transcript.MediaId
import org.unitedsoftware.alexandrite.sdk.transcript.MediaKind
import org.unitedsoftware.alexandrite.sdk.transcript.MediaPart
import org.unitedsoftware.alexandrite.sdk.transcript.StoredMedia
import org.unitedsoftware.alexandrite.sdk.transcript.SummaryEntry
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolOutcome
import org.unitedsoftware.alexandrite.sdk.transcript.ToolResultEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UserEntry
import org.unitedsoftware.alexandrite.sdk.transcript.ada
import org.unitedsoftware.alexandrite.sdk.transcript.call
import org.unitedsoftware.alexandrite.sdk.transcript.chat
import org.unitedsoftware.alexandrite.sdk.transcript.claude
import org.unitedsoftware.alexandrite.sdk.transcript.record
import org.unitedsoftware.alexandrite.sdk.transcript.reply
import org.unitedsoftware.alexandrite.sdk.transcript.result
import org.unitedsoftware.alexandrite.sdk.transcript.user
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

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
    fun `a replaced reply keeps its kind, conversation and destination`() {
        val final = OutboundMessage.builder("Hello, Ada.", MessageKind.REPLY).conversation(ConversationId("c1")).build()
        val draft = ReplyDraft(turn, final, chat)

        val redacted = draft.withMessage(final.rebuild { text("Hello.").markup(Markup.MARKDOWN) })

        assertEquals("Hello.", redacted.message.text)
        assertEquals(chat, redacted.destination)
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
        assertFailsWith<IllegalArgumentException> {
            ToolCallCheck(turn, call("toolu_01"), tool("notes.add"), ToolRisk.EXEC).withRequireApproval(" ")
        }
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
    fun `a call is done with its own result, a turn commits stored entries and a conversation seals into another`() {
        val done = ToolCallDone(turn, call("toolu_01"), result("toolu_01"))

        assertEquals("toolu_01", done.result.callId.value)
        assertFailsWith<IllegalArgumentException> { ToolCallDone(turn, call("toolu_01"), result("toolu_02")) }
        assertFailsWith<IllegalArgumentException> {
            TurnCommitted(turn, listOf(user("Hello")), TurnOutcome.Completed(null), null)
        }
        assertEquals(1, TurnCommitted(turn, listOf(user("Hello", record(1))), TurnOutcome.Cancelled, null).entries.size)
        assertFailsWith<IllegalArgumentException> {
            ConversationSealed(turn, ConversationId("c1"), ConversationId("c1"))
        }
    }

    @Test
    fun `a compaction folds stored entries, and a hook's summary replaces nothing else`() {
        val entries = listOf(user("Hello", record(1)), user("Still there?", record(2)))
        val previous = SummaryEntry(record(3), "Ada said hello.", EntryId(1))
        val compaction = ContextCompaction(turn, entries, previous, "the open bugs")

        val written = compaction.withSummary("Ada asked twice.")

        assertNull(compaction.summary)
        assertEquals(ContextCompaction(turn, entries, previous, "the open bugs", "Ada asked twice."), written)
        assertFailsWith<IllegalArgumentException> { ContextCompaction(turn, emptyList(), null, null) }
        assertFailsWith<IllegalArgumentException> { ContextCompaction(turn, listOf(user("Hello")), null, null) }
        assertFailsWith<IllegalArgumentException> { compaction.withSummary(" ") }
        assertFailsWith<IllegalArgumentException> {
            ContextCompaction(turn, entries, SummaryEntry(null, "Unstored.", EntryId(1)), null)
        }
    }

    @Test
    fun `a compaction stored its summary, and a clearing clears at least one call`() {
        val summary = SummaryEntry(record(3), "Ada said hello.", EntryId(2))

        assertEquals(EntryId(2), ContextCompacted(turn, summary, 900, 100).summary.through)
        assertFailsWith<IllegalArgumentException> {
            ContextCompacted(turn, SummaryEntry(null, "Unstored.", EntryId(2)), 900, 100)
        }
        assertEquals(listOf(call("toolu_01")), ContextCleared(turn, listOf(call("toolu_01")), 300).calls)
        assertFailsWith<IllegalArgumentException> { ContextCleared(turn, emptyList(), 0) }
    }
}
