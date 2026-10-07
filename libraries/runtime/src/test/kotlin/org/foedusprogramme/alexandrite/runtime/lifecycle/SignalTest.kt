package org.foedusprogramme.alexandrite.runtime.lifecycle

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.foedusprogramme.alexandrite.runtime.AlexandriteRuntime
import org.foedusprogramme.alexandrite.runtime.Events
import org.foedusprogramme.alexandrite.runtime.Recorder
import org.foedusprogramme.alexandrite.runtime.RuntimeSpec
import org.foedusprogramme.alexandrite.runtime.RuntimeStartException
import org.foedusprogramme.alexandrite.runtime.StartStage
import org.foedusprogramme.alexandrite.runtime.Termination
import org.foedusprogramme.alexandrite.runtime.core
import org.foedusprogramme.alexandrite.runtime.hang
import org.foedusprogramme.alexandrite.runtime.service
import org.foedusprogramme.alexandrite.runtime.spec
import org.foedusprogramme.alexandrite.runtime.worker
import org.foedusprogramme.alexandrite.sdk.runtime.StopRequest
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import sun.misc.Signal
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class SignalTest {
    @TempDir
    lateinit var dataDir: Path

    private val events = Events()

    private fun shutdown(reason: String) = Termination(StopRequest.shutdown(reason), emptyList())

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
        hookMargin: Duration = 5.seconds,
        whileRunning: suspend () -> Unit = {},
    ): Termination = runBlocking {
        val run = async(Dispatchers.Default) { runUntilSignal(spec, block, signals, hookMargin) }
        whileRunning()
        run.await()
    }

    private fun untilStopped(ready: CompletableDeferred<Unit>): suspend AlexandriteRuntime.() -> Unit = {
        ready.complete(Unit)
        awaitCancellation()
    }

    // Through the seam.

    @Test
    fun `the first signal requests a SHUTDOWN stop and the trap is closed afterwards`() {
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
    fun `a signal while starting stops the start, which throws`() {
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

        val error = assertFailsWith<RuntimeStartException> {
            signalled(spec, signals, { ran = true }) {
                starting.await()
                signals.raise("TERM", 15)
            }
        }

        assertEquals(StopRequest.shutdown("received SIGTERM"), error.stopRequest)
        assertEquals(StartStage.START, error.stage)
        assertFalse(ran)
        assertNull(signals.handler)
    }

    @Test
    fun `a signal before the start stops the runtime without starting it`() {
        val signals = FakeSignals(early = HostSignal("TERM", 15))

        val error = assertFailsWith<RuntimeStartException> {
            signalled(spec(core(service("a", "core", events)), dataDir), signals)
        }

        assertEquals(StopRequest.shutdown("received SIGTERM"), error.stopRequest)
        assertEquals(StartStage.DATA_DIR, error.stage)
        assertEquals(emptyList(), events.all())
    }

    @Test
    fun `a failed start throws and gives the signals back`() {
        val signals = FakeSignals()
        val spec = spec(core(service("a", "core", events) { error("no start") }), dataDir)

        val error = assertFailsWith<RuntimeStartException> { signalled(spec, signals) }

        assertEquals(StartStage.START, error.stage)
        assertNull(error.stopRequest)
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

    @Test
    fun `the shutdown hook stops waiting once the grace and its margin have passed`() {
        val signals = FakeSignals(trappable = false)
        val ready = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val spec = spec(core(worker("a", "core", onStop = { release.await() })), dataDir, shutdownGrace = 0.seconds)

        val termination = signalled(spec, signals, untilStopped(ready), hookMargin = 0.seconds) {
            ready.await()
            val hook = thread(block = checkNotNull(signals.hook))
            hook.join(TimeUnit.SECONDS.toMillis(10))
            assertFalse(hook.isAlive)
            release.countDown()
        }

        assertEquals(StopRequest.shutdown("the JVM is shutting down"), termination.request)
    }

    // The JVM's handlers.

    @Test
    fun `traps share the JVM's handlers, each gets every signal and the last one restores them`() {
        assumeFalse(System.getProperty("os.name").startsWith("Windows"), "no POSIX signals")
        val interrupt = Signal("INT")
        val before = LinkedBlockingQueue<String>()
        val original = Signal.handle(interrupt) { before += it.name }
        try {
            val first = LinkedBlockingQueue<String>()
            val second = LinkedBlockingQueue<String>()
            val a = JvmSignals.trap { first += it.name }
            assumeTrue(a != null, "this JVM cannot handle signals")
            val b = checkNotNull(JvmSignals.trap { second += it.name })

            Signal.raise(interrupt)
            assertEquals("INT", second.poll(10, TimeUnit.SECONDS))
            assertEquals(listOf("INT"), first.toList())
            a!!.close()
            Signal.raise(interrupt)
            assertEquals("INT", second.poll(10, TimeUnit.SECONDS))
            b.close()
            Signal.raise(interrupt)

            assertEquals("INT", before.poll(10, TimeUnit.SECONDS))
            assertEquals(listOf("INT"), first.toList())
            assertEquals(emptyList(), second.toList() + before.toList())
        } finally {
            Signal.handle(interrupt, original)
        }
    }

    @Test
    fun `a shutdown hook can be removed more than once`() {
        val hook = JvmSignals.onShutdown {}

        hook.close()
        hook.close()
    }

    // Real signals.

    private class Target(private val process: Process) : AutoCloseable {
        private val output = LinkedBlockingQueue<String>()
        private val lines = mutableListOf<String>()
        private val reader = thread(isDaemon = true) {
            process.inputReader().useLines { it.forEach(output::add) }
            output.add(END)
        }

        val pid: Long get() = process.pid()

        fun awaitLine(line: String) {
            while (line !in lines) {
                val next = output.poll(30, TimeUnit.SECONDS) ?: error("no '$line' within 30 s: $lines")
                if (next === END) error("the target ended before '$line': $lines")
                lines += next
            }
        }

        fun terminate() = process.toHandle().destroy()

        fun exitValue(): Int {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "the target did not end: $lines")
            reader.join(TimeUnit.SECONDS.toMillis(10))
            output.filterNot { it === END }.forEach { lines += it }
            return process.exitValue()
        }

        fun lines(): List<String> = lines

        override fun close() {
            process.destroyForcibly()
        }

        private companion object {
            val END = String()
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
