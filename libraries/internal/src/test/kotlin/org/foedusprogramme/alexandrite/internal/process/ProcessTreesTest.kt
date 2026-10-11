package org.foedusprogramme.alexandrite.internal.process

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

@DisabledOnOs(OS.WINDOWS)
class ProcessTreesTest {
    private val started = mutableListOf<ProcessHandle>()

    @AfterTest
    fun tearDown() {
        started.forEach { it.destroyForcibly() }
    }

    /** A shell that starts a background `sleep`, with the shell's handle and the sleep's. */
    private fun spawner(script: String): Pair<Process, ProcessHandle> {
        val process = ProcessBuilder("/bin/sh", "-c", script).start()
        started += process.toHandle()
        val child = process.inputStream.bufferedReader().readLine().trim().toLong()
        val handle = ProcessHandle.of(child).orElseThrow()
        started += handle
        return process to handle
    }

    private fun gone(handle: ProcessHandle): Boolean {
        val deadline = TimeSource.Monotonic.markNow() + 5.seconds
        while (handle.isAlive && deadline.hasNotPassedNow()) Thread.sleep(10)
        return !handle.isAlive
    }

    @Test
    fun `a process and the process it started both end`() = runBlocking {
        val (process, child) = spawner("sleep 300 & echo \$!; wait")

        destroyTree(process, 2.seconds)

        assertEquals(true to true, gone(process.toHandle()) to gone(child))
    }

    @Test
    fun `processes that ignore the request to stop are killed after the grace`() = runBlocking {
        val (process, child) = spawner("trap '' TERM; sleep 300 & echo \$!; wait")
        val start = TimeSource.Monotonic.markNow()

        destroyTree(process, 300.milliseconds)

        assertTrue(start.elapsedNow() >= 300.milliseconds)
        assertEquals(true to true, gone(process.toHandle()) to gone(child))
    }
}
