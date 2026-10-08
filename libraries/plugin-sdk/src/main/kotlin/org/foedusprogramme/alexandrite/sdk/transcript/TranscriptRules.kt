package org.foedusprogramme.alexandrite.sdk.transcript

import org.foedusprogramme.alexandrite.sdk.chat.ToolCallId

/**
 * The rules that the entries a model sees keep: each tool call is answered by exactly one tool result, and the results
 * of an assistant entry's calls come right after it, where a user entry may follow them.
 */
public object TranscriptRules {
    /** The rules that [entries] break, empty when they keep them all. */
    public fun check(entries: List<TranscriptEntry>): List<String> {
        val problems = mutableListOf<String>()
        val calls = mutableMapOf<ToolCallId, Call>()
        val answered = mutableSetOf<ToolCallId>()
        val open = linkedSetOf<ToolCallId>()
        var round = -1
        for ((index, entry) in entries.withIndex()) {
            if (entry is NoticeEntry || entry is UnknownEntry) continue
            if (entry is ToolResultEntry) {
                val id = entry.callId
                val call = calls[id]
                when {
                    call == null -> problems += "Entry $index answers tool call '$id', which no earlier entry made."

                    id in answered -> problems += "Entry $index answers tool call '$id' again."

                    id !in open -> problems += "Entry $index answers tool call '$id' of entry ${call.entry} too late."

                    call.part.name != entry.toolName ->
                        problems += "Entry $index names tool '${entry.toolName}' for tool call '$id' of " +
                            "'${call.part.name}'."
                }
                answered += id
                open -= id
                continue
            }
            if (open.isNotEmpty()) {
                problems += "Entry $index comes before the results of entry $round's tool calls ${quoted(open)}."
                open.clear()
            }
            if (entry is AssistantEntry) {
                round = index
                for (part in entry.parts.filterIsInstance<ToolCallPart>()) {
                    if (calls.putIfAbsent(part.id, Call(index, part)) == null) {
                        open += part.id
                    } else {
                        problems += "Entry $index uses tool call id '${part.id}' again."
                    }
                }
            }
        }
        if (open.isNotEmpty()) problems += "The tool calls ${quoted(open)} of entry $round are not answered."
        return problems
    }

    private class Call(val entry: Int, val part: ToolCallPart)

    private fun quoted(ids: Set<ToolCallId>): String = ids.joinToString { "'$it'" }
}
