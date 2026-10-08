package org.foedusprogramme.alexandrite.sdk.transcript

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.foedusprogramme.alexandrite.sdk.chat.ToolCallId
import org.foedusprogramme.alexandrite.sdk.chat.TurnKind
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TranscriptValuesTest {
    // Identifiers.

    @Test
    fun `a model reference splits at the first slash`() {
        val ref = ModelRef.parse("openrouter/anthropic/claude")

        assertEquals(ModelRef(EndpointId("openrouter"), "anthropic/claude"), ref)
        assertEquals("openrouter/anthropic/claude", ref.toString())
        assertEquals("\"openrouter/anthropic/claude\"", Json.encodeToString(ref))
        assertEquals(ref, Json.decodeFromString<ModelRef>("\"openrouter/anthropic/claude\""))
        for (text in listOf(
            "deepseek",
            "/chat",
            "Deep/chat",
            "deepseek/",
            "deepseek/a\nb",
        )) {
            assertNull(ModelRef.parseOrNull(text), text)
        }
        assertFailsWith<IllegalArgumentException> { ModelRef.parse("deepseek") }
    }

    @Test
    fun `identifiers are validated`() {
        for (make in listOf({ EntryId(0) }, { MediaId("") }, { EndpointId("Deep") }, { Dialect("openai_chat") })) {
            assertFailsWith<IllegalArgumentException> { make() }
        }
        assertFailsWith<IllegalArgumentException> { UserOrigin.Initiated("Clock", TurnKind.REMINDER) }
        assertFailsWith<IllegalArgumentException> { UserEntry(null, emptyList(), user("x").origin) }
    }

    @Test
    fun `an open enumeration keeps an id it does not know`() {
        assertEquals("hologram", Json.decodeFromString<MediaKind>("\"hologram\"").id)
        assertEquals(SealKind.PLAIN, Json.decodeFromString<SealKind>("\"plain\""))
        assertEquals("\"unknown_tool\"", Json.encodeToString(NotRunReason.UNKNOWN_TOOL))
        assertEquals("\"blank_reply\"", Json.encodeToString(NoticeKind.BLANK_REPLY))
    }

    // Media.

    @Test
    fun `inline media are equal by content and print only their size and hash`() {
        val bytes = byteArrayOf(1, 2, 3)
        val media = InlineMedia(bytes)

        bytes[0] = 9
        media.bytes()[1] = 9

        assertContentEquals(byteArrayOf(1, 2, 3), media.bytes())
        assertEquals(InlineMedia(byteArrayOf(1, 2, 3)), media)
        assertEquals(InlineMedia(byteArrayOf(1, 2, 3)).hashCode(), media.hashCode())
        assertNotEquals(InlineMedia(byteArrayOf(1, 2)), media)
        assertEquals(3, media.size)
        assertEquals("InlineMedia(size=3, sha256=039058c6f2c0cb49)", media.toString())
    }

    // Parts and entries.

    @Test
    fun `a reasoning seal prints no data`() {
        val seal = ReasoningSeal(claude, anthropic, SealKind.SIGNATURE, "secret-signature")

        assertEquals(
            "ReasoningSeal(origin=anthropic/claude-opus, dialect=anthropic, kind=signature, data=<16 chars>)",
            seal.toString(),
        )
        assertEquals(ReasoningSeal(claude, anthropic, SealKind.SIGNATURE, "secret-signature"), seal)
        assertNotEquals(ReasoningSeal(claude, anthropic, SealKind.SIGNATURE, "other-signature"), seal)
    }

    @Test
    fun `tool call arguments parse to an object when they are one`() {
        fun parsed(arguments: String) = ToolCallPart(ToolCallId("c"), "notes.add", arguments).parseArguments()

        assertEquals(json("""{"text":"milk"}"""), parsed("""{"text":"milk"}"""))
        assertEquals(JsonObject(emptyMap()), parsed(" "))
        assertNull(parsed("""{"text":"""))
        assertNull(parsed("[1]"))
        assertNull(parsed("\"text\""))
    }

    @Test
    fun `a response without visible text or tool calls is blank`() {
        val reasoning = ReasoningPart("thinking", null, null)

        assertTrue(reply().blank)
        assertTrue(reply(reasoning, TextPart(" \n")).blank)
        assertTrue(reply(OpaquePart(anthropic, "server_tool_use", JsonObject(emptyMap()))).blank)
        assertFalse(reply(reasoning, TextPart("Hi")).blank)
        assertFalse(reply(call("a")).blank)
    }

    @Test
    fun `values are equal by their properties and print them`() {
        val cases = listOf(
            Triple({ TextPart("hi") }, TextPart("ho"), "TextPart(text=hi)"),
            Triple({ StoredMedia(MediaId("m-1")) }, StoredMedia(MediaId("m-2")), "StoredMedia(id=m-1)"),
            Triple(
                { ToolOutcome.NotRun(NotRunReason.HOOK_DENIED) },
                ToolOutcome.NotRun(NotRunReason.HOOK_DENIED, "7"),
                "NotRun(reason=hook_denied, approval=null)",
            ),
            Triple(
                { UserOrigin.Initiated("clock", TurnKind.REMINDER) },
                UserOrigin.Initiated("clock", TurnKind.HEARTBEAT),
                "Initiated(plugin=clock, kind=reminder)",
            ),
            Triple(
                { record(1) },
                record(2),
                "EntryRecord(id=1, conversation=c1, turn=t1, createdAt=2026-10-07T08:30:00Z)",
            ),
            Triple(
                { SummaryEntry(null, "s", EntryId(4)) },
                SummaryEntry(record(1), "s", EntryId(4)),
                "SummaryEntry(record=null, text=s, through=4)",
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

    @Test
    fun `an entry given a record keeps everything else`() {
        fun stored(entry: TranscriptEntry): JsonObject =
            JsonObject(Json.parseToJsonElement(TranscriptCodec.encode(entry)).jsonObject - "record")

        for (entry in goldens.values.flatten()) {
            val recorded = entry.withRecord(record(99))

            assertEquals(record(99), recorded.record)
            assertEquals(entry.javaClass, recorded.javaClass)
            assertEquals(stored(entry), stored(recorded))
        }
        val unknown = goldens.getValue("unknown").first() as UnknownEntry
        assertEquals(unknown.json, unknown.withRecord(record(99)).json)
    }

    @Test
    fun `only the outcome of a call that succeeded is no error`() {
        assertFalse(ToolOutcome.Succeeded.isError)
        assertTrue(
            listOf(ToolOutcome.Failed, ToolOutcome.Cancelled, ToolOutcome.NotRun(NotRunReason.UNKNOWN_TOOL)).all {
                it.isError
            },
        )
    }

    // Provider data.

    @Serializable
    private class Cached(val id: String)

    @Test
    fun `provider data decodes the data of a dialect`() {
        val data = ProviderData.EMPTY.with(anthropic, json("""{"id":"msg_1","extra":true}"""))

        assertEquals("msg_1", data.decode(anthropic, Cached.serializer())?.id)
        assertNull(data.decode(Dialect("deepseek"), Cached.serializer()))
        assertNull(data.with(anthropic, json("""{"other":1}""")).decode(anthropic, Cached.serializer()))
        assertEquals(mapOf(anthropic to json("""{"id":"msg_1","extra":true}""")), data.entries)
    }
}
