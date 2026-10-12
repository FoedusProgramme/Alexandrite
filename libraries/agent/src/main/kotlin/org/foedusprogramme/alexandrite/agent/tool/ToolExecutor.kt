package org.foedusprogramme.alexandrite.agent.tool

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import org.foedusprogramme.alexandrite.agent.cancel.TurnShutdown
import org.foedusprogramme.alexandrite.agent.cancel.turnCause
import org.foedusprogramme.alexandrite.agent.config.AgentSettings
import org.foedusprogramme.alexandrite.agent.permission.TurnTaint
import org.foedusprogramme.alexandrite.agent.prompt.Escaper
import org.foedusprogramme.alexandrite.agent.workspace.Workspaces
import org.foedusprogramme.alexandrite.sdk.chat.ChannelMessageRef
import org.foedusprogramme.alexandrite.sdk.chat.TurnInfo
import org.foedusprogramme.alexandrite.sdk.di.Singleton
import org.foedusprogramme.alexandrite.sdk.tool.Tool
import org.foedusprogramme.alexandrite.sdk.transcript.NotRunReason
import org.foedusprogramme.alexandrite.sdk.transcript.TextPart
import org.foedusprogramme.alexandrite.sdk.transcript.ToolCallPart
import org.foedusprogramme.alexandrite.sdk.transcript.ToolOutcome
import org.foedusprogramme.alexandrite.sdk.transcript.ToolOutputPart
import org.foedusprogramme.alexandrite.sdk.transcript.ToolResultEntry
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The result the model reads of a call cancelled while it ran. */
internal const val CANCELLED_RESULT: String = "Cancelled (outcome unknown)"

/** The characters of a tool result's text that the model reads. */
internal const val MAX_RESULT_CHARS: Int = 200_000

/** How a tool call ended. */
internal class ToolCallEnd(
    /** Not stored yet, with its media inline. */
    val result: ToolResultEntry,
    /** What the chat is told of the call that did not run, null for nothing. */
    val notice: String?,
    /** The paths that the call reported as touched. */
    val touched: List<Path> = emptyList(),
)

/** Runs the tool calls of a turn's responses. */
@Singleton
internal class ToolExecutor(private val gate: ToolGate, settings: AgentSettings, private val workspaces: Workspaces) {
    /** How long a tool cancelled at a shutdown may take to end before it is abandoned. */
    private val shutdownWait = minOf(ABANDON_AFTER, settings.shutdown.cancelJoinSeconds.seconds / 2)

    /**
     * How [call] in [turn] ends, where the turn offers [tools], may call those named in [callable], a tool may run
     * for [timeout] unless it declares longer, untrusted content marks [taint] and the turn does its part through
     * [calls].
     */
    suspend fun execute(
        turn: TurnInfo,
        call: ToolCallPart,
        tools: Map<String, Tool>,
        timeout: Duration,
        callable: Set<String>,
        taint: TurnTaint,
        calls: TurnCalls,
        /** The message the turn was started by, which a tool that acts on it reads from its context. */
        trigger: ChannelMessageRef?,
    ): ToolCallEnd {
        val tool = tools[call.name]
            ?: return ended(call, "No tool named '${call.name}' is offered.", NotRunReason.UNKNOWN_TOOL)
        if (call.name !in callable) {
            return ended(call, "This turn may not call '${call.name}'.", NotRunReason.NOT_PERMITTED)
        }
        val arguments = call.parseArguments()
            ?: return ended(call, "The arguments are not a JSON object.", NotRunReason.INVALID_ARGUMENTS)
        val limit = tool.definition.timeout?.let { maxOf(timeout, it + DECLARED_TIMEOUT_GRACE) } ?: timeout
        val reviewing = AgentToolContext(turn, call.id, workspaces, null, taint, trigger)
        val admission = try {
            gate.admit(GateCall(turn, call, tool, arguments, limit, taint, reviewing, calls))
        } finally {
            reviewing.close()
        }
        return when (admission) {
            is GateResult.Refused -> ToolCallEnd(textResult(call, admission.text, admission.outcome), admission.notice)

            is GateResult.Admitted -> {
                calls.starting(call.id)
                val context = AgentToolContext(turn, call.id, workspaces, admission.clearance, taint, trigger)
                ToolCallEnd(runTool(turn, call, tool, arguments, limit, context), null, context.touchedPaths)
            }
        }
    }

    private suspend fun runTool(
        turn: TurnInfo,
        call: ToolCallPart,
        tool: Tool,
        arguments: JsonObject,
        timeout: Duration,
        context: AgentToolContext,
    ): ToolResultEntry {
        try {
            val running = CoroutineScope(currentCoroutineContext().minusKey(Job)).async {
                tool.execute(arguments, context)
            }
            val returned = try {
                withTimeoutOrNull(timeout) { running.await() }
            } catch (e: CancellationException) {
                if (currentCoroutineContext().isActive) return textResult(call, CANCELLED_RESULT, ToolOutcome.Cancelled)
                stop(running, turn, call, if (turnCause(e) is TurnShutdown) shutdownWait else ABANDON_AFTER)
                throw e
            } catch (e: Throwable) {
                if (e is VirtualMachineError) throw e
                logger.warn("Tool {} failed in turn {}: {}", call.name, turn.id, e.toString())
                val why = e.message?.lineSequence()?.firstOrNull()?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                return textResult(call, "The tool failed: $why", ToolOutcome.Failed)
            }
            if (returned == null) {
                logger.warn("Tool {} timed out in turn {} after {}", call.name, turn.id, timeout)
                stop(running, turn, call, ABANDON_AFTER)
                return textResult(call, "The call timed out after ${timeout.inWholeSeconds} s.", ToolOutcome.Failed)
            }
            val outcome = if (returned.isError) ToolOutcome.Failed else ToolOutcome.Succeeded
            return ToolResultEntry(null, call.id, call.name, shaped(returned.content), outcome)
        } finally {
            context.close()
        }
    }

    /** Cancels [running] and waits at most [wait] for it to end. */
    private suspend fun stop(running: Job, turn: TurnInfo, call: ToolCallPart, wait: Duration) {
        withContext(NonCancellable) {
            running.cancel()
            if (withTimeoutOrNull(wait) { running.join() } == null) {
                logger.warn(
                    "Tool {} still runs {} after its call {} in turn {} was cancelled: it is abandoned and its " +
                        "result discarded",
                    call.name,
                    wait,
                    call.id,
                    turn.id,
                )
            }
        }
    }

    private fun ended(call: ToolCallPart, text: String, reason: NotRunReason): ToolCallEnd =
        ToolCallEnd(textResult(call, text, ToolOutcome.NotRun(reason)), null)
}

internal fun textResult(call: ToolCallPart, text: String, outcome: ToolOutcome): ToolResultEntry =
    ToolResultEntry(null, call.id, call.name, listOf(TextPart(text)), outcome)

/** [content] with its text escaped and cut after [MAX_RESULT_CHARS] characters, where a note says so. */
internal fun shaped(content: List<ToolOutputPart>): List<ToolOutputPart> {
    val escaped = content.map { if (it is TextPart) TextPart(Escaper.HEADERS.text(it.text)) else it }
    val total = escaped.sumOf { (it as? TextPart)?.text?.length ?: 0 }
    if (total <= MAX_RESULT_CHARS) return escaped
    var left = MAX_RESULT_CHARS
    val kept = escaped.mapNotNull { part ->
        if (part !is TextPart) return@mapNotNull part
        val text = part.text
        val end = if (text.length <= left) text.length else cutAt(text, left)
        left = if (end < text.length) 0 else left - end
        if (end == 0) null else TextPart(text.substring(0, end))
    }
    val note = "\n[The result was cut here: it held $total characters, of which the model reads $MAX_RESULT_CHARS.]"
    return kept + TextPart(note)
}

/** [limit], or one less where that would split a surrogate pair of [text]. */
private fun cutAt(text: String, limit: Int): Int =
    if (limit > 0 && text[limit - 1].isHighSurrogate()) limit - 1 else limit

/** How long a cancelled tool may take to end before it is abandoned. */
private val ABANDON_AFTER = 5.seconds

/** How much longer than its declared timeout a tool may run. */
private val DECLARED_TIMEOUT_GRACE = 5.seconds

private val logger = LoggerFactory.getLogger(ToolExecutor::class.java)
