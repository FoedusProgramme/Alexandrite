package org.foedusprogramme.alexandrite.agent.context

import org.foedusprogramme.alexandrite.sdk.transcript.AssistantEntry
import org.foedusprogramme.alexandrite.sdk.transcript.EntryId
import org.foedusprogramme.alexandrite.sdk.transcript.NoticeEntry
import org.foedusprogramme.alexandrite.sdk.transcript.SummaryEntry
import org.foedusprogramme.alexandrite.sdk.transcript.TextPart
import org.foedusprogramme.alexandrite.sdk.transcript.ToolCallPart
import org.foedusprogramme.alexandrite.sdk.transcript.ToolResultEntry
import org.foedusprogramme.alexandrite.sdk.transcript.TranscriptEntry
import org.foedusprogramme.alexandrite.sdk.transcript.UnknownEntry

/** What a conversation's requests read of its stored entries: its newest rolling summary and those it does not cover. */
internal class ContextView(val summary: SummaryEntry?, val entries: List<TranscriptEntry>) {
    /** The view as `context.loaded` shows it, the summary first. */
    val loaded: List<TranscriptEntry> get() = listOfNotNull(summary) + entries
}

/** The view of [stored], a conversation's entries in the order they were stored. */
internal fun contextView(stored: List<TranscriptEntry>): ContextView {
    val summary = stored.lastOrNull { it is SummaryEntry } as SummaryEntry?
    val read = stored.filter { entry ->
        val id = entry.record?.id
        entry !is SummaryEntry && (id == null || summary?.covers(id) != true)
    }
    return ContextView(summary, read)
}

/** Whether models read [entry] where it was stored. */
internal fun isSent(entry: TranscriptEntry): Boolean =
    entry !is NoticeEntry && entry !is UnknownEntry && entry !is SummaryEntry

/** How many of the oldest of [entries] a compaction may fold: those before the newest [keepTurns] turns. */
internal fun foldable(entries: List<TranscriptEntry>, keepTurns: Int): Int {
    val kept = entries.mapNotNull { it.record?.turn }.distinct().takeLast(keepTurns).toSet()
    val first = entries.indexOfFirst { it.record?.turn in kept }
    return if (first < 0) entries.size else first
}

/**
 * How many of the oldest of [entries], at most [limit], shed [needed] by [tokens], ending only before an entry that
 * [endsBefore] allows: the fewest that do, else the most that may be folded.
 */
internal fun cut(
    entries: List<TranscriptEntry>,
    limit: Int,
    needed: Double,
    tokens: (TranscriptEntry) -> Double,
    endsBefore: (next: TranscriptEntry?) -> Boolean = { it !is ToolResultEntry },
): Int {
    var shed = 0.0
    var cut = 0
    for (count in 1..limit) {
        shed += tokens(entries[count - 1])
        if (!endsBefore(entries.getOrNull(count))) continue
        cut = count
        if (shed >= needed) break
    }
    return cut
}

/** What the model reads of [result] once its content is cleared. */
internal fun clearedResult(result: ToolResultEntry): ToolResultEntry {
    val characters = result.content.sumOf { (it as? TextPart)?.text?.length ?: 0 }
    val text = "$CLEARED_TAG ${result.toolName}: $characters characters cleared; call it again if you need them"
    return ToolResultEntry(result.record, result.callId, result.toolName, listOf(TextPart(text)), result.outcome)
}

/**
 * The stored results among [entries] that a clearing may clear: those before the newest [keepRounds] tool rounds,
 * not in [cleared], that take more than they would cleared.
 */
internal fun clearable(entries: List<TranscriptEntry>, keepRounds: Int, cleared: Set<EntryId>): List<ToolResultEntry> {
    val rounds = entries.indices.filter { index ->
        (entries[index] as? AssistantEntry)?.parts?.any { it is ToolCallPart } == true
    }
    val keptFrom = if (keepRounds == 0) entries.size else rounds.takeLast(keepRounds).firstOrNull() ?: 0
    return entries.take(keptFrom).filterIsInstance<ToolResultEntry>().filter { result ->
        val id = result.record?.id
        id != null && id !in cleared && TokenEstimator.entry(result) > TokenEstimator.entry(clearedResult(result))
    }
}

/** [entries] with the results [cleared] cleared. */
internal fun withCleared(entries: List<TranscriptEntry>, cleared: Set<EntryId>): List<TranscriptEntry> {
    if (cleared.isEmpty()) return entries
    return entries.map { entry ->
        if (entry is ToolResultEntry && entry.record?.id in cleared) clearedResult(entry) else entry
    }
}

/** The tag that starts what the model reads of a cleared result. */
internal const val CLEARED_TAG: String = "[alexandrite:cleared]"
