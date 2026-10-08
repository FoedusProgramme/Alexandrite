package org.foedusprogramme.alexandrite.sdk.transcript

import kotlin.test.Test
import kotlin.test.assertEquals

class TranscriptRulesTest {
    private fun check(vararg entries: TranscriptEntry): List<String> = TranscriptRules.check(entries.toList())

    @Test
    fun `results may come in any order right after their calls`() {
        assertEquals(
            emptyList(),
            check(
                user("Save two notes"),
                reply(call("a"), call("b")),
                result("b"),
                result("a"),
                reply(TextPart("Done.")),
            ),
        )
    }

    @Test
    fun `a user entry may follow a round's results`() {
        assertEquals(
            emptyList(),
            check(user("Save it"), reply(call("a")), result("a"), user("Actually, two"), reply(call("b")), result("b")),
        )
    }

    @Test
    fun `notices and unknown entries are skipped`() {
        val notice = NoticeEntry(null, "Still working.", NoticeKind.FAILED)
        val unknown = UnknownEntry(null, "poll", json("""{"type":"poll"}"""))

        assertEquals(emptyList(), check(reply(call("a")), notice, unknown, result("a")))
    }

    @Test
    fun `a call without a result is reported`() {
        assertEquals(
            listOf("The tool calls 'a', 'b' of entry 1 are not answered."),
            check(user("Go"), reply(call("a"), call("b"))),
        )
    }

    @Test
    fun `a result after another entry is reported`() {
        assertEquals(
            listOf(
                "Entry 2 comes before the results of entry 0's tool calls 'b'.",
                "Entry 3 answers tool call 'b' of entry 0 too late.",
            ),
            check(reply(call("a"), call("b")), result("a"), user("Wait"), result("b")),
        )
    }

    @Test
    fun `a call answered twice or never made is reported`() {
        assertEquals(
            listOf(
                "Entry 0 answers tool call 'x', which no earlier entry made.",
                "Entry 3 answers tool call 'a' again.",
            ),
            check(result("x"), reply(call("a")), result("a"), result("a")),
        )
    }

    @Test
    fun `a call id used twice is reported`() {
        assertEquals(
            listOf("Entry 2 uses tool call id 'a' again.", "Entry 3 answers tool call 'a' again."),
            check(reply(call("a")), result("a"), reply(call("a")), result("a")),
        )
    }

    @Test
    fun `a result that names another tool is reported`() {
        assertEquals(
            listOf("Entry 1 names tool 'notes.list' for tool call 'a' of 'notes.add'."),
            check(reply(call("a")), result("a", name = "notes.list")),
        )
    }
}
