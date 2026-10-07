package org.foedusprogramme.alexandrite.runtime.lifecycle

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.foedusprogramme.alexandrite.runtime.AlexandriteRuntime
import org.foedusprogramme.alexandrite.runtime.BLOCK_RETURNED
import org.foedusprogramme.alexandrite.runtime.Events
import org.foedusprogramme.alexandrite.runtime.HOST_STOP
import org.foedusprogramme.alexandrite.runtime.Probe
import org.foedusprogramme.alexandrite.runtime.RUN_CANCELLED
import org.foedusprogramme.alexandrite.runtime.Recorder
import org.foedusprogramme.alexandrite.runtime.RuntimeEvent
import org.foedusprogramme.alexandrite.runtime.RuntimeListener
import org.foedusprogramme.alexandrite.runtime.RuntimeProblemKind
import org.foedusprogramme.alexandrite.runtime.RuntimeSpec
import org.foedusprogramme.alexandrite.runtime.RuntimeStartException
import org.foedusprogramme.alexandrite.runtime.Service
import org.foedusprogramme.alexandrite.runtime.StartStage
import org.foedusprogramme.alexandrite.runtime.Termination
import org.foedusprogramme.alexandrite.runtime.TestIndex
import org.foedusprogramme.alexandrite.runtime.execute
import org.foedusprogramme.alexandrite.runtime.explicit
import org.foedusprogramme.alexandrite.runtime.logged
import org.foedusprogramme.alexandrite.runtime.probe
import org.foedusprogramme.alexandrite.runtime.requested
import org.foedusprogramme.alexandrite.runtime.spec
import org.foedusprogramme.alexandrite.runtime.startFailure
import org.foedusprogramme.alexandrite.runtime.terminated
import org.foedusprogramme.alexandrite.runtime.worker
import org.foedusprogramme.alexandrite.sdk.config.ConfigSource
import org.foedusprogramme.alexandrite.sdk.di.container.Binding
import org.foedusprogramme.alexandrite.sdk.di.container.binding
import org.foedusprogramme.alexandrite.sdk.di.key
import org.foedusprogramme.alexandrite.sdk.problem.Problem
import org.foedusprogramme.alexandrite.sdk.runtime.RuntimeControl
import org.foedusprogramme.alexandrite.sdk.runtime.StopKind
import org.foedusprogramme.alexandrite.sdk.runtime.StopRequest
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.measureTime

@Timeout(60)
class LifecycleTest {
    @TempDir
    lateinit var dataDir: Path

    private val events = Events()
    private val recorder = Recorder()

    private val restart = StopRequest(StopKind.RESTART, "update")

    private val lifecycle = listOf("create a", "start a", "open a", "close a", "drain a", "stop a", "destroy a")

    private fun core(vararg bindings: Binding<*>) = explicit(TestIndex("core", bindings = bindings.toList()))

    /** Holds whoever passes it until they are interrupted or cancelled. */
    private class Gate {
        private val entered = CountDownLatch(1)

        @Volatile
        var armed = true

        fun block() {
            if (!armed) return
            entered.countDown()
            Thread.sleep(Long.MAX_VALUE)
        }

        suspend fun hold() {
            entered.countDown()
            awaitCancellation()
        }

        fun awaitEntered() = assertTrue(entered.await(5, TimeUnit.SECONDS), "nothing reached the gate")
    }

    private class GatedIndex(private val gate: Gate) : TestIndex("gated") {
        override val configRoot: String
            get() {
                gate.block()
                return "plugins.gated"
            }
    }

    private class GatedSource(private val gate: Gate) : ConfigSource {
        override fun tree(path: String): JsonObject? {
            gate.block()
            return null
        }
    }

    // Start and stop.

    @Test
    fun `a run starts, runs the block once ready and stops with SHUTDOWN when the block returns`() {
        val spec = spec(core(worker("a", "core", events)), dataDir, listener = recorder)

        val termination = spec.execute { events.record("block") }

        assertEquals(lifecycle.take(3) + "block" + lifecycle.drop(3), events.all())
        assertEquals(listOf("PluginsResolved", "Started", "Ready", "Stopping", "Stopped"), recorder.names())
        assertEquals(requested(BLOCK_RETURNED), termination)
        assertSame(termination, recorder.termination())
    }

    @Test
    fun `the stop closes, drains, stops and destroys in reverse`() {
        val plugins = explicit(
            TestIndex("store", bindings = listOf(worker("db", "store", events))),
            TestIndex("turns", bindings = listOf(worker("worker", "turns", events, listOf("db")))),
        )

        spec(plugins, dataDir).execute()

        assertEquals(
            listOf(
                "create db", "create worker",
                "start db", "start worker",
                "open db", "open worker",
                "close worker", "close db",
                "drain worker", "drain db",
                "stop worker", "stop db",
                "destroy worker", "destroy db",
            ),
            events.all(),
        )
    }

    @Test
    fun `a block that throws stops the runtime with FAILURE, then run rethrows the error`() {
        val spec = spec(core(worker("a", "core", events)), dataDir, listener = recorder)

        val thrown = assertFailsWith<IllegalStateException> { spec.execute { error("block failed") } }

        assertEquals("block failed", thrown.message)
        val reason = "the run block failed: java.lang.IllegalStateException: block failed"
        assertEquals(requested(StopRequest(StopKind.FAILURE, reason)), recorder.termination())
        assertEquals(lifecycle, events.all())
    }

    @Test
    fun `a cancelled caller gets the CancellationException once the stop ran without being cancelled`() {
        val ready = CompletableDeferred<Unit>()
        val thrown = CompletableDeferred<Throwable>()
        val worker = worker(
            "a",
            "core",
            events,
            onDrain = {
                delay(100.milliseconds)
                events.record("drained a")
            },
        )
        val spec = spec(core(worker), dataDir, listener = recorder)

        runBlocking {
            val caller = launch {
                try {
                    AlexandriteRuntime.run(spec) {
                        ready.complete(Unit)
                        awaitCancellation()
                    }
                } catch (e: Throwable) {
                    thrown.complete(e)
                    throw e
                }
            }
            ready.await()
            caller.cancelAndJoin()
        }

        assertIs<CancellationException>(runBlocking { thrown.await() })
        assertEquals(lifecycle.take(5) + "drained a" + lifecycle.drop(5), events.all())
        assertEquals(listOf("PluginsResolved", "Started", "Ready", "Stopping", "Stopped"), recorder.names())
        assertEquals(requested(RUN_CANCELLED), recorder.termination())
    }

    @Test
    fun `requestStop returns at once and the stop runs once the block has ended`() {
        val draining = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val worker = worker(
            "a",
            "core",
            events,
            onDrain = {
                draining.complete(Unit)
                release.await()
            },
        )
        val spec = spec(core(worker), dataDir)

        val termination = runBlocking {
            val run = async(Dispatchers.Default) {
                AlexandriteRuntime.run(spec) {
                    requestStop(restart)
                    events.record("requested")
                    awaitCancellation()
                }
            }
            draining.await()
            assertFalse(run.isCompleted)
            release.complete(Unit)
            run.await()
        }

        assertEquals(requested(restart), termination)
        assertEquals(lifecycle.take(3) + "requested" + lifecycle.drop(3), events.all())
    }

    @Test
    fun `a plugin's stop request through RuntimeControl cancels the block`() {
        val control = CompletableDeferred<RuntimeControl>()
        val failure = StopRequest(StopKind.FAILURE, "disk full")
        val spec = spec(core(worker("a", "core", onStart = { control.complete(it) })), dataDir)

        val termination = spec.execute {
            val plugin = control.await()
            thread { plugin.requestStop(failure) }
            try {
                awaitCancellation()
            } catch (e: CancellationException) {
                events.record("block cancelled")
                throw e
            }
        }

        assertEquals(requested(failure), termination)
        assertEquals(listOf("block cancelled"), events.all())
    }

    @Test
    fun `a plugin that requests a stop from its own start cuts the start short`() {
        val failure = StopRequest(StopKind.FAILURE, "no disk")
        var ran = false
        val spec = spec(
            core(
                worker("a", "core", events, onStart = { it.requestStop(failure) }),
                worker("b", "core", events, listOf("a")),
            ),
            dataDir,
            listener = recorder,
        )

        val termination = spec.execute { ran = true }

        assertEquals(requested(failure), termination)
        assertFalse(ran)
        assertEquals(listOf("create a", "create b", "start a", "stop a", "destroy b", "destroy a"), events.all())
        assertEquals(listOf("PluginsResolved", "Stopping", "Stopped"), recorder.names())
    }

    @Test
    fun `the first stop request wins and later ones are logged and ignored`() {
        val late = StopRequest(StopKind.FAILURE, "drain failed")
        val spec = spec(core(worker("a", "core", onDrain = { it.requestStop(late) })), dataDir)
        lateinit var runtime: AlexandriteRuntime
        lateinit var termination: Termination

        val lines = logged {
            termination = spec.execute {
                runtime = this
                requestStop(restart)
            }
            runtime.requestStop()
        }

        assertEquals(requested(restart), termination)
        assertEquals(
            listOf(
                "INFO test: stopping: $restart",
                "INFO test: ignoring the stop request $late: $restart came first",
                "INFO test: stopped",
                "INFO test: ignoring the stop request $HOST_STOP: $restart came first",
            ),
            lines.filterNot { it.startsWith("INFO test: loading plugins") },
        )
    }

    private class Blocked(val stage: StartStage, val events: List<String>, val spec: (Gate) -> RuntimeSpec)

    @Test
    fun `a stop cuts the start short at every stage and the runtime never gets ready`() {
        val cases = listOf(
            Blocked(StartStage.PLUGINS, listOf("Stopping", "Stopped")) { gate ->
                spec(explicit(GatedIndex(gate)), dataDir, listener = recorder)
            },
            Blocked(StartStage.CONFIG, listOf("Stopping", "Stopped")) { gate ->
                spec(core(), dataDir, listener = recorder, source = GatedSource(gate))
            },
            Blocked(StartStage.GRAPH, listOf("PluginsResolved", "Stopping", "Stopped")) { gate ->
                val slow = binding(key<Service>("slow"), "core", "slow") {
                    gate.block()
                    Service("slow", events) {}
                }
                spec(core(slow), dataDir, listener = recorder)
            },
            Blocked(StartStage.START, listOf("PluginsResolved", "Stopping", "Stopped")) { gate ->
                spec(core(worker("a", "core", onStart = { gate.hold() })), dataDir, listener = recorder)
            },
            Blocked(StartStage.OPEN, listOf("PluginsResolved", "Started", "Stopping", "Stopped")) { gate ->
                spec(core(worker("a", "core", onOpen = { gate.hold() })), dataDir, listener = recorder)
            },
        )

        for (case in cases) {
            recorder.events.clear()
            val gate = Gate().apply { armed = false }
            val spec = case.spec(gate)
            gate.armed = true
            val runtime = AlexandriteRuntime.launch(spec)

            gate.awaitEntered()
            runtime.requestStop(restart)

            assertEquals(requested(restart), runtime.terminated(), "${case.stage}")
            assertFalse(runBlocking { runtime.awaitReady() }, "${case.stage}")
            assertEquals(case.events, recorder.names(), "${case.stage}")
        }
    }

    @Test
    fun `a stop during OPEN closes the opened instances, then drains and stops the started ones`() {
        val gate = Gate()
        val runtime = AlexandriteRuntime.launch(
            spec(
                core(worker("a", "core", events), worker("b", "core", events, listOf("a"), onOpen = { gate.hold() })),
                dataDir,
            ),
        )

        gate.awaitEntered()
        runtime.requestStop()

        assertEquals(requested(HOST_STOP), runtime.terminated())
        assertEquals(
            listOf(
                "create a", "create b",
                "start a", "start b",
                "open a", "open b",
                "close a",
                "drain b", "drain a",
                "stop b", "stop a",
                "destroy b", "destroy a",
            ),
            events.all(),
        )
    }

    // Failed start.

    @Test
    fun `a failed start tears down what it built and returns StartFailed without running the block`() {
        val spec = spec(
            core(
                worker("a", "core", events),
                worker("b", "core", events, listOf("a"), onOpen = { error("open b failed") }),
            ),
            dataDir,
            listener = recorder,
        )
        var ran = false

        val termination = spec.execute { ran = true }

        val error = assertIs<Termination.Cause.StartFailed>(termination.cause).error
        assertFalse(ran)
        assertEquals(StartStage.OPEN, error.stage)
        assertEquals(
            "Cannot start runtime 'test': stage OPEN failed: java.lang.IllegalStateException: open b failed",
            error.message,
        )
        assertEquals(emptyList(), termination.problems)
        assertEquals(
            listOf(
                "create a", "create b",
                "start a", "start b",
                "open a", "open b",
                "close a",
                "drain b", "drain a",
                "stop b", "stop a",
                "destroy b", "destroy a",
            ),
            events.all(),
        )
        assertEquals(listOf("PluginsResolved", "Started", "StartFailed"), recorder.names())
        assertSame(error, assertIs<RuntimeEvent.StartFailed>(recorder.events.last()).error)
        spec(core(), dataDir).execute()
    }

    @Test
    fun `a stop request while a failed start tears down is ignored`() {
        val spec = spec(
            core(
                worker("a", "core", events, onDrain = { it.requestStop(restart) }),
                worker("b", "core", events, onOpen = { error("open b failed") }),
            ),
            dataDir,
        )
        lateinit var error: RuntimeStartException

        val lines = logged { error = spec.startFailure() }

        assertEquals(StartStage.OPEN, error.stage)
        assertContains(lines, "INFO test: ignoring the stop request $restart: its start failed")
    }

    // Problems while stopping.

    private class FailingClient(private val name: String) : AutoCloseable {
        override fun close() = error("close $name failed")
    }

    @Test
    fun `what fails while stopping is reported in the termination and logged`() {
        val spec = spec(
            core(
                worker("a", "core", onClose = { error("close a failed") }),
                worker("b", "core", onDrain = { error("drain b failed") }, onStop = { error("stop b failed") }),
                binding(key<FailingClient>(), "core", "c") { FailingClient("c") },
            ),
            dataDir,
        )
        lateinit var termination: Termination

        val lines = logged { termination = spec.execute() }

        val messages = listOf(
            "Closing a (plugin core) failed: java.lang.IllegalStateException: close a failed",
            "Draining b (plugin core) failed: java.lang.IllegalStateException: drain b failed",
            "Stopping b (plugin core) failed: java.lang.IllegalStateException: stop b failed",
            "Destroying an instance failed: java.lang.IllegalStateException: close c failed",
        )
        assertEquals(
            listOf(
                RuntimeProblemKind.CLOSE_FAILED,
                RuntimeProblemKind.DRAIN_FAILED,
                RuntimeProblemKind.STOP_FAILED,
                RuntimeProblemKind.DESTROY_FAILED,
            ),
            termination.problems.map { it.kind },
        )
        assertEquals(messages, termination.problems.map { it.message })
        assertEquals(listOf("core", "core", "core", null), termination.problems.map { it.plugin })
        assertEquals(
            messages.dropLast(1).map { "WARN test: $it" } + "WARN test: destroying an instance failed",
            lines.filter { it.startsWith("WARN") },
        )
    }

    @Test
    fun `a drain still running at the shutdown grace is cancelled and the later ones are skipped`() {
        val spec = spec(
            core(worker("a", "core", events), worker("b", "core", events, onDrain = { awaitCancellation() })),
            dataDir,
            shutdownGrace = 200.milliseconds,
        )

        val termination = spec.execute()

        assertEquals(
            listOf(
                Problem(
                    RuntimeProblemKind.DRAIN_TIMED_OUT,
                    "Draining b (plugin core) was cancelled: the shutdown grace of 200ms ran out.",
                    "core",
                    null,
                ),
                Problem(
                    RuntimeProblemKind.DRAIN_NOT_CALLED,
                    "Skipped draining a (plugin core): the shutdown grace of 200ms had run out.",
                    "core",
                    null,
                ),
                Problem(
                    RuntimeProblemKind.DRAIN_NOT_CALLED,
                    "Skipped draining Hooks (plugin alexandrite-runtime): the shutdown grace of 200ms had run out.",
                    "alexandrite-runtime",
                    null,
                ),
            ),
            termination.problems,
        )
        assertEquals(listOf("drain b"), events.all().filter { it.startsWith("drain") })
    }

    // Termination.

    @Test
    fun `run returns once torn down and its final event was delivered`() {
        val listener = RuntimeListener { event ->
            if (event is RuntimeEvent.Stopped) Thread.sleep(200)
            recorder.onEvent(event)
        }
        val spec = spec(core(worker("a", "core", onDrain = { delay(300.milliseconds) })), dataDir, listener = listener)

        val elapsed = measureTime { spec.execute() }

        assertTrue(elapsed >= 500.milliseconds, "run returned after $elapsed")
        assertEquals("Stopped", recorder.names().last())
    }

    // Services.

    @Test
    fun `services resolve until a stop is requested`() {
        lateinit var runtime: AlexandriteRuntime
        val seen = Collections.synchronizedList(mutableListOf<String>())
        fun record(call: String) {
            seen += "$call: " + runCatching { runtime.services.get(key<Probe>()) }.fold({ "resolved" }, { it.message })
        }
        val spec = spec(core(probe("core"), worker("a", "core", onDrain = { record("drain") })), dataDir)

        spec.execute {
            runtime = this
            val held = services
            record("ready")
            requestStop()
            record("requested")
            seen += "held: " + runCatching { held.get(key<Probe>()) }.fold({ "resolved" }, { it.message })
        }
        record("ended")

        val unavailable = "Runtime 'test' has no services: a stop was requested."
        assertEquals(
            listOf("ready: resolved") + listOf("requested", "held", "drain", "ended").map { "$it: $unavailable" },
            seen,
        )
    }
}
