package org.foedusprogramme.alexandrite.testkit.provider

import org.foedusprogramme.alexandrite.sdk.model.ModelEvent
import org.foedusprogramme.alexandrite.sdk.model.ModelException
import org.foedusprogramme.alexandrite.sdk.model.ModelRequest
import org.foedusprogramme.alexandrite.sdk.transcript.AssistantPart
import org.foedusprogramme.alexandrite.sdk.transcript.ReasoningPart
import org.foedusprogramme.alexandrite.sdk.transcript.ReasoningSeal
import org.foedusprogramme.alexandrite.sdk.transcript.TextPart
import org.foedusprogramme.alexandrite.sdk.transcript.ToolCallPart
import org.foedusprogramme.alexandrite.testkit.store.expect
import org.foedusprogramme.alexandrite.testkit.store.expectEqual
import org.foedusprogramme.alexandrite.testkit.store.fail

/** What one index of a response streamed before its part completed. */
private class Streamed(val kind: String) {
    val text = StringBuilder()
    var deltas = 0
    val reasoning = StringBuilder()
    var reasoningText = false
    val summary = StringBuilder()
    var summaryText = false
    var seal: ReasoningSeal? = null
    var call: ModelEvent.ToolCallStarted? = null
    val arguments = StringBuilder()
    var part: AssistantPart? = null
}

/**
 * Checks that [events], the response to [request] that ended in [failure] or completed, keep the stream contract of
 * `ModelEndpoint.stream`.
 */
internal fun checkStream(request: ModelRequest, events: List<ModelEvent>, failure: ModelException?) {
    val indexes = sortedMapOf<Int, Streamed>()
    var completed: ModelEvent.Completed? = null
    var usage: ModelEvent.UsageUpdated? = null
    for ((position, event) in events.withIndex()) {
        val at = "Event $position ($event)"
        expect(completed == null) { "$at follows the terminal Completed event." }
        when (event) {
            is ModelEvent.ResponseStarted -> expect(position == 0) { "$at is no first event." }

            is ModelEvent.TextDelta -> {
                val streamed = open(indexes, event.index, "text", at)
                streamed.text.append(event.text)
                streamed.deltas++
            }

            is ModelEvent.ReasoningDelta -> {
                val streamed = open(indexes, event.index, "reasoning", at)
                event.text?.let {
                    streamed.reasoning.append(it)
                    streamed.reasoningText = true
                }
                event.summary?.let {
                    streamed.summary.append(it)
                    streamed.summaryText = true
                }
            }

            is ModelEvent.ReasoningSealed -> {
                val streamed = open(indexes, event.index, "reasoning", at)
                expect(streamed.seal == null) { "$at seals index ${event.index} again." }
                streamed.seal = event.seal
            }

            is ModelEvent.ToolCallStarted -> {
                val streamed = open(indexes, event.index, "call", at)
                expect(streamed.call == null) { "$at starts the call at index ${event.index} again." }
                streamed.call = event
            }

            is ModelEvent.ToolArgumentsDelta -> {
                val streamed = open(indexes, event.index, "call", at)
                expect(streamed.call != null) { "$at streams arguments of a call that has not started." }
                streamed.arguments.append(event.fragment)
            }

            is ModelEvent.PartCompleted -> {
                val streamed = indexes.getOrPut(event.index) { Streamed(kindOf(event.part)) }
                expect(streamed.part == null) { "$at completes index ${event.index} again." }
                expect(streamed.kind == kindOf(event.part)) {
                    "$at completes index ${event.index}, which holds ${streamed.kind}, with ${event.part}."
                }
                streamed.part = event.part
                checkPart(streamed, event.part, at)
            }

            is ModelEvent.UsageUpdated -> usage = event

            is ModelEvent.Completed -> completed = event
        }
    }
    if (failure != null) {
        expect(completed == null) { "The stream completed and threw $failure." }
        expectEqual(events.isNotEmpty(), failure.error.outputStarted, "outputStarted of $failure")
        return
    }
    val terminal = completed ?: fail("The stream ended without a Completed event.")
    val open = indexes.filterValues { it.part == null }.keys
    expect(open.isEmpty()) { "The stream completed with the parts at $open never completed." }
    expectEqual(indexes.keys.toList(), indexes.keys.indices.toList(), "Indexes of the completed parts")
    expectEqual(indexes.values.map { it.part }, terminal.message.parts, "The completed message's parts")
    expectEqual(request.model, terminal.message.producedBy, "The completed message's model")
    usage?.let { expectEqual(it.usage, terminal.usage, "The last usage update against the completed usage") }
    val ids = terminal.message.parts.filterIsInstance<ToolCallPart>().map { it.id }
    expect(ids.toSet().size == ids.size) { "The completed message uses tool call ids $ids more than once." }
}

private fun open(indexes: MutableMap<Int, Streamed>, index: Int, kind: String, at: String): Streamed {
    val streamed = indexes.getOrPut(index) { Streamed(kind) }
    expect(streamed.kind == kind) { "$at streams $kind at index $index, which holds ${streamed.kind}." }
    expect(streamed.part == null) { "$at streams index $index after its part completed." }
    return streamed
}

private fun kindOf(part: AssistantPart): String = when (part) {
    is TextPart -> "text"
    is ReasoningPart -> "reasoning"
    is ToolCallPart -> "call"
    else -> "other"
}

private fun checkPart(streamed: Streamed, part: AssistantPart, at: String) {
    when (part) {
        is TextPart -> if (streamed.deltas > 0) expectEqual(streamed.text.toString(), part.text, "$at: the text deltas")

        is ReasoningPart -> {
            if (streamed.reasoningText) {
                expectEqual(
                    streamed.reasoning.toString(),
                    part.text,
                    "$at: the reasoning deltas",
                )
            }
            if (streamed.summaryText) expectEqual(streamed.summary.toString(), part.summary, "$at: the summary deltas")
            streamed.seal?.let { expectEqual(it, part.seal, "$at: the sealed reasoning") }
        }

        is ToolCallPart -> {
            val started = streamed.call ?: fail("$at completes a tool call that never started.")
            expectEqual(started.id, part.id, "$at: the call's id")
            expectEqual(started.name, part.name, "$at: the call's name")
            expectEqual(streamed.arguments.toString(), part.arguments, "$at: the argument deltas")
        }
    }
}
