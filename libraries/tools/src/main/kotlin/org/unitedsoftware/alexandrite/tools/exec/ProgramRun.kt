package org.unitedsoftware.alexandrite.tools.exec

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.unitedsoftware.alexandrite.internal.process.destroyTree
import org.unitedsoftware.alexandrite.internal.tool.ToolRefusal
import org.unitedsoftware.alexandrite.sdk.tool.SegmentJoint
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** How a program run ended: [exitCode] is null when it timed out. */
internal class ProgramOutcome(val exitCode: Int?, val output: String)

/**
 * Runs [command] in [cwd] with [environment] and no input for at most [timeout], keeping [maxChars] of its output.
 *
 * The program and the processes it started are destroyed when it times out or the call is cancelled.
 */
internal suspend fun runProgram(
    command: List<String>,
    cwd: Path,
    environment: Map<String, String>,
    timeout: Duration,
    maxChars: Int,
    onLine: ((String) -> Unit)? = null,
    launch: (ProcessBuilder) -> Process = ProcessBuilder::start,
): ProgramOutcome {
    val builder = ProcessBuilder(command).directory(cwd.toFile()).redirectErrorStream(true)
    builder.environment().apply {
        clear()
        putAll(environment)
    }
    val started = AtomicReference<Process>()
    try {
        withContext(Dispatchers.IO + NonCancellable) { started.set(launch(builder)) }
        val process = started.get()
        process.outputStream.close()
        val output = OutputCollector(maxChars)
        val reader = thread(isDaemon = true, name = "command.exec output ${process.pid()}") {
            output.drain(process.inputStream, onLine)
        }
        val exited = withTimeoutOrNull(timeout) { process.onExit().await() } != null
        if (!exited) withContext(NonCancellable) { destroyTree(process, STOP_GRACE) }
        runInterruptible(Dispatchers.IO) { reader.join(READER_WAIT.inWholeMilliseconds) }
        return ProgramOutcome(if (exited) process.exitValue() else null, output.text())
    } catch (e: CancellationException) {
        started.get()?.let { withContext(NonCancellable) { destroyTree(it, STOP_GRACE) } }
        throw e
    }
}

/**
 * The variables of [parent] that a program gets: the usual ones and those named in [extra]. They include `TERM` and
 * every `LC_*` on purpose, which an MCP server's minimal environment leaves out, since a command's output is read like
 * a terminal's.
 */
internal fun childEnvironment(parent: Map<String, String>, extra: List<String>): Map<String, String> {
    val passed = parent.filterKeys { it in PASSED || it.startsWith("LC_") || it in extra }
    if ("PATH" in passed) return passed
    return passed + ("PATH" to SYSTEM_DIRECTORIES.joinToString(File.pathSeparator))
}

/** Keeps the first and the last characters of an output, counting the ones cut between them. */
internal class OutputCollector(limit: Int) {
    private val headLimit = limit - limit / 2
    private val head = StringBuilder()
    private val tail = CharArray(limit / 2)
    private var tailStart = 0
    private var tailSize = 0
    private var cut = 0L

    fun drain(stream: InputStream, onLine: ((String) -> Unit)? = null) {
        val line = StringBuilder()
        var omitted = 0L
        fun reportLine() {
            val truncation = if (omitted > 0) " [$omitted characters omitted]" else ""
            onLine?.invoke(line.toString().trimEnd('\r') + truncation)
            line.setLength(0)
            omitted = 0
        }
        try {
            stream.reader(Charsets.UTF_8).use { reader ->
                val chunk = CharArray(CHUNK)
                while (true) {
                    val count = reader.read(chunk)
                    if (count < 0) break
                    append(chunk, count)
                    if (onLine != null) {
                        for (index in 0 until count) {
                            val character = chunk[index]
                            if (character == '\n') {
                                reportLine()
                            } else if (line.length < CHUNK) {
                                line.append(character)
                            } else {
                                omitted++
                            }
                        }
                    }
                }
            }
            if (line.isNotEmpty()) reportLine()
        } catch (e: IOException) {
            // The stream closed under the reader.
        }
    }

    @Synchronized
    fun append(chars: CharArray, count: Int) {
        val headed = minOf(count, headLimit - head.length)
        head.appendRange(chars, 0, headed)
        for (index in headed until count) {
            if (tail.isEmpty()) {
                cut++
            } else if (tailSize < tail.size) {
                tail[(tailStart + tailSize++) % tail.size] = chars[index]
            } else {
                tail[tailStart] = chars[index]
                tailStart = (tailStart + 1) % tail.size
                cut++
            }
        }
    }

    /** The output, with a note where characters were cut. */
    @Synchronized
    fun text(): String {
        var first = head.toString()
        var last = String(CharArray(tailSize) { tail[(tailStart + it) % tail.size] })
        if (cut == 0L) return first + last
        var dropped = cut
        if (first.lastOrNull()?.isHighSurrogate() == true) {
            first = first.dropLast(1)
            dropped++
        }
        if (last.firstOrNull()?.isLowSurrogate() == true) {
            last = last.drop(1)
            dropped++
        }
        return "$first\n[… $dropped characters cut …]\n$last"
    }
}

private val PASSED = setOf("PATH", "HOME", "USER", "LANG", "TERM", "TMPDIR")

/** How long a program and its processes have to stop before they are killed. */
private val STOP_GRACE = 1.seconds

/** How long the output is still read after the program ended. */
private val READER_WAIT = 1.seconds

private const val CHUNK = 8192

/**
 * Runs the steps of [call] in turn, without a shell, each program started by [launch] with [environment] and its own
 * variables, all of them for at most [timeout], keeping [maxChars] of their output.
 *
 * A step is checked again by [planner] right before it runs, and the call stops where the check finds a change.
 */
internal suspend fun runSteps(
    call: PlannedCall,
    planner: Planner,
    environment: Map<String, String>,
    timeout: Duration,
    maxChars: Int,
    onLine: ((String) -> Unit)? = null,
    launch: (ProcessBuilder) -> Process = ProcessBuilder::start,
): ProgramOutcome {
    val start = TimeSource.Monotonic.markNow()
    val single = call.steps.size == 1
    val shown = mutableListOf<String>()
    var status = 0
    var cwd = call.cwd
    for ((index, step) in call.steps.withIndex()) {
        val header = "$ ${commandLine(step.words)}"
        val runs = when (step.joint) {
            SegmentJoint.AND -> status == 0
            SegmentJoint.OR -> status != 0
            else -> true
        }
        if (!runs) {
            shown += "$header [not run]"
            continue
        }
        val run = step.run
        if (run == null) {
            val target = step.words[1]
            val checked = recheck(call, step, planner) { planner.changed(target, cwd) }
            when {
                checked is Checked.Stopped -> return stopped(shown, header, checked.why, single, maxChars)

                Files.isDirectory(step.cwd) -> {
                    cwd = step.cwd
                    status = 0
                    shown += header
                }

                else -> {
                    status = 1
                    shown += "$header [exit 1]\ncd: $target: no such directory"
                }
            }
        } else {
            val checked = recheck(call, step, planner) {
                planner.program(run.argv, cwd, run.timeout, run.environment, run.limit).executable
            }
            if (checked is Checked.Stopped) return stopped(shown, header, checked.why, single, maxChars)
            val left = timeout - start.elapsedNow()
            if (!left.isPositive()) return ProgramOutcome(null, joined(shown, maxChars))
            val limited = run.limit?.takeIf { it < left }
            val outcome = runProgram(
                listOf(run.resolved.toString()) + run.argv.drop(1),
                cwd,
                environment + run.environment,
                limited ?: left,
                maxChars,
                onLine,
                launch,
            )
            val output = outcome.output.trimEnd()
            val code = outcome.exitCode ?: if (limited != null) TIMED_OUT else null
            if (single) {
                val note = if (outcome.exitCode == null && code != null) {
                    "\n[stopped by its timeout after ${limited?.inWholeSeconds} s]"
                } else {
                    ""
                }
                return ProgramOutcome(code, output + note)
            }
            val ending = when {
                code == null -> " [timed out]"
                outcome.exitCode == null -> " [stopped by its timeout, exit $code]"
                code != 0 -> " [exit $code]"
                else -> ""
            }
            shown += header + ending + if (output.isEmpty()) "" else "\n$output"
            code ?: return ProgramOutcome(null, joined(shown, maxChars))
            status = code
        }
        val next = call.steps.getOrNull(index + 1)?.joint
        if (call.errexit && status != 0 && (next == null || next == SegmentJoint.THEN)) break
    }
    return ProgramOutcome(status, joined(shown, maxChars))
}

/** Whether [step] of [call] still plans as it did, where [planned] plans it again. */
private fun recheck(call: PlannedCall, step: PlannedStep, planner: Planner, planned: () -> Path): Checked {
    val again = try {
        planned()
    } catch (e: ToolRefusal) {
        return Checked.Stopped(e.message)
    }
    val expected = step.run?.executable ?: step.cwd
    if (again != expected) {
        return Checked.Stopped("${step.words.first()} changed since ${call.argv.first()} was reviewed.")
    }
    if (!call.asks.containsAll(planner.asks)) {
        return Checked.Stopped("${step.words.first()} reaches a place the floor asks a person about since review.")
    }
    return Checked.Same
}

private sealed interface Checked {
    data object Same : Checked

    class Stopped(val why: String) : Checked
}

/** The outcome of a call stopped at the step of [header] for [why]. */
private fun stopped(shown: List<String>, header: String, why: String, single: Boolean, maxChars: Int): ProgramOutcome {
    if (single) return ProgramOutcome(NOT_RUN, "[not run: $why]")
    return ProgramOutcome(NOT_RUN, joined(shown + "$header [not run: $why]", maxChars))
}

/** [blocks] one after the other, cut in the middle to [maxChars]. */
private fun joined(blocks: List<String>, maxChars: Int): String {
    val text = blocks.joinToString("\n")
    if (text.length <= maxChars) return text
    val collector = OutputCollector(maxChars)
    collector.append(text.toCharArray(), text.length)
    return collector.text()
}

/** [words] as one command line, each word that a shell would split or expand in single quotes. */
internal fun commandLine(words: List<String>): String = words.joinToString(" ") { word ->
    val plain = word.isNotEmpty() && word.all { it.isLetterOrDigit() || it in PLAIN }
    if (plain) word else "'${word.replace("'", "'\\''")}'"
}

/** The characters a shell takes as part of a word. */
private const val PLAIN = "-_./=:,+@%~"

/** The exit code of a program that its `timeout` stopped. */
private const val TIMED_OUT = 124

/** The exit code of a step that could not run. */
private const val NOT_RUN = 126
