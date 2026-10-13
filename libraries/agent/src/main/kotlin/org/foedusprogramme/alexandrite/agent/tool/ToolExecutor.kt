package org.foedusprogramme.alexandrite.agent.tool

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import org.foedusprogramme.alexandrite.agent.cancel.TurnShutdown
import org.foedusprogramme.alexandrite.agent.cancel.turnCause
import org.foedusprogramme.alexandrite.agent.config.AgentSettings
import org.foedusprogramme.alexandrite.agent.directory.ConversationDirectories
import org.foedusprogramme.alexandrite.agent.job.Detaching
import org.foedusprogramme.alexandrite.agent.job.Jobs
import org.foedusprogramme.alexandrite.agent.job.backgroundResult
import org.foedusprogramme.alexandrite.agent.permission.TurnTaint
import org.foedusprogramme.alexandrite.agent.prompt.Escaper
import org.foedusprogramme.alexandrite.agent.workspace.Workspaces
import org.foedusprogramme.alexandrite.sdk.channel.ToolCallStatus
import org.foedusprogramme.alexandrite.sdk.channel.TurnEvent
import org.foedusprogramme.alexandrite.sdk.chat.TurnInfo
import org.foedusprogramme.alexandrite.sdk.di.Singleton
import org.foedusprogramme.alexandrite.sdk.tool.ReviewPreview
import org.foedusprogramme.alexandrite.sdk.tool.Tool
import org.foedusprogramme.alexandrite.sdk.tool.ToolActivity
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
internal class ToolExecutor(
    private val gate: ToolGate,
    settings: AgentSettings,
    private val workspaces: Workspaces,
    private val directories: ConversationDirectories,
    private val jobs: Jobs,
) {
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
    ): ToolCallEnd {
        val tool = tools[call.name]
            ?: return ended(call, "No tool named '${call.name}' is offered.", NotRunReason.UNKNOWN_TOOL, calls)
        if (call.name !in callable) {
            return ended(call, "This turn may not call '${call.name}'.", NotRunReason.NOT_PERMITTED, calls)
        }
        val arguments = call.parseArguments()
            ?: return ended(call, "The arguments are not a JSON object.", NotRunReason.INVALID_ARGUMENTS, calls)
        val limit = tool.definition.timeout?.let { maxOf(timeout, it + DECLARED_TIMEOUT_GRACE) } ?: timeout
        val reviewing = AgentToolContext(turn, call.id, workspaces, directories, null, taint)
        val admission = try {
            gate.admit(GateCall(turn, call, tool, arguments, limit, taint, reviewing, calls))
        } finally {
            reviewing.close()
        }
        return when (admission) {
            is GateResult.Refused -> {
                calls.ended(call.id, ToolCallStatus.NOT_RUN, null)
                ToolCallEnd(textResult(call, admission.text, admission.outcome), admission.notice)
            }

            is GateResult.Admitted -> {
                calls.starting(call.id)
                calls.running(call.id)
                val delegating = calls as? DelegatingTurn
                val context =
                    AgentToolContext(turn, call.id, workspaces, directories, admission.clearance, taint, delegating)
                val result = if (tool is InTurnTool) {
                    inTurn(turn, call, tool, arguments, context)
                } else {
                    runTool(turn, call, tool, arguments, limit, context)
                }
                val content = contentOf(tool.definition.activity, admission.preview, result, admission.scratch)
                calls.ended(call.id, statusOf(result.outcome), content)
                ToolCallEnd(result, null, context.touchedPaths)
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
        var detached = false
        val explicit = call.name == "command.exec" &&
            ((arguments["background"] as? JsonPrimitive)?.booleanOrNull == true || arguments["monitor"] is JsonObject)
        if (explicit && turn.lineage != null) {
            context.close()
            return textResult(
                call,
                "A delegated run cannot start background commands; run the command synchronously.",
                ToolOutcome.NotRun(NotRunReason.NOT_PERMITTED),
            )
        }
        try {
            val running = CoroutineScope(currentCoroutineContext().minusKey(Job)).async(
                start = if (explicit) CoroutineStart.LAZY else CoroutineStart.DEFAULT,
            ) { tool.execute(arguments, context) }
            val returned = try {
                val foreground = if (explicit) {
                    Duration.ZERO
                } else if (turn.lineage == null) {
                    minOf(jobs.foreground(turn.agent), timeout)
                } else {
                    timeout
                }
                (if (explicit) null else withTimeoutOrNull(foreground) { running.await() }) ?: run {
                    val job = jobs.detach(Detaching(turn, call, arguments, running, context, foreground, timeout))
                    if (job != null) {
                        detached = true
                        return backgroundResult(call, job)
                    }
                    if (explicit) {
                        stop(running, turn, call, ABANDON_AFTER)
                        return textResult(
                            call,
                            "Background capacity is full; the command was stopped.",
                            ToolOutcome.NotRun(NotRunReason.DELEGATION_LIMIT),
                        )
                    }
                    withTimeoutOrNull(timeout - foreground) { running.await() }
                }
            } catch (e: CancellationException) {
                if (currentCoroutineContext().isActive) return textResult(call, CANCELLED_RESULT, ToolOutcome.Cancelled)
                stop(running, turn, call, if (turnCause(e) is TurnShutdown) shutdownWait else ABANDON_AFTER)
                throw e
            } catch (e: Throwable) {
                if (e is VirtualMachineError) throw e
                logger.warn("Tool {} failed in turn {}: {}", call.name, turn.id, e.toString())
                return failedResult(call, e)
            }
            if (returned == null) {
                logger.warn("Tool {} timed out in turn {} after {}", call.name, turn.id, timeout)
                stop(running, turn, call, ABANDON_AFTER)
                return timedOutResult(call, timeout)
            }
            val outcome = if (returned.isError) ToolOutcome.Failed else ToolOutcome.Succeeded
            return ToolResultEntry(null, call.id, call.name, shaped(returned.content), outcome)
        } finally {
            if (!detached) context.close()
        }
    }

    /** How [call] of [tool] ends, run in the turn's coroutine. */
    private suspend fun inTurn(
        turn: TurnInfo,
        call: ToolCallPart,
        tool: InTurnTool,
        arguments: JsonObject,
        context: AgentToolContext,
    ): ToolResultEntry {
        var detached = false
        try {
            val result = tool.call(arguments, context)
            detached = result.outcome is ToolOutcome.Detached
            return ToolResultEntry(null, call.id, call.name, result.content, result.outcome)
        } catch (e: CancellationException) {
            if (currentCoroutineContext().isActive) return textResult(call, CANCELLED_RESULT, ToolOutcome.Cancelled)
            throw e
        } catch (e: Throwable) {
            if (e is VirtualMachineError) throw e
            logger.warn("Tool {} failed in turn {}: {}", call.name, turn.id, e.toString())
            return failedResult(call, e)
        } finally {
            if (!detached) context.close()
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

    private suspend fun ended(call: ToolCallPart, text: String, reason: NotRunReason, calls: TurnCalls): ToolCallEnd {
        calls.ended(call.id, ToolCallStatus.NOT_RUN, null)
        return ToolCallEnd(textResult(call, text, ToolOutcome.NotRun(reason)), null)
    }
}

internal fun textResult(call: ToolCallPart, text: String, outcome: ToolOutcome): ToolResultEntry =
    ToolResultEntry(null, call.id, call.name, listOf(TextPart(text)), outcome)

/** The result of [call], whose tool threw [error]. */
internal fun failedResult(call: ToolCallPart, error: Throwable): ToolResultEntry {
    val why = error.message?.lineSequence()?.firstOrNull()?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
    return textResult(call, "The tool failed: $why", ToolOutcome.Failed)
}

/** The result of [call], whose tool ran past [timeout] and was stopped. */
internal fun timedOutResult(call: ToolCallPart, timeout: Duration): ToolResultEntry =
    textResult(call, "The call timed out after ${timeout.inWholeSeconds} s.", ToolOutcome.Failed)

/** The [ToolCallStatus] a finished call's [outcome] is shown as. */
private fun statusOf(outcome: ToolOutcome): ToolCallStatus = when (outcome) {
    ToolOutcome.Succeeded -> ToolCallStatus.COMPLETED
    is ToolOutcome.Detached -> ToolCallStatus.BACKGROUNDED
    is ToolOutcome.NotRun -> ToolCallStatus.NOT_RUN
    else -> ToolCallStatus.FAILED
}

/**
 * What a finished call of [activity] is shown with: the review's [preview] for an edit, [result]'s tail for a run.
 *
 * A [scratch] edit shows no diff, as its target lies in the turn's own conversation directory.
 */
private fun contentOf(
    activity: ToolActivity,
    preview: ReviewPreview?,
    result: ToolResultEntry,
    scratch: Boolean,
): TurnEvent.Content? = when (activity) {
    ToolActivity.EDIT -> (preview as? ReviewPreview.Diff)
        ?.takeIf { result.outcome == ToolOutcome.Succeeded && !scratch }
        ?.let { TurnEvent.Content.Diff(it.path, it.unified) }

    ToolActivity.EXECUTE -> textOf(result.content).takeIf { it.isNotBlank() }
        ?.let { TurnEvent.Content.Output(tailLines(it, OUTPUT_TAIL_LINES)) }

    else -> null
}

/** The text of [parts], its other kinds left out. */
private fun textOf(parts: List<ToolOutputPart>): String =
    parts.filterIsInstance<TextPart>().joinToString("") { it.text }

/** The last [max] lines of [text]. */
private fun tailLines(text: String, max: Int): String {
    val lines = text.lines()
    return if (lines.size <= max) text else lines.takeLast(max).joinToString("\n")
}

/** How many lines of a run's output a [TurnEvent.Content.Output] keeps. */
private const val OUTPUT_TAIL_LINES = 20

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
