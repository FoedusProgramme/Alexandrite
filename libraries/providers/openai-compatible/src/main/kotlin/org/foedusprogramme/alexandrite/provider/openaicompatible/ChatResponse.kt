package org.foedusprogramme.alexandrite.provider.openaicompatible

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.foedusprogramme.alexandrite.sdk.chat.ToolCallId
import org.foedusprogramme.alexandrite.sdk.model.FinishKind
import org.foedusprogramme.alexandrite.sdk.model.FinishReason
import org.foedusprogramme.alexandrite.sdk.model.ModelErrorKind
import org.foedusprogramme.alexandrite.sdk.model.ModelEvent
import org.foedusprogramme.alexandrite.sdk.model.ModelRequest
import org.foedusprogramme.alexandrite.sdk.model.Usage
import org.foedusprogramme.alexandrite.sdk.model.Warning
import org.foedusprogramme.alexandrite.sdk.transcript.AssistantEntry
import org.foedusprogramme.alexandrite.sdk.transcript.AssistantPart
import org.foedusprogramme.alexandrite.sdk.transcript.ReasoningPart
import org.foedusprogramme.alexandrite.sdk.transcript.TextPart
import org.foedusprogramme.alexandrite.sdk.transcript.ToolCallPart

@Serializable
internal class ChunkWire(
    val id: String? = null,
    val model: String? = null,
    val choices: List<ChoiceWire> = emptyList(),
    val usage: JsonObject? = null,
    val error: JsonElement? = null,
)

@Serializable
internal class ChoiceWire(
    val index: Int = 0,
    val delta: DeltaWire? = null,
    /** The whole message of a response that is not streamed. */
    val message: DeltaWire? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
internal class DeltaWire(
    val content: JsonElement? = null,
    @SerialName("reasoning_content") val reasoningContent: String? = null,
    val reasoning: JsonElement? = null,
    @SerialName("reasoning_details") val reasoningDetails: List<JsonObject>? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCallWire>? = null,
)

@Serializable
internal class ToolCallWire(val index: Int? = null, val id: String? = null, val function: FunctionWire? = null)

@Serializable
internal class FunctionWire(val name: String? = null, val arguments: String? = null)

/** Turns the chunks of one response to [request] into the events of the stream contract. */
internal class ChatResponse(
    private val request: ModelRequest,
    private val profile: Profile,
    private val warnings: List<Warning>,
) {
    private val parts = mutableListOf<PartBuilder>()
    private var current: PartBuilder? = null
    private val calls = mutableMapOf<Int, CallBuilder>()
    private var responseId: String? = null
    private var model: String? = null
    private var finish: String? = null
    private var usage: Usage? = null

    /** Whether any event was made. */
    var started: Boolean = false
        private set

    fun chunk(chunk: ChunkWire): List<ModelEvent> {
        responseId = responseId ?: chunk.id
        model = model ?: chunk.model
        chunk.error?.let { throw streamError(it, started) }
        val events = mutableListOf<ModelEvent>()
        chunk.choices.firstOrNull { it.index == 0 }?.let { choice ->
            (choice.delta ?: choice.message)?.let { delta(it, events) }
            choice.finishReason?.takeIf { it.isNotEmpty() }?.let { raw ->
                if (raw == "error") throw failure(ModelErrorKind.SERVER_ERROR, "The response broke off.")
                finish = raw
            }
        }
        chunk.usage?.let { raw ->
            val reported = usage(raw)
            usage = reported
            events += ModelEvent.UsageUpdated(reported)
        }
        return started(events)
    }

    /** The events that complete the response. */
    fun finish(): List<ModelEvent> {
        val raw = finish ?: throw failure(ModelErrorKind.PROTOCOL, "The response ended without a finish reason.")
        profile.failure(raw)?.let { throw failure(it, "The response ended for the reason '$raw'.") }
        val events = mutableListOf<ModelEvent>()
        close(events)
        for (call in calls.values.sortedBy { it.index }) {
            if (call.name == null) throw failure(ModelErrorKind.PROTOCOL, "Tool call ${call.index} names no tool.")
            events += call.complete()
        }
        val message = AssistantEntry(null, parts.map { it.part!! }, request.model)
        val reason = FinishReason(finishKind(raw), raw, null)
        events += ModelEvent.Completed(message, reason, usage ?: Usage.builder().build())
        return started(events)
    }

    private fun failure(kind: ModelErrorKind, message: String) = modelException(kind, message, started)

    private fun started(events: MutableList<ModelEvent>): List<ModelEvent> {
        if (events.isEmpty() || started) return events
        started = true
        return listOf(ModelEvent.ResponseStarted(responseId, model, warnings)) + events
    }

    private fun delta(delta: DeltaWire, events: MutableList<ModelEvent>) {
        val reasoning = delta.reasoningContent ?: delta.reasoning.text()
        val details = delta.reasoningDetails.orEmpty()
        if (!reasoning.isNullOrEmpty() || details.isNotEmpty()) {
            val builder = current as? ReasoningBuilder ?: open(ReasoningBuilder(parts.size), events)
            builder.add(reasoning, details, events)
        }
        delta.content.text()?.takeIf { it.isNotEmpty() }?.let { text ->
            val builder = current as? TextBuilder ?: open(TextBuilder(parts.size), events)
            builder.text.append(text)
            events += ModelEvent.TextDelta(builder.index, text)
        }
        delta.toolCalls?.forEachIndexed { position, call -> call(call, position, events) }
    }

    private fun call(wire: ToolCallWire, position: Int, events: MutableList<ModelEvent>) {
        val key = wire.index ?: position
        val call = calls.getOrPut(key) {
            close(events)
            CallBuilder(parts.size).also { parts += it }
        }
        if (call.id == null) wire.id?.takeIf { it.isNotEmpty() }?.let { call.id = it }
        if (call.name == null) {
            wire.function?.name?.takeIf { it.isNotEmpty() }?.let { call.name = profile.toolName(it, request.tools) }
        }
        val fragment = wire.function?.arguments.orEmpty()
        call.arguments.append(fragment)
        val name = call.name ?: return
        if (!call.started) {
            call.started = true
            val id = call.id ?: request.ids.callId(call.index).value
            call.id = id
            events += ModelEvent.ToolCallStarted(call.index, ToolCallId(id), name)
            if (call.arguments.isNotEmpty()) {
                events +=
                    ModelEvent.ToolArgumentsDelta(call.index, call.arguments.toString())
            }
        } else if (fragment.isNotEmpty()) {
            events += ModelEvent.ToolArgumentsDelta(call.index, fragment)
        }
    }

    private fun <T : PartBuilder> open(builder: T, events: MutableList<ModelEvent>): T {
        close(events)
        parts += builder
        current = builder
        return builder
    }

    private fun close(events: MutableList<ModelEvent>) {
        current?.let { events += it.complete() }
        current = null
    }

    private fun finishKind(raw: String): FinishKind = when (raw) {
        "stop" -> FinishKind.END_TURN
        "length" -> FinishKind.MAX_OUTPUT_TOKENS
        "tool_calls", "function_call" -> FinishKind.TOOL_USE
        "content_filter" -> FinishKind.REFUSAL
        else -> FinishKind.OTHER
    }

    private abstract class PartBuilder(val index: Int) {
        var part: AssistantPart? = null

        abstract fun build(): Pair<List<ModelEvent>, AssistantPart>

        fun complete(): List<ModelEvent> {
            val (events, built) = build()
            part = built
            return events + ModelEvent.PartCompleted(index, built)
        }
    }

    private class TextBuilder(index: Int) : PartBuilder(index) {
        val text = StringBuilder()

        override fun build(): Pair<List<ModelEvent>, AssistantPart> =
            emptyList<ModelEvent>() to TextPart(text.toString())
    }

    private inner class ReasoningBuilder(index: Int) : PartBuilder(index) {
        private val text = StringBuilder()
        private val summary = StringBuilder()
        private val details = mutableListOf<JsonObject>()

        fun add(reasoning: String?, added: List<JsonObject>, events: MutableList<ModelEvent>) {
            added.forEach(::merge)
            val shown = reasoning?.ifEmpty { null } ?: joined(added, "text")
            val summarized = joined(added, "summary")
            shown?.let { text.append(it) }
            summarized?.let { summary.append(it) }
            if (shown != null || summarized != null) events += ModelEvent.ReasoningDelta(index, shown, summarized)
        }

        override fun build(): Pair<List<ModelEvent>, AssistantPart> {
            val seal = profile.seal(text.toString(), details, request.model)
            val part = ReasoningPart(text.toString().ifEmpty { null }, summary.toString().ifEmpty { null }, seal)
            return listOfNotNull(seal?.let { ModelEvent.ReasoningSealed(index, it) }) to part
        }

        private fun joined(details: List<JsonObject>, field: String): String? =
            details.mapNotNull { it.string(field) }.joinToString("").ifEmpty { null }

        /** Adds [detail] to the block of the same index and type, or as a block of its own. */
        private fun merge(detail: JsonObject) {
            val last = details.lastOrNull()
            if (last?.get("index") == null || last["index"] != detail["index"] || last["type"] != detail["type"]) {
                details += detail
                return
            }
            val merged = last.toMutableMap()
            for ((name, value) in detail) {
                val before = merged[name].text()
                merged[name] = when {
                    value is JsonNull -> merged[name] ?: value

                    name in CONCATENATED && before != null && value.text() != null -> JsonPrimitive(
                        before + value.text(),
                    )

                    else -> value
                }
            }
            details[details.lastIndex] = JsonObject(merged)
        }
    }

    private class CallBuilder(index: Int) : PartBuilder(index) {
        var id: String? = null
        var name: String? = null
        var started = false
        val arguments = StringBuilder()

        override fun build(): Pair<List<ModelEvent>, AssistantPart> =
            emptyList<ModelEvent>() to ToolCallPart(ToolCallId(id!!), name!!, arguments.toString())
    }

    private companion object {
        /** The fields of a reasoning detail that its stream splits into fragments. */
        val CONCATENATED = setOf("text", "summary", "data")
    }
}

private fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

/** The usage that a Chat Completions usage object reports, its inconsistent counts left out. */
internal fun usage(raw: JsonObject): Usage {
    val hit = raw.long("prompt_cache_hit_tokens")
    val miss = raw.long("prompt_cache_miss_tokens")
    val input = raw.long("prompt_tokens") ?: if (hit != null && miss != null) hit + miss else null
    val details = raw.obj("prompt_tokens_details")
    var cacheRead = details?.long("cached_tokens") ?: hit
    var cacheWrite = details?.long("cache_write_tokens")
    val output = raw.long("completion_tokens")
    var reasoning = raw.obj("completion_tokens_details")?.long("reasoning_tokens")
    if (input != null && (cacheRead ?: 0) + (cacheWrite ?: 0) > input) {
        cacheWrite = null
        if ((cacheRead ?: 0) > input) cacheRead = null
    }
    if (output != null && reasoning != null && reasoning > output) reasoning = null
    return Usage.builder()
        .inputTokens(input?.takeIf { it >= 0 })
        .cacheReadTokens(cacheRead?.takeIf { it >= 0 })
        .cacheWriteTokens(cacheWrite?.takeIf { it >= 0 })
        .outputTokens(output?.takeIf { it >= 0 })
        .reasoningTokens(reasoning?.takeIf { it >= 0 })
        .contextTokens(input?.takeIf { it >= 0 })
        .raw(raw)
        .build()
}
