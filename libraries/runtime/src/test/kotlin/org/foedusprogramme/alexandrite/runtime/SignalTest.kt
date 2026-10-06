package org.foedusprogramme.alexandrite.runtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.foedusprogramme.alexandrite.runtime.Termination.Cause
import org.foedusprogramme.alexandrite.sdk.di.container.Binding
import org.foedusprogramme.alexandrite.sdk.runtime.StopKind
import org.foedusprogramme.alexandrite.sdk.runtime.StopRequest
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.BufferedReader
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Timeout(60)
class SignalTest {
    @TempDir
    lateinit var dataDir: Path

    private val events = Events()

    private fun core(vararg bindings: Binding<*>) = explicit(TestIndex("core", bindings = bindings.toList()))

    private fun shutdown(reason: String) =
        Termination(Cause.Requested(StopRequest(StopKind.SHUTDOWN, reason)), emptyList())

    private class FakeSignals(private val trappable: Boolean = true, private val early: HostSignal? = null) :
        Signals {
        @Volatile
        var handler: ((HostSignal) -> Unit)? = null

        @Volatile
        var hook: (() -> Unit)? = null

        val halted: MutableList<Int> = CopyOnWriteArrayList()

        override fun trap(handler: (HostSignal) -> Unit): AutoCloseable? {
            if (!trappable) return null
            this.handler = handler
            early?.let(handler)
            return AutoCloseable { this.handler = null }
        }

        override fun onShutdown(hook: () -> Unit): AutoCloseable {
            this.hook = hook
            return AutoCloseable { this.hook = null }
        }

        override fun halt(status: Int) {
            halted += status
        }

        fun raise(name: String, number: Int) = checkNotNull(handler) { "no signal handler" }(HostSignal(name, number))
    }

    private fun signalled(
        spec: RuntimeSpec,
        signals: Signals,
        block: suspend AlexandriteRuntime.() -> Unit = { awaitCancellation() },
        whileRunning: suspend () -> Unit = {},
    ): Termination = runBlocking {
        val run = async(Dispatchers.Default) { runUntilSignal(spec, block, signals) }
        whileRunning()
        run.await()
    }

    private fun untilStopped(ready: CompletableDeferred<Unit>): suspend AlexandriteRuntime.() -> Unit = {
        ready.complete(Unit)
        awaitCancellation()
    }

    // Through the seam.

    @Test
    fun `the first signal requests a SHUTDOWN stop and the previous handlers are back afterwards`() {
        val signals = FakeSignals()
        val ready = CompletableDeferred<Unit>()
        val spec = spec(core(service("a", "core", events)), dataDir)

        val termination = signalled(spec, signals, untilStopped(ready)) {
            ready.await()
            signals.raise("TERM", 15)
        }

        assertEquals(shutdown("received SIGTERM"), termination)
        assertEquals(listOf("create a", "start a", "stop a", "destroy a"), events.all())
        assertNull(signals.handler)
        assertEquals(emptyList(), signals.halted)
    }

    @Test
    fun `any signal after the first halts with 128 plus its number`() {
        val signals = FakeSignals()
        val draining = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val worker = worker("w", "core", events, onDrain = {
            draining.complete(Unit)
            release.await()
        })

        val ready = CompletableDeferred<Unit>()

        val termination = signalled(spec(core(worker), dataDir), signals, untilStopped(ready)) {
            ready.await()
            signals.raise("INT", 2)
            draining.await()
            signals.raise("TERM", 15)
            signals.raise("INT", 2)
            assertEquals(listOf(143, 130), signals.halted)
            release.complete(Unit)
        }

        assertEquals(shutdown("received SIGINT"), termination)
    }

    @Test
    fun `a signal while starting stops the start`() {
        val signals = FakeSignals()
        val starting = CompletableDeferred<Unit>()
        var ran = false
        val spec = spec(
            core(
                service("a", "core", events) {
                    starting.complete(Unit)
                    hang()
                },
            ),
            dataDir,
        )

        val termination = signalled(spec, signals, { ran = true }) {
            starting.await()
            signals.raise("TERM", 15)
        }

        assertEquals(shutdown("received SIGTERM"), termination)
        assertFalse(ran)
    }

    @Test
    fun `a signal before the start stops the runtime without starting it`() {
        val signals = FakeSignals(early = HostSignal("TERM", 15))
        val termination = signalled(spec(core(service("a", "core", events)), dataDir), signals)

        assertEquals(shutdown("received SIGTERM"), termination)
        assertEquals(emptyList(), events.all())
    }

    @Test
    fun `a failed start returns its termination instead of throwing`() {
        val signals = FakeSignals()
        val spec = spec(core(service("a", "core", events) { error("no start") }), dataDir)

        val termination = signalled(spec, signals)

        val cause = assertIs<Cause.StartFailed>(termination.cause)
        assertEquals(StartStage.START, cause.error.stage)
        assertNull(signals.handler)
    }

    @Test
    fun `without signal handlers a shutdown hook requests the stop and waits for the termination`() {
        val signals = FakeSignals(trappable = false)
        val recorder = Recorder()
        val ready = CompletableDeferred<Unit>()
        var afterHook: List<String>? = null

        val termination =
            signalled(spec(core(), dataDir, listener = recorder), signals, untilStopped(ready)) {
                ready.await()
                val hook = checkNotNull(signals.hook)
                thread {
                    hook()
                    afterHook = recorder.names()
                }.join()
            }

        assertEquals(shutdown("the JVM is shutting down"), termination)
        assertEquals("Stopped", afterHook?.last())
        assertNull(signals.hook)
    }

    // Real signals.

    private class Target(private val process: Process) : AutoCloseable {
        private val output: BufferedReader = process.inputReader()
        private val lines = mutableListOf<String>()

        val pid: Long get() = process.pid()

        fun awaitLine(line: String) {
            while (line !in lines) lines += output.readLine() ?: error("the target ended before '$line': $lines")
        }

        fun terminate() = process.toHandle().destroy()

        fun exitValue(): Int {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "the target did not end: $lines")
            output.lineSequence().forEach { lines += it }
            return process.exitValue()
        }

        fun lines(): List<String> = lines

        override fun close() {
            process.destroyForcibly()
        }
    }

    private fun target(vararg args: String): Target {
        assumeFalse(System.getProperty("os.name").startsWith("Windows"), "no POSIX signals")
        val java = Path.of(System.getProperty("java.home"), "bin", "java")
        assumeTrue(Files.isExecutable(java), "no java executable at $java")
        val command = listOf(java.toString(), "-cp", System.getProperty("java.class.path"), TARGET) + args
        return try {
            Target(ProcessBuilder(command).redirectErrorStream(true).start())
        } catch (e: IOException) {
            assumeTrue(false, "cannot start a JVM: $e")
            error("unreachable")
        }
    }

    private fun sendInterrupt(pid: Long) {
        val kill = try {
            ProcessBuilder("kill", "-INT", "$pid").start()
        } catch (e: IOException) {
            assumeTrue(false, "cannot run kill: $e")
            return
        }
        assertEquals(0, kill.waitFor())
    }

    @Test
    fun `SIGTERM stops a runtime run until a signal and its handlers`() {
        target(dataDir.toString()).use { target ->
            target.awaitLine("ready")

            target.terminate()

            assertEquals(0, target.exitValue())
            assertContains(target.lines(), "stopped: received SIGTERM")
        }
    }

    @Test
    fun `SIGTERM after SIGINT halts a runtime that is still draining with status 143`() {
        target(dataDir.toString(), "hang").use { target ->
            target.awaitLine("ready")
            sendInterrupt(target.pid)
            target.awaitLine("stopping: received SIGINT")

            target.terminate()

            assertEquals(143, target.exitValue())
        }
    }

    private companion object {
        const val TARGET = "org.foedusprogramme.alexandrite.runtime.SignalTargetKt"
    }
}
