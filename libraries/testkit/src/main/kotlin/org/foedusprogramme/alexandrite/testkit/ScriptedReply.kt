package org.foedusprogramme.alexandrite.testkit

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.serialization.json.JsonObject
import org.foedusprogramme.alexandrite.sdk.chat.ToolCallId
import org.foedusprogramme.alexandrite.sdk.model.FinishKind
import org.foedusprogramme.alexandrite.sdk.model.FinishReason
import org.foedusprogramme.alexandrite.sdk.model.ModelError
import org.foedusprogramme.alexandrite.sdk.model.ModelErrorKind
import org.foedusprogramme.alexandrite.sdk.model.ModelEvent
import org.foedusprogramme.alexandrite.sdk.model.ModelException
import org.foedusprogramme.alexandrite.sdk.model.RequestIds
import org.foedusprogramme.alexandrite.sdk.model.Usage
import org.foedusprogramme.alexandrite.sdk.model.Warning
import org.foedusprogramme.alexandrite.sdk.model.rebuild
import org.foedusprogramme.alexandrite.sdk.transcript.AssistantEntry
import org.foedusprogramme.alexandrite.sdk.transcript.AssistantPart
import org.foedusprogramme.alexandrite.sdk.transcript.Dialect
import org.foedusprogramme.alexandrite.sdk.transcript.ModelRef
import org.foedusprogramme.alexandrite.sdk.transcript.OpaquePart
import org.foedusprogramme.alexandrite.sdk.transcript.ReasoningPart
import org.foedusprogramme.alexandrite.sdk.transcript.ReasoningSeal
import org.foedusprogramme.alexandrite.sdk.transcript.SealKind
import org.foedusprogramme.alexandrite.sdk.transcript.TextPart
import org.foedusprogramme.alexandrite.sdk.transcript.ToolCallPart

/** One response of a [ScriptedModel], streamed part by part. */
public class ScriptedReply private constructor(
    private val actions: List<Action>,
    private val usage: Usage,
    private val finish: FinishReason?,
    private val warnings: List<Warning>,
) {
    /** Whether the reply ends without its terminal event. */
    internal val cutShort: Boolean get() = actions.lastOrNull().let { it is Fail || it is Hang }

    /** Emits the events of the response to a request of [model] with [ids], as the stream contract orders them. */
    internal suspend fun FlowCollector<ModelEvent>.respond(
        responseId: String,
        model: ModelRef,
        ids: RequestIds,
        dialect: Dialect,
    ) {
        emit(ModelEvent.ResponseStarted(responseId, model.model, warnings))
        val parts = mutableListOf<AssistantPart>()
        for (action in actions) {
            val index = parts.size
            when (action) {
                is Text -> action.chunks.filter { it.isNotEmpty() }.forEach { emit(ModelEvent.TextDelta(index, it)) }

                is Reasoning -> {
                    if (action.text != null || action.summary != null) {
                        emit(ModelEvent.ReasoningDelta(index, action.text, action.summary))
                    }
                    action.seal(model, dialect)?.let { emit(ModelEvent.ReasoningSealed(index, it)) }
                }

                is Call -> {
                    val call = action.part(index, ids)
                    emit(ModelEvent.ToolCallStarted(index, call.id, call.name))
                    if (call.arguments.isNotEmpty()) emit(ModelEvent.ToolArgumentsDelta(index, call.arguments))
                }

                is Opaque -> Unit

                is Pause -> action.gate()

                Hang -> awaitCancellation()

                is Fail -> throw ModelException(action.error.rebuild { outputStarted(true) })
            }
            action.part(index, model, ids, dialect)?.let { part ->
                emit(ModelEvent.PartCompleted(index, part))
                parts += part
            }
        }
        emit(ModelEvent.UsageUpdated(usage))
        emit(completed(parts, model))
    }

    /** The terminal event of the response to a request of [model] with [ids]. */
    internal fun completed(model: ModelRef, ids: RequestIds, dialect: Dialect): ModelEvent.Completed {
        require(!cutShort) { "A reply that hangs or fails has no terminal event." }
        val parts = mutableListOf<AssistantPart>()
        for (action in actions) action.part(parts.size, model, ids, dialect)?.let(parts::add)
        return completed(parts, model)
    }

    private fun completed(parts: List<AssistantPart>, model: ModelRef): ModelEvent.Completed {
        val kind = if (parts.any { it is ToolCallPart }) FinishKind.TOOL_USE else FinishKind.END_TURN
        return ModelEvent.Completed(AssistantEntry(null, parts, model), finish ?: FinishReason(kind, null, null), usage)
    }

    public class Builder internal constructor() {
        private val actions = mutableListOf<Action>()
        private var usage: Usage = Usage.builder().build()
        private var finish: FinishReason? = null
        private val warnings = mutableListOf<Warning>()

        /** A text part, streamed as one delta per chunk. */
        public fun text(vararg chunks: String): Builder = add(Text(chunks.toList()))

        /** A reasoning part, sealed with [seal] of [sealKind] for the model that made it unless [seal] is null. */
        public fun reasoning(
            text: String? = null,
            summary: String? = null,
            seal: String? = null,
            sealKind: SealKind = SealKind.SIGNATURE,
        ): Builder = add(Reasoning(text, summary, seal, sealKind))

        /** A call of the tool [name], whose id is the request's call id of the part unless given. */
        public fun toolCall(name: String, arguments: String = "{}", id: ToolCallId? = null): Builder {
            require(name.isNotEmpty()) { "A tool call names a tool." }
            return add(Call(name, arguments, id))
        }

        /** Output of the endpoint's dialect that has no part of its own. */
        public fun opaque(kind: String, json: JsonObject): Builder = add(Opaque(kind, json))

        /** Suspends the stream on [gate] once the events so far are emitted. */
        public fun pause(gate: suspend () -> Unit): Builder = add(Pause(gate))

        /** Suspends the stream until its collector is cancelled, once the events so far are emitted. */
        public fun hang(): Builder = add(Hang)

        /** Throws a [ModelException] of [error] once the events so far are emitted. */
        public fun fail(error: ModelError): Builder = add(Fail(error))

        public fun fail(kind: ModelErrorKind, message: String = "Scripted $kind failure."): Builder =
            fail(ModelError.builder(kind, message).build())

        public fun usage(usage: Usage): Builder = apply { this.usage = usage }

        /** The finish reason, by default TOOL_USE when the reply calls tools and END_TURN otherwise. */
        public fun finish(kind: FinishKind, raw: String? = null, detail: String? = null): Builder =
            apply { finish = FinishReason(kind, raw, detail) }

        public fun warning(kind: String, message: String): Builder = apply { warnings += Warning(kind, message) }

        public fun build(): ScriptedReply = ScriptedReply(actions.toList(), usage, finish, warnings.toList())

        private fun add(action: Action): Builder = apply {
            check(actions.lastOrNull().let { it !is Fail && it !is Hang }) {
                "A scripted reply ends where it hangs or fails."
            }
            actions += action
        }
    }

    public companion object {
        public fun builder(): Builder = Builder()
    }
}

public fun scriptedReply(block: ScriptedReply.Builder.() -> Unit): ScriptedReply =
    ScriptedReply.builder().apply(block).build()

private sealed interface Action {
    /** The part at [index] of the response, null when the action makes none. */
    fun part(index: Int, model: ModelRef, ids: RequestIds, dialect: Dialect): AssistantPart? = null
}

private class Text(val chunks: List<String>) : Action {
    override fun part(index: Int, model: ModelRef, ids: RequestIds, dialect: Dialect) =
        TextPart(chunks.joinToString(""))
}

private class Reasoning(val text: String?, val summary: String?, val seal: String?, val kind: SealKind) : Action {
    fun seal(model: ModelRef, dialect: Dialect): ReasoningSeal? = seal?.let { ReasoningSeal(model, dialect, kind, it) }

    override fun part(index: Int, model: ModelRef, ids: RequestIds, dialect: Dialect) =
        ReasoningPart(text, summary, seal(model, dialect))
}

private class Call(val name: String, val arguments: String, val id: ToolCallId?) : Action {
    fun part(index: Int, ids: RequestIds) = ToolCallPart(id ?: ids.callId(index), name, arguments)

    override fun part(index: Int, model: ModelRef, ids: RequestIds, dialect: Dialect) = part(index, ids)
}

private class Opaque(val kind: String, val json: JsonObject) : Action {
    override fun part(index: Int, model: ModelRef, ids: RequestIds, dialect: Dialect) = OpaquePart(dialect, kind, json)
}

private class Pause(val gate: suspend () -> Unit) : Action

private data object Hang : Action

private class Fail(val error: ModelError) : Action
