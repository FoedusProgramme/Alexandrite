package org.foedusprogramme.alexandrite.sdk.transcript

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import org.foedusprogramme.alexandrite.sdk.chat.ToolCallId
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class TranscriptCodecTest {
    private val pretty = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    private fun golden(name: String): String =
        checkNotNull(javaClass.getResource("/transcript/$name.json")) { "No golden file $name.json" }.readText()

    // Golden files.

    @Test
    fun `every entry encodes to its golden JSON`() {
        for ((name, entries) in goldens) {
            val encoded = JsonArray(entries.map { Json.parseToJsonElement(TranscriptCodec.encode(it)) })

            assertEquals(golden(name), pretty.encodeToString(encoded) + "\n", "$name.json")
        }
    }

    @Test
    fun `every golden JSON decodes to its entry and encodes back to the same bytes`() {
        for ((name, entries) in goldens) {
            val stored = Json.parseToJsonElement(golden(name)).jsonArray.map { it.toString() }

            assertEquals(entries, stored.map(TranscriptCodec::decode), "$name.json")
            assertEquals(stored, stored.map { TranscriptCodec.encode(TranscriptCodec.decode(it)) }, "$name.json")
        }
    }

    // Unknown types.

    @Test
    fun `types this version does not know decode to their unknown forms with the JSON as stored`() {
        val entry = TranscriptCodec.decode("""{"type":"vote","record":{"id":3},"options":["a","b"]}""")
        val user = TranscriptCodec.decode(
            """{"type":"user","parts":[{"type":"text","text":"hi"},{"type":"poll","q":"?"}],""" +
                """"origin":{"type":"from_agent","agent":"helper"},"addedLater":true}""",
        )
        val result = TranscriptCodec.decode(
            """{"type":"tool_result","callId":"c","toolName":"files.read","content":[{"type":"media",""" +
                """"kind":"image","mediaType":"image/png","source":{"type":"url","url":"u"}}],"outcome":{"type":"x"}}""",
        )

        assertEquals(
            UnknownEntry(null, "vote", json("""{"type":"vote","record":{"id":3},"options":["a","b"]}""")),
            entry,
        )
        assertIs<UserEntry>(user)
        assertEquals(listOf(TextPart("hi"), UnknownPart("poll", json("""{"type":"poll","q":"?"}"""))), user.parts)
        assertEquals(UserOrigin.Unknown("from_agent", json("""{"type":"from_agent","agent":"helper"}""")), user.origin)
        assertIs<ToolResultEntry>(result)
        assertEquals(
            UnknownMedia("url", json("""{"type":"url","url":"u"}""")),
            (result.content.single() as MediaPart).source,
        )
        assertEquals(ToolOutcome.Unknown("x", json("""{"type":"x"}""")), result.outcome)
        assertEquals(true, result.outcome.isError)
    }

    @Test
    fun `a part that a role does not take decodes to an unknown part`() {
        val entry = TranscriptCodec.decode(
            """{"type":"user","parts":[{"type":"reasoning","text":"t"}],"origin":{"type":"initiated",""" +
                """"plugin":"clock","kind":"reminder"}}""",
        )

        assertEquals(
            listOf(UnknownPart("reasoning", json("""{"type":"reasoning","text":"t"}"""))),
            (entry as UserEntry).parts,
        )
    }

    @Test
    fun `an unknown entry is encoded with the record it has`() {
        val stored =
            """{"type":"vote","record":{"id":3,"conversation":"c1","turn":"t1","createdAt":"2026-10-07T08:30:00Z"},""" +
                """"x":1}"""
        val unknown = TranscriptCodec.decode(stored) as UnknownEntry

        assertEquals(record(3), unknown.record)
        assertEquals(stored, TranscriptCodec.encode(unknown))
        assertEquals(
            """{"type":"vote","x":1}""",
            TranscriptCodec.encode(UnknownEntry(null, unknown.type, unknown.json)),
        )
        assertEquals(
            stored.replace("\"id\":3", "\"id\":4"),
            TranscriptCodec.encode(UnknownEntry(record(4), unknown.type, unknown.json)),
        )
    }

    // What is refused.

    @Test
    fun `inline media are refused`() {
        val media = MediaPart(MediaKind.IMAGE, "image/png", InlineMedia(byteArrayOf(1, 2)))
        val entries = listOf(
            UserEntry(null, listOf(media), user("x").origin),
            ToolResultEntry(null, ToolCallId("c"), "files.read", listOf(media), ToolOutcome.Succeeded),
        )

        for (entry in entries) {
            val error = assertFailsWith<IllegalArgumentException> { TranscriptCodec.encode(entry) }
            assertContains(error.message!!, "Inline media cannot be persisted")
        }
    }

    @Test
    fun `malformed JSON is refused`() {
        val texts = listOf(
            "[]",
            """{"text":"no type"}""",
            """{"type":"","text":"blank type"}""",
            """{"type":"summary","text":"no through"}""",
            """{"type":"summary","text":"s","through":0}""",
            """{"type":"user","parts":[],"origin":{"type":"initiated","plugin":"clock","kind":"reminder"}}""",
            """{"type":"assistant","parts":[],"producedBy":"no-slash"}""",
        )

        for (text in texts) assertFailsWith<IllegalArgumentException>(text) { TranscriptCodec.decode(text) }
        assertIs<SerializationException>(assertFailsWith<IllegalArgumentException> { TranscriptCodec.decode("[]") })
    }

    // Seals.

    @Test
    fun `a reasoning seal is kept byte for byte`() {
        val data = "\"\\\n\t\u0000\u001f é😺 ${"Zm9v".repeat(500)}=="
        val entry = reply(ReasoningPart("r", null, ReasoningSeal(claude, anthropic, SealKind.REDACTED, data)))

        val decoded = TranscriptCodec.decode(TranscriptCodec.encode(entry)) as AssistantEntry

        val seal = (decoded.parts.single() as ReasoningPart).seal!!
        assertEquals(data, seal.data)
        assertEquals(data.encodeToByteArray().toList(), seal.data.encodeToByteArray().toList())
    }
}
