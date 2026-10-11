package org.foedusprogramme.alexandrite.tools.exec

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.foedusprogramme.alexandrite.internal.process.destroyTree
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

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
): ProgramOutcome {
    val builder = ProcessBuilder(command).directory(cwd.toFile()).redirectErrorStream(true)
    builder.environment().apply {
        clear()
        putAll(environment)
    }
    val started = AtomicReference<Process>()
    try {
        withContext(Dispatchers.IO + NonCancellable) { started.set(builder.start()) }
        val process = started.get()
        process.outputStream.close()
        val output = OutputCollector(maxChars)
        val reader = thread(isDaemon = true, name = "command.exec output ${process.pid()}") {
            output.drain(process.inputStream)
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

/** The variables of [parent] that a program gets: the usual ones and those named in [extra]. */
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

    fun drain(stream: InputStream) {
        try {
            stream.reader(Charsets.UTF_8).use { reader ->
                val chunk = CharArray(CHUNK)
                while (true) {
                    val count = reader.read(chunk)
                    if (count < 0) break
                    append(chunk, count)
                }
            }
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
