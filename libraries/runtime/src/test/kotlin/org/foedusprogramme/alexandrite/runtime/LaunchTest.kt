package org.foedusprogramme.alexandrite.runtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
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
import org.foedusprogramme.alexandrite.runtime.Termination.Cause
import org.foedusprogramme.alexandrite.sdk.di.Lifecycle
import org.foedusprogramme.alexandrite.sdk.di.container.Binding
import org.foedusprogramme.alexandrite.sdk.di.container.Dependency
import org.foedusprogramme.alexandrite.sdk.di.container.DependencyKind
import org.foedusprogramme.alexandrite.sdk.di.container.binding
import org.foedusprogramme.alexandrite.sdk.di.key
import org.foedusprogramme.alexandrite.sdk.plugin.PluginScope
import org.foedusprogramme.alexandrite.sdk.runtime.StopKind
import org.foedusprogramme.alexandrite.sdk.runtime.StopRequest
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds

@Timeout(60)
class LaunchTest {
    @TempDir
    lateinit var dataDir: Path

    private val events = Events()
    private val recorder = Recorder()

    private val restart = StopRequest(StopKind.RESTART, "update")

    private fun core(vararg bindings: Binding<*>) = explicit(TestIndex("core", bindings = bindings.toList()))

    private fun TestScope.virtual() = StandardTestDispatcher(testScheduler)

    // The handle.

    @Test
    fun `launch returns at once and the state moves from STARTING to READY to STOPPED`() = runTest {
        val spec = spec(core(worker("a", "core", events)), dataDir, listener = recorder, dispatcher = virtual())

        val runtime = AlexandriteRuntime.launch(spec)

        assertEquals(RuntimeState.STARTING, runtime.state.value)
        assertEquals(emptyList(), events.all())
        assertTrue(runtime.awaitReady())
        assertEquals(RuntimeState.READY, runtime.state.value)
        assertEquals(listOf("create a", "start a", "open a"), events.all())
        runtime.requestStop(restart)
        assertEquals(RuntimeState.STOPPING, runtime.state.value)
        assertEquals(requested(restart), runtime.awaitTermination())
        assertEquals(RuntimeState.STOPPED, runtime.state.value)
        assertEquals(listOf("PluginsResolved", "Started", "Ready", "Stopping", "Stopped"), recorder.names())
    }

    @Test
    fun `awaitReady returns false once a runtime ended without getting ready`() = runTest {
        val broken = core(worker("a", "core", onOpen = { error("no open") }))
        val failing = AlexandriteRuntime.launch(spec(broken, dataDir.resolve("a"), dispatcher = virtual()))
        val stopped = AlexandriteRuntime.launch(spec(core(), dataDir.resolve("b"), dispatcher = virtual()))
        stopped.requestStop(restart)

        assertFalse(failing.awaitReady())
        assertFalse(stopped.awaitReady())
        assertEquals(RuntimeState.FAILED, failing.state.value)
        assertEquals(StartStage.OPEN, assertIs<Cause.StartFailed>(failing.awaitTermination().cause).error.stage)
        assertEquals(RuntimeState.STOPPED, stopped.state.value)
        assertEquals(requested(restart), stopped.awaitTermination())
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `the start timeout runs on the virtual time of a test dispatcher`() = runTest {
        val hanging = core(worker("a", "core", onStart = { awaitCancellation() }))
        val spec = spec(hanging, dataDir, startTimeout = 1.hours, dispatcher = virtual())

        val termination = AlexandriteRuntime.launch(spec).awaitTermination()

        val error = assertIs<Cause.StartFailed>(termination.cause).error
        assertEquals("Cannot start runtime 'test': stage START failed: not started within 1h", error.message)
        assertEquals(1.hours.inWholeMilliseconds, currentTime)
    }

    @Test
    fun `a stop may be requested from another thread or another coroutine`() {
        val first = AlexandriteRuntime.launch(spec(core(), dataDir.resolve("a")))
        val second = AlexandriteRuntime.launch(spec(core(), dataDir.resolve("b")))

        runBlocking { first.awaitReady() }
        thread { first.requestStop(restart) }.join()
        val termination = runBlocking {
            launch(Dispatchers.IO) {
                second.awaitReady()
                second.requestStop(restart)
            }
            second.awaitTermination()
        }

        assertEquals(requested(restart), first.terminated())
        assertEquals(requested(restart), termination)
    }

    @Test
    fun `every caller of awaitTermination gets the termination once the final event was delivered`() {
        val listener = RuntimeListener { event ->
            if (event is RuntimeEvent.Stopped) {
                Thread.sleep(100)
                events.record("delivered")
            }
        }
        val runtime = AlexandriteRuntime.launch(spec(core(), dataDir, listener = listener))
        var fromThread: Termination? = null
        val waiter = thread {
            fromThread = runtime.terminated()
            events.record("returned")
        }

        val terminations = runBlocking {
            val waiting = List(3) {
                async(Dispatchers.Default) { runtime.awaitTermination().also { events.record("returned") } }
            }
            runtime.awaitReady()
            runtime.requestStop()
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
        val runtime = AlexandriteRuntime.launch(spec(core(worker), dataDir, listener = recorder), parent)

        runBlocking {
            assertTrue(runtime.awaitReady())
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
    fun `a runtime launched in a cancelled scope stops before it starts`() {
        val parent = CoroutineScope(Job()).apply { cancel() }

        val spec = spec(core(service("a", "core", events)), dataDir, listener = recorder)

        val runtime = AlexandriteRuntime.launch(spec, parent)

        assertEquals(requested(PARENT_CANCELLED), runtime.terminated())
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
    fun `awaiting its own termination from its listener or its lifecycle calls fails at once`() {
        val handle = CompletableDeferred<AlexandriteRuntime>()
        val outcomes = Collections.synchronizedList(mutableListOf<String>())
        suspend fun attempt(where: String) {
            outcomes += try {
                handle.await().awaitTermination()
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
        val runtime = AlexandriteRuntime.launch(spec(core(worker), dataDir, listener = listener))
        handle.complete(runtime)

        runBlocking { runtime.awaitReady() }
        runtime.requestStop()

        assertEquals(requested(HOST_STOP), runtime.terminated())
        val message = "Runtime 'test' cannot await its termination from its listener, its lifecycle calls or its " +
            "plugins' coroutines."
        assertEquals(setOf("listener: $message", "drain: $message", "stop: $message"), outcomes.toSet())
    }
}
