package org.foedusprogramme.alexandrite.runtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.foedusprogramme.alexandrite.runtime.RuntimeEvent.Ready
import org.foedusprogramme.alexandrite.sdk.config.ConfigSource
import org.foedusprogramme.alexandrite.sdk.di.Binding
import org.foedusprogramme.alexandrite.sdk.di.binding
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
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    private fun core(vararg bindings: Binding<*>) = explicit(TestIndex("core", bindings = bindings.toList()))

    private fun requested(request: StopRequest, problems: List<Problem> = emptyList()) =
        Termination(Termination.Cause.Requested(request), problems)

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
    fun `each lifecycle call sees the state of its stage and the events follow the states`() {
        lateinit var runtime: AlexandriteRuntime
        val seen = Collections.synchronizedList(mutableListOf<String>())
        fun record(call: String) {
            seen += "$call ${runtime.state.value}"
        }
        runtime = runtime(
            core(
                worker(
                    "a",
                    "core",
                    events,
                    onStart = { record("start") },
                    onOpen = { record("open") },
                    onClose = { record("close") },
                    onDrain = { record("drain") },
                ),
            ),
            dataDir,
            listener = recorder,
        )

        val before = runtime.state.value
        runtime.started()
        val ready = runtime.state.value
        val termination = runtime.stopped()

        assertEquals(listOf(RuntimeState.NEW, RuntimeState.READY), listOf(before, ready))
        assertEquals(RuntimeState.STOPPED, runtime.state.value)
        assertEquals(listOf("start STARTING", "open STARTING", "close STOPPING", "drain STOPPING"), seen)
        assertEquals(
            listOf("create a", "start a", "open a", "close a", "drain a", "stop a", "destroy a"),
            events.all(),
        )
        assertEquals(listOf("PluginsResolved", "Started", "Ready", "Stopping", "Stopped"), recorder.names())
        assertEquals(requested(HOST_STOP), termination)
        assertSame(termination, recorder.termination())
    }

    @Test
    fun `the stop closes, drains, stops and destroys in reverse`() {
        val plugins = explicit(
            TestIndex("store", bindings = listOf(worker("db", "store", events))),
            TestIndex("turns", bindings = listOf(worker("worker", "turns", events, listOf("db")))),
        )

        runtime(plugins, dataDir).started().stopped()

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
    fun `requestStop returns at once and the stop runs on the runtime`() {
        val draining = CountDownLatch(1)
        val release = CompletableDeferred<Unit>()
        val worker = worker(
            "a",
            "core",
            events,
            onDrain = {
                draining.countDown()
                release.await()
            },
        )
        val runtime = runtime(core(worker), dataDir, listener = recorder).started()

        runtime.requestStop(restart)

        assertTrue(draining.await(5, TimeUnit.SECONDS))
        assertEquals(RuntimeState.STOPPING, runtime.state.value)
        val stopping = assertFailsWith<IllegalStateException> { runtime.services }
        assertEquals("Runtime 'test' has no services: it is stopping.", stopping.message)
        release.complete(Unit)
        assertEquals(requested(restart), runtime.terminated())
        assertEquals(RuntimeState.STOPPED, runtime.state.value)
    }

    @Test
    fun `a plugin stops the runtime through RuntimeControl`() {
        val control = CompletableDeferred<RuntimeControl>()
        val failure = StopRequest(StopKind.FAILURE, "disk full")
        val runtime = runtime(core(worker("a", "core", onStart = { control.complete(it) })), dataDir).started()

        runBlocking { control.await() }.requestStop(failure)

        assertEquals(requested(failure), runtime.terminated())
    }

    @Test
    fun `a plugin that requests a stop from its own start cuts the start short`() {
        val failure = StopRequest(StopKind.FAILURE, "no disk")
        val runtime = runtime(
            core(
                worker("a", "core", events, onStart = { it.requestStop(failure) }),
                worker("b", "core", events, listOf("a")),
            ),
            dataDir,
            listener = recorder,
        )

        val error = runtime.startFailure()

        assertEquals(StartStage.START, error.stage)
        assertEquals("Cannot start runtime 'test': stage START failed: stopped while starting", error.message)
        assertEquals(RuntimeState.STOPPED, runtime.state.value)
        assertEquals(requested(failure), runtime.terminated())
        assertEquals(listOf("create a", "create b", "start a", "stop a", "destroy b", "destroy a"), events.all())
        assertEquals(listOf("PluginsResolved", "Stopping", "Stopped"), recorder.names())
    }

    @Test
    fun `the first stop request wins and later ones are logged and ignored`() {
        val late = StopRequest(StopKind.FAILURE, "drain failed")
        val runtime = runtime(core(worker("a", "core", onDrain = { it.requestStop(late) })), dataDir).started()

        lateinit var termination: Termination
        val lines = logged {
            termination = runtime.stopped(restart)
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
            lines,
        )
    }

    @Test
    fun `a stop before start ends the runtime at once`() {
        val runtime = runtime(core(worker("a", "core", events)), dataDir, listener = recorder)

        val termination = runtime.stopped()

        assertEquals(requested(HOST_STOP), termination)
        assertEquals(RuntimeState.STOPPED, runtime.state.value)
        assertEquals(listOf("Stopped"), recorder.names())
        assertEquals(emptyList(), events.all())
        val error = assertFailsWith<IllegalStateException> { runBlocking { runtime.start() } }
        assertEquals("Cannot start runtime 'test': it has stopped.", error.message)
    }

    private class Blocked(val stage: StartStage, val events: List<String>, val runtime: (Gate) -> AlexandriteRuntime)

    @Test
    fun `a stop cuts the start short at every stage`() {
        val cases = listOf(
            Blocked(StartStage.PLUGINS, listOf("Stopping", "Stopped")) { gate ->
                runtime(explicit(GatedIndex(gate)), dataDir, listener = recorder)
            },
            Blocked(StartStage.CONFIG, listOf("Stopping", "Stopped")) { gate ->
                runtime(core(), dataDir, listener = recorder, source = GatedSource(gate))
            },
            Blocked(StartStage.GRAPH, listOf("PluginsResolved", "Stopping", "Stopped")) { gate ->
                val slow = binding(key<Service>("slow"), "core", "slow") {
                    gate.block()
                    Service("slow", events) {}
                }
                runtime(core(slow), dataDir, listener = recorder)
            },
            Blocked(StartStage.START, listOf("PluginsResolved", "Stopping", "Stopped")) { gate ->
                runtime(core(worker("a", "core", onStart = { gate.hold() })), dataDir, listener = recorder)
            },
            Blocked(StartStage.OPEN, listOf("PluginsResolved", "Started", "Stopping", "Stopped")) { gate ->
                runtime(core(worker("a", "core", onOpen = { gate.hold() })), dataDir, listener = recorder)
            },
        )

        for (case in cases) {
            recorder.events.clear()
            val gate = Gate().apply { armed = false }
            val runtime = case.runtime(gate)
            gate.armed = true
            var thrown: Throwable? = null
            val starter = thread { thrown = runCatching { runBlocking { runtime.start() } }.exceptionOrNull() }

            gate.awaitEntered()
            val termination = runtime.stopped(restart)
            starter.join(5_000)

            val error = assertIs<RuntimeStartException>(thrown, "${case.stage}")
            assertEquals(case.stage, error.stage)
            assertEquals(
                "Cannot start runtime 'test': stage ${case.stage} failed: stopped while starting",
                error.message,
            )
            assertEquals(requested(restart), termination, "${case.stage}")
            assertEquals(RuntimeState.STOPPED, runtime.state.value)
            assertEquals(case.events, recorder.names(), "${case.stage}")
        }
    }

    @Test
    fun `a stop during OPEN closes the opened instances, then drains and stops the started ones`() {
        val gate = Gate()
        val runtime = runtime(
            core(worker("a", "core", events), worker("b", "core", events, listOf("a"), onOpen = { gate.hold() })),
            dataDir,
        )
        val starter = thread { runCatching { runBlocking { runtime.start() } } }

        gate.awaitEntered()
        runtime.stopped()
        starter.join(5_000)

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
    fun `a failed start tears down what it built and ends FAILED`() {
        val runtime = runtime(
            core(
                worker("a", "core", events),
                worker("b", "core", events, listOf("a"), onOpen = { error("open b failed") }),
            ),
            dataDir,
            listener = recorder,
        )

        val error = runtime.startFailure()

        assertEquals(StartStage.OPEN, error.stage)
        assertEquals(
            "Cannot start runtime 'test': stage OPEN failed: java.lang.IllegalStateException: open b failed",
            error.message,
        )
        assertEquals(RuntimeState.FAILED, runtime.state.value)
        val termination = runtime.terminated()
        assertSame(error, assertIs<Termination.Cause.StartFailed>(termination.cause).error)
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
        runtime(core(), dataDir).started().close()
    }

    @Test
    fun `a stop request while a failed start tears down is ignored`() {
        val runtime = runtime(
            core(
                worker("a", "core", events, onDrain = { it.requestStop(restart) }),
                worker("b", "core", events, onOpen = { error("open b failed") }),
            ),
            dataDir,
        )

        runtime.startFailure()

        assertEquals(RuntimeState.FAILED, runtime.state.value)
        assertIs<Termination.Cause.StartFailed>(runtime.terminated().cause)
    }

    // Problems while stopping.

    private class FailingClient(private val name: String) : AutoCloseable {
        override fun close() = error("close $name failed")
    }

    @Test
    fun `what fails while stopping is reported in the termination and logged`() {
        val runtime = runtime(
            core(
                worker("a", "core", onClose = { error("close a failed") }),
                worker("b", "core", onDrain = { error("drain b failed") }, onStop = { error("stop b failed") }),
                binding(key<FailingClient>(), "core", "c") { FailingClient("c") },
            ),
            dataDir,
        ).started()

        lateinit var termination: Termination
        val lines = logged { termination = runtime.stopped() }

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
        val runtime = runtime(
            core(worker("a", "core", events), worker("b", "core", events, onDrain = { awaitCancellation() })),
            dataDir,
            shutdownGrace = 200.milliseconds,
        ).started()

        val termination = runtime.stopped()

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

    // Listener.

    @Test
    fun `a listener may close, stop or request a stop from inside onEvent`() {
        val actions = listOf<(AlexandriteRuntime) -> Unit>(
            { it.close() },
            { it.requestStop() },
            { runBlocking { it.stop() } },
        )

        for ((number, action) in actions.withIndex()) {
            val recorder = Recorder()
            lateinit var runtime: AlexandriteRuntime
            val listener = RuntimeListener { event ->
                recorder.onEvent(event)
                if (event == Ready) action(runtime)
            }
            runtime = runtime(core(worker("a", "core")), dataDir, listener = listener)

            runtime.started()

            assertEquals(requested(HOST_STOP), runtime.terminated(), "action $number")
            assertEquals(listOf("PluginsResolved", "Started", "Ready", "Stopping", "Stopped"), recorder.names())
        }
    }

    @Test
    fun `awaiting the termination from inside the listener fails instead of hanging`() {
        lateinit var runtime: AlexandriteRuntime
        val failure = CompletableDeferred<Throwable>()
        val listener = RuntimeListener { event ->
            if (event == Ready) {
                runtime.requestStop()
                failure.complete(runCatching { runBlocking { runtime.awaitTermination() } }.exceptionOrNull()!!)
            }
        }
        runtime = runtime(core(), dataDir, listener = listener)

        runtime.started().terminated()

        val error = assertIs<IllegalStateException>(runBlocking { failure.await() })
        assertEquals(
            "Runtime 'test' cannot await its termination from its listener or its own lifecycle calls.",
            error.message,
        )
    }

    @Test
    fun `any number of callers await the same termination`() {
        val runtime = runtime(core(worker("a", "core")), dataDir).started()

        val terminations = runBlocking {
            val waiting = List(3) { async(Dispatchers.Default) { runtime.awaitTermination() } }
            runtime.requestStop(restart)
            waiting.awaitAll()
        }

        assertEquals(requested(restart), terminations.first())
        terminations.forEach { assertSame(terminations.first(), it) }
    }

    @Test
    fun `close blocks until the runtime has terminated and its last event was delivered`() {
        val runtime = runtime(
            core(worker("a", "core", onDrain = { delay(300.milliseconds) })),
            dataDir,
            listener = recorder,
        ).started()

        val elapsed = measureTime { runtime.close() }

        assertTrue(elapsed >= 300.milliseconds, "close returned after $elapsed")
        assertEquals(RuntimeState.STOPPED, runtime.state.value)
        assertEquals("Stopped", recorder.names().last())
    }

    // Services.

    @Test
    fun `services resolve only while the runtime is ready`() {
        lateinit var runtime: AlexandriteRuntime
        val seen = Collections.synchronizedList(mutableListOf<String>())
        fun record(call: String) {
            seen += "$call: " + runCatching { runtime.services.get(key<Probe>()) }.fold({ "resolved" }, { it.message })
        }
        runtime = runtime(
            core(
                probe("core"),
                worker("a", "core", onStart = { record("start") }, onDrain = { record("drain") }),
            ),
            dataDir,
        )

        record("new")
        runtime.started()
        record("ready")
        runtime.stopped()
        record("stopped")
        val failed = runtime(core(worker("a", "core", onStart = { error("no") })), dataDir).apply { startFailure() }

        assertEquals(
            listOf(
                "new: Runtime 'test' has no services until start() returns.",
                "start: Runtime 'test' has no services until start() returns.",
                "ready: resolved",
                "drain: Runtime 'test' has no services: it is stopping.",
                "stopped: Runtime 'test' has no services: it has stopped.",
            ),
            seen,
        )
        val error = assertFailsWith<IllegalStateException> { failed.services }
        assertEquals("Runtime 'test' has no services: it failed to start.", error.message)
    }
}
