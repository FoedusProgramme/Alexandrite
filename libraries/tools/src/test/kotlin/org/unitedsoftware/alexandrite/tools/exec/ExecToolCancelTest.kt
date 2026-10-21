package org.unitedsoftware.alexandrite.tools.exec

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.sdk.tool.ToolResult
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

@DisabledOnOs(OS.WINDOWS)
class ExecToolCancelTest {
    @TempDir
    lateinit var directory: Path

    private lateinit var fs: ExecFixture
    private lateinit var pids: Path
    private val started = mutableListOf<ProcessHandle>()

    @BeforeEach
    fun setUp() {
        fs = ExecFixture(directory)
        pids = fs.workspace.resolve("pids")
        fs.script("workspace/spawn.sh", "echo started\nsleep 300 &\necho \$! > \"\$1\"\necho \$\$ >> \"\$1\"\nwait")
    }

    @AfterTest
    fun tearDown() {
        started.forEach { it.destroyForcibly() }
    }

    /** The shell and the `sleep` it started, once both are running. */
    private suspend fun spawned(): List<ProcessHandle> {
        withTimeout(10.seconds) {
            while (!pids.exists() || pids.readLines().size < 2) delay(20)
        }
        return pids.readLines().map { ProcessHandle.of(it.trim().toLong()).orElseThrow() }.also { started += it }
    }

    private fun gone(handle: ProcessHandle): Boolean {
        val deadline = TimeSource.Monotonic.markNow() + 5.seconds
        while (handle.isAlive && deadline.hasNotPassedNow()) Thread.sleep(20)
        return !handle.isAlive
    }

    @Test
    fun `a program that runs past its timeout is stopped with the processes it started`() {
        val result = fs.tool().run(fs.allowed(), "argv" to listOf("./spawn.sh", "$pids"), "timeoutSeconds" to 3)

        val processes = pids.readLines().map { ProcessHandle.of(it.trim().toLong()) }
        assertEquals(
            true to "The program timed out after 3 s and was stopped with the processes it started. Its output " +
                "until then:\nstarted",
            result.isError to result.text,
        )
        assertTrue(processes.all { handle -> handle.map(::gone).orElse(true) })
    }

    @Test
    fun `cancelling a running call stops the program and the processes it started, then propagates`() = runBlocking {
        var outcome: Throwable? = null
        var result: ToolResult? = null
        val call = launch(Dispatchers.Default) {
            try {
                result = fs.tool().execute(arguments("argv" to listOf("./spawn.sh", "$pids")), fs.allowed())
            } catch (e: Throwable) {
                outcome = e
                throw e
            }
        }
        val processes = spawned()
        val start = TimeSource.Monotonic.markNow()

        call.cancel()
        withTimeout(5.seconds) { call.join() }

        assertTrue(start.elapsedNow() < 5.seconds)
        assertNull(result)
        assertIs<CancellationException>(outcome)
        assertTrue(processes.all(::gone))
    }
}
