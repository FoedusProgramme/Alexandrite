package org.foedusprogramme.alexandrite.testkit

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import org.foedusprogramme.alexandrite.sdk.chat.ToolCallId
import org.foedusprogramme.alexandrite.sdk.chat.TurnInfo
import org.foedusprogramme.alexandrite.sdk.tool.Tool
import org.foedusprogramme.alexandrite.sdk.tool.ToolContext
import org.foedusprogramme.alexandrite.sdk.tool.ToolDefinition
import org.foedusprogramme.alexandrite.sdk.tool.ToolResult
import org.foedusprogramme.alexandrite.sdk.tool.ToolRisk
import kotlin.coroutines.cancellation.CancellationException

/** A tool that records its calls and answers each with what [result] returns. */
public class RecordingTool internal constructor(
    override val definition: ToolDefinition,
    private val result: suspend (arguments: JsonObject, context: ToolContext) -> ToolResult,
) : Tool {
    private val lock = Any()
    private val started = MutableStateFlow<List<Call>>(emptyList())
    private val cancelledCalls = mutableListOf<Call>()
    private var gate: CompletableDeferred<Unit>? = null

    /** The calls that started, in order. */
    public val calls: List<Call> get() = started.value

    /** The calls that were cancelled before they returned. */
    public val cancelled: List<Call> get() = synchronized(lock) { cancelledCalls.toList() }

    /** Makes the calls from now on wait until [release]. */
    public fun hold(): RecordingTool = apply { synchronized(lock) { if (gate == null) gate = CompletableDeferred() } }

    /** Lets the waiting calls and every later one run. */
    public fun release() {
        synchronized(lock) {
            gate?.complete(Unit)
            gate = null
        }
    }

    /** Suspends until [count] calls started, and returns them. */
    public suspend fun awaitCalls(count: Int): List<Call> = started.first { it.size >= count }.take(count)

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val call = Call(arguments, context.turn, context.call)
        val held = synchronized(lock) {
            started.value += call
            gate
        }
        try {
            held?.await()
            return result(arguments, context)
        } catch (e: CancellationException) {
            synchronized(lock) { cancelledCalls += call }
            throw e
        }
    }

    /** A call of the tool. */
    public data class Call(public val arguments: JsonObject, public val turn: TurnInfo, public val call: ToolCallId)
}

public fun recordingTool(
    name: String = "test.tool",
    risk: ToolRisk = ToolRisk.READ_ONLY,
    result: suspend (arguments: JsonObject, context: ToolContext) -> ToolResult = { _, _ -> ToolResult("done") },
): RecordingTool = RecordingTool(testToolDefinition(name, risk), result)
