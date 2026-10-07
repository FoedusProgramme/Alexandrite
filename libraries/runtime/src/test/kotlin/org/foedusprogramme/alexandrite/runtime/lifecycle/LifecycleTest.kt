package org.foedusprogramme.alexandrite.runtime.lifecycle

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import org.foedusprogramme.alexandrite.runtime.AlexandriteRuntime
import org.foedusprogramme.alexandrite.runtime.BLOCK_RETURNED
import org.foedusprogramme.alexandrite.runtime.BotIndex
import org.foedusprogramme.alexandrite.runtime.Events
import org.foedusprogramme.alexandrite.runtime.HOST_STOP
import org.foedusprogramme.alexandrite.runtime.PARENT_CANCELLED
import org.foedusprogramme.alexandrite.runtime.Probe
import org.foedusprogramme.alexandrite.runtime.RESTART
import org.foedusprogramme.alexandrite.runtime.RUN_CANCELLED
import org.foedusprogramme.alexandrite.runtime.Recorder
import org.foedusprogramme.alexandrite.runtime.RuntimeEvent
import org.foedusprogramme.alexandrite.runtime.RuntimeListener
import org.foedusprogramme.alexandrite.runtime.RuntimeProblemKind
import org.foedusprogramme.alexandrite.runtime.RuntimeSpec
import org.foedusprogramme.alexandrite.runtime.RuntimeStartException
import org.foedusprogramme.alexandrite.runtime.RuntimeState
import org.foedusprogramme.alexandrite.runtime.Service
import org.foedusprogramme.alexandrite.runtime.StartStage
import org.foedusprogramme.alexandrite.runtime.Termination
import org.foedusprogramme.alexandrite.runtime.TestIndex
import org.foedusprogramme.alexandrite.runtime.core
import org.foedusprogramme.alexandrite.runtime.execute
import org.foedusprogramme.alexandrite.runtime.explicit
import org.foedusprogramme.alexandrite.runtime.logged
import org.foedusprogramme.alexandrite.runtime.probe
import org.foedusprogramme.alexandrite.runtime.requested
import org.foedusprogramme.alexandrite.runtime.spec
import org.foedusprogramme.alexandrite.runtime.startFailure
import org.foedusprogramme.alexandrite.runtime.startVirtually
import org.foedusprogramme.alexandrite.runtime.started
import org.foedusprogramme.alexandrite.runtime.terminated
import org.foedusprogramme.alexandrite.runtime.virtual
import org.foedusprogramme.alexandrite.runtime.worker
import org.foedusprogramme.alexandrite.sdk.config.ConfigSource
import org.foedusprogramme.alexandrite.sdk.di.Lifecycle
import org.foedusprogramme.alexandrite.sdk.di.container.DiProblemKind
import org.foedusprogramme.alexandrite.sdk.di.container.binding
import org.foedusprogramme.alexandrite.sdk.di.key
import org.foedusprogramme.alexandrite.sdk.problem.Problem
import org.foedusprogramme.alexandrite.sdk.runtime.RuntimeControl
import org.foedusprogramme.alexandrite.sdk.runtime.StopRequest
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
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

@OptIn(ExperimentalCoroutinesApi::class)
class LifecycleTest {
    @TempDir
    lateinit var dataDir: Path

    private val events = Events()
    private val recorder = Recorder()

    private val lifecycle = listOf("create a", "start a", "open a", "close a", "drain a", "stop a", "destroy a")

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

    private fun cutShort(spec: RuntimeSpec, gate: Gate): RuntimeStartException {
        val parent = CoroutineScope(Job())
        return runBlocking(Dispatchers.Default) {
            val start = async { assertFailsWith<RuntimeStartException> { AlexandriteRuntime.start(spec, parent) } }
            gate.awaitEntered()
            parent.cancel()
            start.await()
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
        assertEquals(requested(StopRequest.failure(reason)), recorder.termination())
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

        assertIs<CancellationException>(runBlocking { withTimeout(10.seconds) { thrown.await() } })
        assertEquals(lifecycle.take(5) + "drained a" + lifecycle.drop(5), events.all())
        assertEquals(listOf("PluginsResolved", "Started", "Ready", "Stopping", "Stopped"), recorder.names())
        assertEquals(requested(RUN_CANCELLED), recorder.termination())
    }

    @Test
    fun `stop returns at once and the stop runs once the block has ended`() {
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
                    stop(RESTART)
                    events.record("requested")
                    awaitCancellation()
                }
            }
            draining.await()
            assertFalse(run.isCompleted)
            release.complete(Unit)
            run.await()
        }

        assertEquals(requested(RESTART), termination)
        assertEquals(lifecycle.take(3) + "requested" + lifecycle.drop(3), events.all())
    }

    @Test
    fun `a plugin's stop request through RuntimeControl cancels the block and names the plugin`() {
        val control = CompletableDeferred<RuntimeControl>()
        val failure = StopRequest.failure("disk full")
        val spec = spec(core(worker("a", "core", onStart = { control.complete(it) })), dataDir)
        lateinit var termination: Termination

        val lines = logged {
            termination = spec.execute {
                val plugin = control.await()
                thread { plugin.stop(failure) }
                try {
                    awaitCancellation()
                } catch (e: CancellationException) {
                    events.record("block cancelled")
                    throw e
                }
            }
        }

        assertEquals(requested(failure.from("core")), termination)
        assertEquals("core", termination.request.plugin)
        assertContains(lines, "INFO test: stopping: StopRequest(kind=FAILURE, reason=disk full, plugin=core)")
        assertEquals(listOf("block cancelled"), events.all())
    }

    @Test
    fun `each plugin has a control of its own`() {
        val controls = Collections.synchronizedList(mutableListOf<RuntimeControl>())
        val plugins = explicit(
            TestIndex("store", bindings = listOf(worker("db", "store", onStart = { controls += it }))),
            TestIndex("turns", bindings = listOf(worker("worker", "turns", onStart = { controls += it }))),
        )

        val termination = spec(plugins, dataDir).execute { controls.last().stop(RESTART) }

        assertEquals(2, controls.toSet().size)
        assertEquals(RESTART.from("turns"), termination.request)
    }

    @Test
    fun `a plugin that requests a stop from its own start cuts the start short and the run throws`() {
        val failure = StopRequest.failure("no disk")
        var ran = false
        val spec = spec(
            core(
                worker("a", "core", events, onStart = { it.stop(failure) }),
                worker("b", "core", events, listOf("a")),
            ),
            dataDir,
            listener = recorder,
        )

        val error = assertFailsWith<RuntimeStartException> { spec.execute { ran = true } }

        assertEquals(failure.from("core"), error.stopRequest)
        assertEquals(StartStage.START, error.stage)
        assertEquals(emptyList(), error.problems)
        assertFalse(ran)
        assertEquals(
            listOf("create a", "create b", "start a", "drain a", "stop a", "destroy b", "destroy a"),
            events.all(),
        )
        assertEquals(listOf("PluginsResolved", "Stopping", "Stopped"), recorder.names())
        assertEquals(requested(failure.from("core")), recorder.termination())
    }

    @Test
    fun `the first stop request wins and later ones are logged and ignored`() {
        val late = StopRequest.failure("drain failed")
        val spec = spec(core(worker("a", "core", onDrain = { it.stop(late) })), dataDir)
        lateinit var runtime: AlexandriteRuntime
        lateinit var termination: Termination

        val lines = logged {
            termination = spec.execute {
                runtime = this
                stop(RESTART)
            }
            runtime.stop()
        }

        assertEquals(requested(RESTART), termination)
        assertEquals(
            listOf(
                "INFO test: stopping: $RESTART",
                "INFO test: ignoring the stop request ${late.from("core")}: $RESTART came first",
                "INFO test: stopped",
                "INFO test: ignoring the stop request $HOST_STOP: $RESTART came first",
            ),
            lines.filterNot { it.startsWith("INFO test: loading plugins") },
        )
    }

    @Test
    fun `the state ends STOPPED when the stop runs to its end inside the request`() {
        val runtime = spec(core(worker("a", "core")), dataDir, dispatcher = Dispatchers.Unconfined).started()

        runtime.stop()

        assertEquals(requested(HOST_STOP), runtime.terminated())
        assertEquals(RuntimeState.STOPPED, runtime.state.value)
    }

    private class Blocked(val stage: StartStage, val events: List<String>, val spec: (Gate) -> RuntimeSpec)

    @Test
    fun `a stop cuts the start short at every stage, and the start throws without a warning`() {
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

        val lines = logged {
            for (case in cases) {
                recorder.events.clear()
                val gate = Gate().apply { armed = false }
                val spec = case.spec(gate)
                gate.armed = true

                val error = cutShort(spec, gate)

                assertEquals(PARENT_CANCELLED, error.stopRequest, "${case.stage}")
                assertEquals(case.stage, error.stage)
                assertEquals(requested(PARENT_CANCELLED), recorder.termination(), "${case.stage}")
                assertEquals(case.events, recorder.names(), "${case.stage}")
            }
        }

        assertEquals(emptyList(), lines.filter { it.startsWith("WARN") || it.startsWith("ERROR") })
    }

    @Test
    fun `a stop during OPEN closes the opened instances, then drains and stops the started ones`() {
        val gate = Gate()
        val spec = spec(
            core(worker("a", "core", events), worker("b", "core", events, listOf("a"), onOpen = { gate.hold() })),
            dataDir,
        )

        val error = cutShort(spec, gate)

        assertEquals(StartStage.OPEN, error.stage)
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
    fun `a failed start tears down what it built and throws without running the block`() {
        val spec = spec(
            core(
                worker("a", "core", events),
                worker("b", "core", events, listOf("a"), onOpen = { error("open b failed") }),
            ),
            dataDir,
            listener = recorder,
        )
        var ran = false

        val error = assertFailsWith<RuntimeStartException> { spec.execute { ran = true } }

        assertFalse(ran)
        assertEquals(StartStage.OPEN, error.stage)
        assertNull(error.stopRequest)
        assertEquals(
            "Cannot start runtime 'test': stage OPEN failed: java.lang.IllegalStateException: open b failed",
            error.message,
        )
        assertEquals(emptyList(), error.problems)
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
                worker("a", "core", events, onDrain = { it.stop(RESTART) }),
                worker("b", "core", events, onOpen = { error("open b failed") }),
            ),
            dataDir,
        )
        lateinit var error: RuntimeStartException

        val lines = logged { error = spec.startFailure() }

        assertEquals(StartStage.OPEN, error.stage)
        assertContains(lines, "INFO test: ignoring the stop request ${RESTART.from("core")}: its start failed")
    }

    // Problems while stopping.

    private class FailingClient(private val name: String) : AutoCloseable {
        override fun close() = error("close $name failed")
    }

    @Test
    fun `what fails while a failed start is torn down follows the problems that failed it`() {
        val client = binding(key<FailingClient>(), "core", "c") { FailingClient("c") }
        val spec =
            spec(explicit(TestIndex("core", bindings = listOf(client)), BotIndex()), dataDir, listener = recorder)

        val error = spec.startFailure()

        assertEquals(StartStage.GRAPH, error.stage)
        assertEquals(listOf(DiProblemKind.MISSING, RuntimeProblemKind.DESTROY_FAILED), error.problems.map { it.kind })
        assertEquals(
            "Destroying c (plugin core) failed: java.lang.IllegalStateException: close c failed",
            error.problems.last().message,
        )
        assertEquals(listOf("- ${error.problems.first().message}"), error.message!!.lines().drop(1))
        assertSame(error, assertIs<RuntimeEvent.StartFailed>(recorder.events.last()).error)
    }

    @Test
    fun `what fails while a start that a stop cut short is torn down is in the problems of both`() {
        val failure = StopRequest.failure("no disk")
        val spec = spec(
            core(
                worker("a", "core", onStart = { it.stop(failure) }, onStop = { error("stop a failed") }),
                worker("b", "core", dependencies = listOf("a")),
            ),
            dataDir,
            listener = recorder,
        )
        lateinit var error: RuntimeStartException

        val lines = logged { error = assertFailsWith<RuntimeStartException> { spec.execute() } }

        assertEquals(failure.from("core"), error.stopRequest)
        assertEquals(listOf(RuntimeProblemKind.STOP_FAILED), error.problems.map { it.kind })
        assertEquals(requested(failure.from("core"), error.problems), recorder.termination())
        assertContains(
            lines,
            "WARN test: Stopping a (plugin core) failed: java.lang.IllegalStateException: stop a failed",
        )
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
            "Destroying c (plugin core) failed: java.lang.IllegalStateException: close c failed",
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
        assertEquals(List(4) { "core" }, termination.problems.map { it.plugin })
        assertEquals(messages.map { "WARN test: $it" }, lines.filter { it.startsWith("WARN") })
    }

    @Test
    fun `a drain still running at the shutdown grace is cancelled and the later ones are skipped`() = runTest {
        val spec = spec(
            core(worker("a", "core", events), worker("b", "core", events, onDrain = { awaitCancellation() })),
            dataDir,
            shutdownGrace = 200.milliseconds,
            dispatcher = virtual(),
        )
        val runtime = startVirtually(spec)

        runtime.stop()
        val termination = runtime.join()

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
            ),
            termination.problems,
        )
        assertEquals(listOf("drain b"), events.all().filter { it.startsWith("drain") })
        assertEquals(200, currentTime)
    }

    @Test
    fun `a close still running at the shutdown grace is cancelled and nothing is drained after it`() = runTest {
        val spec = spec(
            core(worker("a", "core", events), worker("b", "core", events, onClose = { awaitCancellation() })),
            dataDir,
            shutdownGrace = 1.seconds,
            dispatcher = virtual(),
        )
        val runtime = startVirtually(spec)

        runtime.stop()
        val problems = runtime.join().problems

        assertEquals(
            listOf(
                RuntimeProblemKind.CLOSE_TIMED_OUT to "Closing b (plugin core) was cancelled: the shutdown grace of " +
                    "1s ran out.",
                RuntimeProblemKind.CLOSE_NOT_CALLED to "Skipped closing a (plugin core): the shutdown grace of 1s " +
                    "had run out.",
            ),
            problems.take(2).map { it.kind to it.message },
        )
        assertEquals(List(2) { RuntimeProblemKind.DRAIN_NOT_CALLED }, problems.drop(2).map { it.kind })
        assertEquals(listOf("stop b", "stop a", "destroy b", "destroy a"), events.all().takeLast(4))
    }

    // Errors.

    @Test
    fun `an Error from onStop or onDestroy is reported, and every instance is still torn down`() {
        val spec = spec(
            core(
                worker("a", "core", events),
                worker("b", "core", events, onStop = { TODO("stop b") }),
                binding(key<FailingTodo>(), "core", "todo") { FailingTodo(events) },
                worker("d", "core", events),
            ),
            dataDir,
        )

        val termination = spec.execute()

        assertEquals(
            listOf(RuntimeProblemKind.STOP_FAILED to "core", RuntimeProblemKind.DESTROY_FAILED to "core"),
            termination.problems.map { it.kind to it.plugin },
        )
        assertContains(termination.problems.first().message, "kotlin.NotImplementedError")
        assertEquals(
            listOf("stop d", "stop b", "stop a", "destroy d", "destroy todo", "destroy b", "destroy a"),
            events.all().filter { it.startsWith("stop") || it.startsWith("destroy") },
        )
        spec(core(), dataDir).execute()
    }

    private class FailingTodo(private val events: Events) : Lifecycle {
        override fun onDestroy() {
            events.record("destroy todo")
            TODO("destroy todo")
        }
    }

    @Test
    fun `an Error from a constructor fails the GRAPH stage and destroys what was created`() {
        val broken = binding(key<Service>("broken"), "core", "broken", dependencies = emptyList()) {
            throw NoClassDefFoundError("org/example/Missing")
        }
        val spec = spec(core(worker("a", "core", events), broken), dataDir)

        val error = spec.startFailure()

        assertEquals(StartStage.GRAPH, error.stage)
        assertEquals(listOf(DiProblemKind.CREATION_FAILED), error.problems.map { it.kind })
        assertEquals(listOf("create a", "destroy a"), events.all())
        spec(core(), dataDir).execute()
    }

    @Test
    fun `a VirtualMachineError from onStop is thrown once the teardown has run`() {
        val spec = spec(
            core(worker("a", "core", events), worker("b", "core", events, onStop = { throw StackOverflowError() })),
            dataDir,
        )

        assertFailsWith<StackOverflowError> { spec.execute() }

        assertEquals(listOf("stop b", "stop a", "destroy b", "destroy a"), events.all().takeLast(4))
        spec(core(), dataDir).execute()
    }

    @Test
    fun `a VirtualMachineError from a start is thrown once the start is torn down`() {
        val spec = spec(core(worker("a", "core", events, onStart = { throw StackOverflowError() })), dataDir)

        assertFailsWith<StackOverflowError> { spec.execute() }

        assertEquals(listOf("create a", "start a", "destroy a"), events.all())
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

    @Test
    fun `the run block cannot join its own runtime`() {
        lateinit var error: IllegalStateException

        spec(core(), dataDir).execute { error = assertFailsWith<IllegalStateException> { join() } }

        assertEquals("Runtime 'test' cannot be joined from its run block.", error.message)
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
            stop()
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
