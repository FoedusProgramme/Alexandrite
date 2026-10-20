package org.unitedsoftware.alexandrite.agent.context

import org.unitedsoftware.alexandrite.sdk.model.ModelRequest
import org.unitedsoftware.alexandrite.sdk.transcript.AssistantEntry
import org.unitedsoftware.alexandrite.sdk.transcript.ContextPart
import org.unitedsoftware.alexandrite.sdk.transcript.MediaPart
import org.unitedsoftware.alexandrite.sdk.transcript.OpaquePart
import org.unitedsoftware.alexandrite.sdk.transcript.Part
import org.unitedsoftware.alexandrite.sdk.transcript.ReasoningPart
import org.unitedsoftware.alexandrite.sdk.transcript.SummaryEntry
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolCallPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolResultEntry
import org.unitedsoftware.alexandrite.sdk.transcript.TranscriptEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UserEntry
import kotlin.math.ceil

/** Guesses how many tokens text takes, erring high. */
internal object TokenEstimator {
    /** The tokens of [text], by its CJK ideographs, its ASCII and its other characters. */
    fun estimate(text: String): Int {
        if (text.isBlank()) return 0
        val cjk = text.count { Character.isIdeographic(it.code) }
        val nonAscii = text.count { it.code > ASCII_MAX }
        val ascii = text.length - nonAscii
        val other = nonAscii - cjk
        val tokens = ceil(ascii / ASCII_CHARS_PER_TOKEN) + ceil(cjk / CJK_CHARS_PER_TOKEN) +
            ceil(other / OTHER_CHARS_PER_TOKEN)
        return tokens.toInt().coerceAtLeast(1)
    }

    /** [tokens] with the margin that a budget keeps for the estimate's error. */
    fun forBudget(tokens: Long): Long = ceil(tokens * BUDGET_MARGIN).toLong()

    /** The tokens of [entry] as one message of a request. */
    fun entry(entry: TranscriptEntry): Int {
        val content = when (entry) {
            is UserEntry -> entry.parts.sumOf(::part)
            is AssistantEntry -> entry.parts.sumOf(::part)
            is ToolResultEntry -> estimate(entry.toolName) + entry.content.sumOf(::part)
            is SummaryEntry -> estimate(entry.text)
            else -> 0
        }
        return content.coerceAtLeast(1) + MESSAGE_OVERHEAD
    }

    /** The tokens of the prompt of [request], with the margin of a budget. */
    fun request(request: ModelRequest): Long {
        val instructions = request.instructions.sumOf { estimate(it.text).toLong() }
        val tools = request.tools.sumOf { tool ->
            estimate(tool.name) + estimate(tool.description) + estimate(tool.parameters.toString()) + MESSAGE_OVERHEAD
        }
        val context = request.turnContext.sumOf { estimate(it.text).toLong() }
        val history = request.history.sumOf { entry(it).toLong() }
        return forBudget(instructions + tools + context + history + MESSAGE_OVERHEAD)
    }

    private fun part(part: Part): Int = when (part) {
        is TextPart -> estimate(part.text)
        is ContextPart -> estimate(part.text)
        is MediaPart -> MEDIA_TOKENS
        is ToolCallPart -> estimate(part.name) + estimate(part.arguments)
        is ReasoningPart -> estimate(part.text ?: part.summary.orEmpty())
        is OpaquePart -> estimate(part.json.toString())
        else -> 0
    }

    private const val ASCII_MAX = 0x7F
    private const val CJK_CHARS_PER_TOKEN = 1.6
    private const val ASCII_CHARS_PER_TOKEN = 4.0
    private const val OTHER_CHARS_PER_TOKEN = 2.0
    private const val BUDGET_MARGIN = 1.25

    /** What each message of a request adds beside its content. */
    const val MESSAGE_OVERHEAD = 8

    /** What an image or other medium is counted as. */
    const val MEDIA_TOKENS = 512
}
