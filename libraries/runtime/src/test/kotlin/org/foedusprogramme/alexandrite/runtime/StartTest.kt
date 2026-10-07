package org.foedusprogramme.alexandrite.runtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.foedusprogramme.alexandrite.sdk.di.Lifecycle
import org.foedusprogramme.alexandrite.sdk.di.container.Binding
import org.foedusprogramme.alexandrite.sdk.di.container.Dependency
import org.foedusprogramme.alexandrite.sdk.di.container.DependencyKind
import org.foedusprogramme.alexandrite.sdk.di.container.binding
import org.foedusprogramme.alexandrite.sdk.di.key
import org.foedusprogramme.alexandrite.sdk.plugin.PluginScope
import org.foedusprogramme.alexandrite.sdk.runtime.StopRequest
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds

@Timeout(60)
class StartTest {
    @TempDir
    lateinit var dataDir: Path

    private val events = Events()
    private val recorder = Recorder()

    private val restart = StopRequest.restart("update")

    private fun core(vararg bindings: Binding<*>) = explicit(TestIndex("core", bindings = bindings.toList()))

    private fun TestScope.virtual() = StandardTestDispatcher(testScheduler)

    // The handle.

    @Test
    fun `start returns once ready and the state moves from READY to STOPPING to STOPPED`() = runTest {
        val spec = spec(core(worker("a", "core", events)), dataDir, listener = recorder, dispatcher = virtual())

        val runtime = AlexandriteRuntime.start(spec)

        assertEquals(RuntimeState.READY, runtime.state.value)
        assertEquals(listOf("create a", "start a", "open a"), events.all())
        runtime.stop(restart)
        assertEquals(RuntimeState.STOPPING, runtime.state.value)
        assertEquals(requested(restart), runtime.join())
        assertEquals(RuntimeState.STOPPED, runtime.state.value)
        assertEquals(listOf("PluginsResolved", "Started", "Ready", "Stopping", "Stopped"), recorder.names())
    }

    @Test
    fun `start throws once a runtime ended without getting ready`() = runTest {
        val broken = core(worker("a", "core", onOpen = { error("no open") }))
        val stopping = core(worker("a", "core", onStart = { it.stop(restart) }))

        val failed = assertFailsWith<RuntimeStartException> {
            AlexandriteRuntime.start(spec(broken, dataDir.resolve("a"), dispatcher = virtual()))
        }
        val stopped = assertFailsWith<RuntimeStartException> {
            AlexandriteRuntime.start(spec(stopping, dataDir.resolve("b"), dispatcher = virtual()))
        }

        assertEquals(StartStage.OPEN, failed.stage)
        assertNull(failed.stopRequest)
        assertEquals(StartStage.START, stopped.stage)
        assertEquals(restart, stopped.stopRequest)
        assertEquals(
            "Cannot start runtime 'test': a stop was requested at stage START: $restart",
            stopped.message,
        )
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `the start timeout runs on the virtual time of a test dispatcher`() = runTest {
        val hanging = core(worker("a", "core", onStart = { awaitCancellation() }))
        val spec = spec(hanging, dataDir, startTimeout = 1.hours, dispatcher = virtual())

        val error = assertFailsWith<RuntimeStartException> { AlexandriteRuntime.start(spec) }

        assertEquals("Cannot start runtime 'test': stage START failed: not started within 1h", error.message)
        assertEquals(1.hours.inWholeMilliseconds, currentTime)
    }

    @Test
    fun `cancelling the caller of start tears the start down, then throws the CancellationException`() {
        val spec = spec(core(service("a", "core", events, onStart = hang)), dataDir, listener = recorder)

        assertFailsWith<TimeoutCancellationException> {
            runBlocking { withTimeout(100.milliseconds) { AlexandriteRuntime.start(spec) } }
        }

        assertEquals(listOf("PluginsResolved", "Stopping", "Stopped"), recorder.names())
        assertEquals(requested(START_CANCELLED), recorder.termination())
        assertEquals(listOf("create a", "start a", "destroy a"), events.all())
    }

    @Test
    fun `a stop may be requested from another thread or another coroutine`() {
        val first = spec(core(), dataDir.resolve("a")).started()
        val second = spec(core(), dataDir.resolve("b")).started()

        thread { first.stop(restart) }.join()
        val termination = runBlocking {
            launch(Dispatchers.IO) { second.stop(restart) }
            second.join()
        }

        assertEquals(requested(restart), first.terminated())
        assertEquals(requested(restart), termination)
    }

    @Test
    fun `every caller of join gets the termination once the final event was delivered`() {
        val listener = RuntimeListener { event ->
            if (event is RuntimeEvent.Stopped) {
                Thread.sleep(100)
                events.record("delivered")
            }
        }
        val runtime = spec(core(), dataDir, listener = listener).started()
        var fromThread: Termination? = null
        val waiter = thread {
            fromThread = runtime.terminated()
            events.record("returned")
        }

        val terminations = runBlocking {
            val waiting = List(3) {
                async(Dispatchers.Default) { runtime.join().also { events.record("returned") } }
            }
            runtime.stop()
            waiting.awaitAll()
        }
        waiter.join()

        assertEquals(listOf("delivered") + List(4) { "returned" }, events.all())
        assertEquals(requested(HOST_STOP), terminations.first())
        (terminations + fromThread).forEach { assertSame(terminations.first(), it) }
    }

    // Parent scope.

    @Test
    fun `cancelling the parent scope stops the runtime and the parent waits until it is torn down`() {
        val parent = CoroutineScope(Job())
        val worker = worker(
            "a",
            "core",
            events,
            onDrain = {
                delay(100.milliseconds)
                events.record("drained a")
            },
        )
        val runtime = spec(core(worker), dataDir, listener = recorder).started(parent)

        runBlocking {
            parent.cancel()
            assertEquals(RuntimeState.STOPPING, runtime.state.value)
            parent.coroutineContext.job.join()
        }

        assertEquals(RuntimeState.STOPPED, runtime.state.value)
        assertEquals(requested(PARENT_CANCELLED), recorder.termination())
        assertEquals(
            listOf("create a", "start a", "open a", "close a", "drain a", "drained a", "stop a", "destroy a"),
            events.all(),
        )
    }

    @Test
    fun `a runtime started in a cancelled scope stops before it starts`() {
        val parent = CoroutineScope(Job()).apply { cancel() }

        val spec = spec(core(service("a", "core", events)), dataDir, listener = recorder)

        val error = assertFailsWith<RuntimeStartException> { spec.started(parent) }

        assertEquals(PARENT_CANCELLED, error.stopRequest)
        assertEquals(StartStage.DATA_DIR, error.stage)
        assertEquals(requested(PARENT_CANCELLED), recorder.termination())
        assertEquals(emptyList(), events.all())
        assertEquals(listOf("Stopping", "Stopped"), recorder.names())
    }

    // Dispatcher.

    private class Threads {
        private val seen = Collections.synchronizedList(mutableListOf<Pair<String, String>>())

        fun record(what: String) {
            seen += what to Thread.currentThread().name.substringBefore(" @")
        }

        fun all(): List<Pair<String, String>> = synchronized(seen) { seen.toList() }
    }

    private class Recorded(private val threads: Threads, private val scope: PluginScope) : Lifecycle {
        val launched = CompletableDeferred<Unit>()

        init {
            threads.record("create")
        }

        override suspend fun onStart() = threads.record("start")

        override suspend fun onOpen() {
            threads.record("open")
            scope.launch {
                threads.record("plugin coroutine")
                launched.complete(Unit)
            }
        }

        override suspend fun onClose() = threads.record("close")

        override suspend fun onDrain() = threads.record("drain")

        override fun onStop() = threads.record("stop")

        override fun onDestroy() = threads.record("destroy")
    }

    private fun recorded(threads: Threads): Binding<Recorded> = binding(
        key(),
        "core",
        "recorded",
        dependencies = listOf(Dependency(key<PluginScope>("core"), DependencyKind.INSTANCE, "scope")),
    ) { r -> Recorded(threads, r.get(key<PluginScope>("core"))) }

    private fun threadsOf(spec: (Threads) -> RuntimeSpec): List<Pair<String, String>> {
        val threads = Threads()
        runBlocking {
            AlexandriteRuntime.run(spec(threads)) {
                services.resolver().get(key<Recorded>()).launched.await()
            }
        }
        return threads.all()
    }

    private val calls = listOf(
        "create",
        "PluginsResolved",
        "start",
        "Started",
        "open",
        "Ready",
        "plugin coroutine",
        "close",
        "drain",
        "stop",
        "destroy",
        "Stopping",
        "Stopped",
    )

    @Test
    fun `lifecycle calls, events and plugin coroutines run on the runtime's dispatcher`() {
        val executor = Executors.newSingleThreadExecutor { Thread(it, "runtime-thread") }
        val dispatcher = executor.asCoroutineDispatcher()
        try {
            val seen = threadsOf { threads ->
                spec(
                    core(recorded(threads)),
                    dataDir,
                    listener = { threads.record(it::class.simpleName!!) },
                    dispatcher = dispatcher,
                )
            }

            assertEquals(calls.sorted(), seen.map { it.first }.sorted())
            assertEquals(setOf("runtime-thread"), seen.map { it.second }.toSet())
        } finally {
            executor.shutdown()
        }
    }

    @Test
    fun `by default lifecycle calls, events and plugin coroutines run on Dispatchers Default`() {
        val host = Thread.currentThread().name

        val seen = threadsOf { threads ->
            spec(core(recorded(threads)), dataDir, listener = { threads.record(it::class.simpleName!!) })
        }

        assertEquals(calls.sorted(), seen.map { it.first }.sorted())
        assertTrue(seen.all { it.second.startsWith("DefaultDispatcher-worker-") && it.second != host }, "$seen")
    }

    // Deadlocks.

    @Test
    fun `joining a runtime from its listener or its lifecycle calls fails at once`() {
        val handle = CompletableDeferred<AlexandriteRuntime>()
        val outcomes = Collections.synchronizedList(mutableListOf<String>())
        suspend fun attempt(where: String) {
            outcomes += try {
                handle.await().join()
                "$where: returned"
            } catch (e: IllegalStateException) {
                "$where: ${e.message}"
            }
        }
        val listener = RuntimeListener { event -> if (event == RuntimeEvent.Ready) runBlocking { attempt("listener") } }
        val worker = worker(
            "a",
            "core",
            onDrain = { attempt("drain") },
            onStop = { runBlocking { attempt("stop") } },
        )
        val runtime = spec(core(worker), dataDir, listener = listener).started()
        handle.complete(runtime)

        runtime.stop()

        assertEquals(requested(HOST_STOP), runtime.terminated())
        val message =
            "Runtime 'test' cannot be joined from its listener, its lifecycle calls or its plugins' coroutines."
        assertEquals(setOf("listener: $message", "drain: $message", "stop: $message"), outcomes.toSet())
    }
}
