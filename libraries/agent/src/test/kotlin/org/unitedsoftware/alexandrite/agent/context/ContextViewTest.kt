package org.unitedsoftware.alexandrite.agent.context

import org.unitedsoftware.alexandrite.sdk.chat.ConversationId
import org.unitedsoftware.alexandrite.sdk.chat.ToolCallId
import org.unitedsoftware.alexandrite.sdk.chat.TurnId
import org.unitedsoftware.alexandrite.sdk.transcript.AssistantEntry
import org.unitedsoftware.alexandrite.sdk.transcript.EntryId
import org.unitedsoftware.alexandrite.sdk.transcript.EntryRecord
import org.unitedsoftware.alexandrite.sdk.transcript.NoticeEntry
import org.unitedsoftware.alexandrite.sdk.transcript.NoticeKind
import org.unitedsoftware.alexandrite.sdk.transcript.SummaryEntry
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolCallPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolOutcome
import org.unitedsoftware.alexandrite.sdk.transcript.ToolResultEntry
import org.unitedsoftware.alexandrite.sdk.transcript.TranscriptEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UserEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UserOrigin
import org.unitedsoftware.alexandrite.testkit.TEST_MODEL
import org.unitedsoftware.alexandrite.testkit.TEST_TIME
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContextViewTest {
    private val long = "A line of output that a tool printed. ".repeat(10)

    private fun record(id: Long, turn: String) = EntryRecord(EntryId(id), ConversationId("c"), TurnId(turn), TEST_TIME)

    private fun user(id: Long, turn: String) =
        UserEntry(record(id, turn), listOf(TextPart("Message $id.")), UserOrigin.SystemWritten("test"))

    private fun calls(id: Long, turn: String, vararg calls: String) =
        AssistantEntry(record(id, turn), calls.map { ToolCallPart(ToolCallId(it), "fs.read", "{}") }, TEST_MODEL)

    private fun answer(id: Long, turn: String) = AssistantEntry(record(id, turn), listOf(TextPart("Done.")), TEST_MODEL)

    private fun result(id: Long, turn: String, call: String, text: String = long) =
        ToolResultEntry(record(id, turn), ToolCallId(call), "fs.read", listOf(TextPart(text)), ToolOutcome.Succeeded)

    private fun ids(entries: List<TranscriptEntry>): List<Long> = entries.map { it.record!!.id.value }

    /** Three turns: one round of two calls, then two plain answers. */
    private val turns = listOf(
        user(1, "t1"),
        calls(2, "t1", "a", "b"),
        result(3, "t1", "a"),
        result(4, "t1", "b"),
        answer(5, "t1"),
        user(6, "t2"),
        answer(7, "t2"),
        user(8, "t3"),
        answer(9, "t3"),
    )

    // The view.

    @Test
    fun `the view starts at the newest summary and holds every entry stored after what it covers`() {
        val older = SummaryEntry(record(10, "t4"), "Turn one.", EntryId(5))
        val newer = SummaryEntry(record(12, "t5"), "Turns one and two.", EntryId(7))
        val notice = NoticeEntry(record(11, "t4"), "A notice.", NoticeKind.FAILED)

        val view = contextView(turns + older + notice + newer)

        assertEquals(newer, view.summary)
        assertEquals(listOf(8L, 9L, 11L), ids(view.entries))
        assertEquals(listOf(12L, 8L, 9L, 11L), ids(view.loaded))
        assertEquals(turns, contextView(turns).entries)
        assertNull(contextView(turns).summary)
        assertEquals(listOf(false, true), listOf(notice, view.entries.first()).map(::isSent))
    }

    // Folds.

    @Test
    fun `a compaction may fold everything before the newest turns it keeps`() {
        assertEquals(listOf(9, 7, 5, 0), listOf(0, 1, 2, 3).map { foldable(turns, it) })
    }

    @Test
    fun `a fold never ends between a call and its results, and goes as far as it may when it needs more`() {
        val one = { _: TranscriptEntry -> 1.0 }

        assertEquals(4, cut(turns, 5, 2.0, one))
        assertEquals(1, cut(turns, 5, 1.0, one))
        assertEquals(5, cut(turns, 5, Double.POSITIVE_INFINITY, one))
        assertEquals(1, cut(turns, 3, Double.POSITIVE_INFINITY, one))
        assertEquals(0, cut(turns, 0, 1.0, one))
    }

    @Test
    fun `entries that take more tokens per estimated token make a fold of the same need shorter`() {
        val entries = (1L..20L).map { user(it, "t$it") }

        val plain = fold(null, entries, emptySet(), Keep(2), 1.0, 1000, 150)
        val dense = fold(null, entries, emptySet(), Keep(2), 2.0, 1000, 150)

        assertTrue(plain!!.entries.size in 2..18, "plain folds ${plain.entries.size}")
        assertTrue(dense!!.entries.size < plain.entries.size, "dense folds ${dense.entries.size}")
        assertTrue(dense.shed >= 150 && plain.shed >= 150)
        assertNull(fold(null, entries.take(2), emptySet(), Keep(2), 1.0, 1000, 150))
    }

    @Test
    fun `a fold sheds the previous summary too and counts cleared results as they read`() {
        val summary = SummaryEntry(record(20, "t0"), "Earlier work. ".repeat(50), EntryId(1))
        val cleared = setOf(EntryId(3), EntryId(4))

        val all = fold(null, turns, emptySet(), Keep(0), 1.0, 1000)!!
        val shrunk = fold(null, turns, cleared, Keep(0), 1.0, 1000)!!
        val withSummary = fold(summary, turns, emptySet(), Keep(0), 1.0, 1000)!!

        assertEquals(turns, all.entries)
        assertTrue(shrunk.shed < all.shed)
        assertEquals(all.shed + all.cost(summaryEntry(summary)), withSummary.shed, 0.001)
        assertEquals(summary, withSummary.previous)
    }

    /** A turn, then a running one: its tools notice and message, then rounds a and b, a follow-up, c and d. */
    private val running = listOf(
        user(1, "t1"),
        answer(2, "t1"),
        user(3, "t2"),
        user(4, "t2"),
        calls(5, "t2", "a"),
        result(6, "t2", "a"),
        calls(7, "t2", "b"),
        result(8, "t2", "b"),
        user(9, "t2"),
        calls(10, "t2", "c"),
        result(11, "t2", "c"),
        calls(12, "t2", "d"),
        result(13, "t2", "d"),
    )

    @Test
    fun `a fold reaches the running turn's older rounds last, keeping its opening, its newest rounds and kept turns`() {
        val keep = Keep(1, TurnId("t2"), 2)

        val all = fold(null, running, emptySet(), keep, 1.0, 1000)!!
        val few = fold(null, running, emptySet(), keep, 1.0, 1000, 1)!!
        val older = fold(null, running, emptySet(), Keep(0, TurnId("t2"), 2), 1.0, 1000)!!

        assertEquals(listOf(5L, 6L, 7L, 8L, 9L), ids(all.entries))
        assertEquals(listOf(EntryId(9), listOf(1L, 2L, 3L, 4L)), listOf(all.through, all.kept.map { it.value }))
        assertEquals(listOf(5L, 6L), ids(few.entries))
        assertEquals(EntryId(6), few.through)
        assertEquals(listOf(1L, 2L, 5L, 6L, 7L, 8L, 9L), ids(older.entries))
        assertEquals(listOf(3L, 4L), older.kept.map { it.value })
        val summary = SummaryEntry(record(14, "t2"), "Rounds a and b.", all.through, all.kept)
        assertEquals(listOf(1L, 2L, 3L, 4L, 10L, 11L, 12L, 13L), ids(contextView(running + summary).entries))
    }

    @Test
    fun `a running turn keeps its newest round whatever it is told, and folds nothing from fewer rounds`() {
        val last = fold(null, running, emptySet(), Keep(1, TurnId("t2"), 0), 1.0, 1000)!!

        assertEquals(listOf(5L, 6L, 7L, 8L, 9L, 10L, 11L), ids(last.entries))
        assertNull(fold(null, running, emptySet(), Keep(1, TurnId("t2"), 4), 1.0, 1000))
    }

    @Test
    fun `a later fold covers what the earlier summary kept once it folds past it`() {
        val first = SummaryEntry(record(14, "t2"), "Rounds a and b.", EntryId(9), (1L..4L).map(::EntryId))
        val visible = contextView(running + first).entries

        val next = fold(first, visible, emptySet(), Keep(0, TurnId("t2"), 1), 1.0, 1000)!!

        assertEquals(listOf(1L, 2L, 10L, 11L), ids(next.entries))
        assertEquals(EntryId(11), next.through)
        assertEquals(listOf(3L, 4L), next.kept.map { it.value })
    }

    // Clearing.

    @Test
    fun `a clearing may clear the long results of the rounds before the newest it keeps`() {
        val entries = listOf(
            user(1, "t1"),
            calls(2, "t1", "a"),
            result(3, "t1", "a"),
            calls(4, "t1", "b", "c"),
            result(5, "t1", "b"),
            result(6, "t1", "c", "ok"),
            calls(7, "t1", "d"),
            result(8, "t1", "d"),
            answer(9, "t1"),
        )

        assertEquals(listOf(3L), ids(clearable(entries, 2, emptySet())))
        assertEquals(listOf(3L, 5L), ids(clearable(entries, 1, emptySet())))
        assertEquals(listOf(3L, 5L, 8L), ids(clearable(entries, 0, emptySet())))
        assertEquals(listOf(5L, 8L), ids(clearable(entries, 0, setOf(EntryId(3)))))
        assertEquals(emptyList(), clearable(entries, 5, emptySet()))
    }

    @Test
    fun `a cleared result keeps its call and outcome and says how much it held`() {
        val entries = listOf(calls(1, "t1", "a"), result(2, "t1", "a"), result(3, "t1", "a", "ok"))

        val read = withCleared(entries, setOf(EntryId(2)))

        assertEquals(listOf(entries[0], entries[2]), listOf(read[0], read[2]))
        val cleared = read[1] as ToolResultEntry
        assertEquals(
            listOf(ToolCallId("a"), "fs.read", ToolOutcome.Succeeded),
            listOf(cleared.callId, cleared.toolName, cleared.outcome),
        )
        val text = "[alexandrite:cleared] fs.read: 380 characters cleared; call it again if you need them"
        assertEquals(listOf(TextPart(text)), cleared.content)
        assertEquals(entries[1].record, cleared.record)
    }

    // Density.

    @Test
    fun `a measure sets the density of the estimate, clamped, unless it does not fit the window`() {
        val measured = ContextRecord(promptTokens = 950, estimatedTokens = 475)

        assertEquals(2.0, measured.density(1000))
        assertEquals(2.0, measured.density(null))
        assertEquals(0.5, ContextRecord(promptTokens = 10, estimatedTokens = 1000).density(1000))
        assertEquals(3.0, ContextRecord(promptTokens = 900, estimatedTokens = 100).density(1000))
        assertNull(ContextRecord(promptTokens = 50_000, estimatedTokens = 800).density(1000))
        assertNull(ContextRecord(promptTokens = 950_000, estimatedTokens = 0).density(1_000_000))
        assertNull(ContextRecord(estimatedTokens = 800).density(1000))
        assertNull(ContextRecord().density(1000))
    }
}
