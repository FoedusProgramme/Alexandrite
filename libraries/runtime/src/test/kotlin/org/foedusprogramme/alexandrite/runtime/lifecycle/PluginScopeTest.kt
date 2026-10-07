package org.foedusprogramme.alexandrite.runtime.lifecycle

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlinx.coroutines.withContext
import org.foedusprogramme.alexandrite.runtime.AlexandriteRuntime
import org.foedusprogramme.alexandrite.runtime.Events
import org.foedusprogramme.alexandrite.runtime.PARENT_CANCELLED
import org.foedusprogramme.alexandrite.runtime.RuntimeProblemKind
import org.foedusprogramme.alexandrite.runtime.RuntimeState
import org.foedusprogramme.alexandrite.runtime.TestIndex
import org.foedusprogramme.alexandrite.runtime.execute
import org.foedusprogramme.alexandrite.runtime.explicit
import org.foedusprogramme.alexandrite.runtime.logged
import org.foedusprogramme.alexandrite.runtime.spec
import org.foedusprogramme.alexandrite.sdk.di.Lifecycle
import org.foedusprogramme.alexandrite.sdk.di.container.Binding
import org.foedusprogramme.alexandrite.sdk.di.container.Dependency
import org.foedusprogramme.alexandrite.sdk.di.container.DependencyKind
import org.foedusprogramme.alexandrite.sdk.di.container.binding
import org.foedusprogramme.alexandrite.sdk.di.key
import org.foedusprogramme.alexandrite.sdk.plugin.PluginScope
import org.foedusprogramme.alexandrite.sdk.problem.Problem
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.coroutines.ContinuationInterceptor
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class PluginScopeTest {
    @TempDir
    lateinit var dataDir: Path

    private val events = Events()

    private class Poller(
        private val name: String,
        private val events: Events,
        val scope: PluginScope,
        private val poll: suspend CoroutineScope.() -> Unit,
    ) : Lifecycle {
        lateinit var job: Job

        override suspend fun onOpen() {
            events.record("open $name")
            job = scope.launch(block = poll)
        }

        override fun onStop() = events.record("stop $name")

        override fun onDestroy() = events.record("destroy $name")
    }

    private fun poller(name: String, plugin: String, poll: suspend CoroutineScope.() -> Unit): Binding<Poller> =
        binding(
            key(name),
            plugin,
            name,
            dependencies = listOf(Dependency(key<PluginScope>(plugin), DependencyKind.INSTANCE, "scope")),
        ) { r -> Poller(name, events, r.get(key<PluginScope>(plugin)), poll) }

    private suspend fun TestScope.started(
        vararg pollers: Binding<Poller>,
        parent: CoroutineScope? = null,
        dispatcher: CoroutineDispatcher = StandardTestDispatcher(testScheduler),
        shutdownGrace: Duration = 1.seconds,
    ): AlexandriteRuntime {
        val indexes = pollers.groupBy { it.plugin }.map { (id, bound) -> TestIndex(id, bindings = bound) }
        val spec =
            spec(explicit(*indexes.toTypedArray()), dataDir, shutdownGrace = shutdownGrace, dispatcher = dispatcher)
        return RuntimeRun(spec, PARENT_CANCELLED, testTimeSource).start(parent)
    }

    private fun AlexandriteRuntime.poller(name: String): Poller = services.resolver().get(key(name))

    @Test
    fun `a coroutine launched when opening runs until the stop and is cancelled after onStop and before onDestroy`() =
        runTest {
            val runtime = started(
                poller("poller", "core") {
                    events.record("polling")
                    try {
                        awaitCancellation()
                    } finally {
                        events.record("cancelled")
                    }
                },
            )

            runCurrent()
            assertEquals(listOf("open poller", "polling"), events.all())
            runtime.stop()

            assertEquals(emptyList(), runtime.join().problems)
            assertEquals(
                listOf("open poller", "polling", "stop poller", "cancelled", "destroy poller"),
                events.all(),
            )
        }

    @Test
    fun `each plugin has a scope of its own, named after it and on the runtime's dispatcher`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val runtime = started(poller("a", "first") {}, poller("b", "second") {}, dispatcher = dispatcher)

        val first = runtime.poller("a").scope.coroutineContext
        val second = runtime.poller("b").scope.coroutineContext

        assertEquals(listOf("first", "second"), listOf(first, second).map { it[CoroutineName]?.name })
        assertNotSame(first.job, second.job)
        assertEquals(listOf(dispatcher, dispatcher), listOf(first, second).map { it[ContinuationInterceptor] })
        runtime.stop()
        runtime.join()
        assertTrue(first.job.isCancelled && second.job.isCancelled)
    }

    @Test
    fun `a coroutine that fails is logged at ERROR and stops neither the runtime nor other coroutines`() {
        lateinit var steady: Job
        lateinit var other: Job

        val lines = logged {
            runTest {
                val runtime = started(
                    poller("crashing", "core") { error("poll failed") },
                    poller("steady", "core") { awaitCancellation() },
                    poller("other", "other") { awaitCancellation() },
                )

                runCurrent()
                steady = runtime.poller("steady").job
                other = runtime.poller("other").job

                assertEquals(RuntimeState.READY, runtime.state.value)
                assertTrue(steady.isActive && other.isActive)
                runtime.stop()
                assertEquals(emptyList(), runtime.join().problems)
            }
        }

        assertTrue(steady.isCancelled && other.isCancelled)
        assertContains(lines, "ERROR test: a coroutine of plugin core failed")
    }

    @Test
    fun `a plugin's coroutine cannot join its runtime`() = runTest {
        val handle = CompletableDeferred<AlexandriteRuntime>()
        val runtime = started(
            poller("waiter", "core") {
                val error = runCatching { handle.await().join() }.exceptionOrNull()
                events.record("${error?.javaClass?.simpleName}: ${error?.message}")
            },
        )
        handle.complete(runtime)

        runCurrent()
        runtime.stop()
        runtime.join()

        assertContains(
            events.all(),
            "IllegalStateException: Runtime 'test' cannot be joined from its listener, its lifecycle calls or its " +
                "plugins' coroutines.",
        )
    }

    @Test
    fun `coroutines still running at the shutdown deadline are reported and abandoned`() {
        val release = CompletableDeferred<Unit>()
        val parent = CoroutineScope(Job())
        val message = "Coroutines of plugin core were still running: the shutdown grace of 1s ran out."

        val lines = logged {
            runTest {
                val runtime = started(
                    poller("straggler", "core") {
                        withContext(NonCancellable) { release.await() }
                        events.record("straggler done")
                    },
                    parent = parent,
                )

                runCurrent()
                runtime.stop()

                assertEquals(
                    listOf(Problem(RuntimeProblemKind.PLUGIN_SCOPE_NOT_DONE, message, "core", null)),
                    runtime.join().problems,
                )
                assertEquals(listOf("open straggler", "stop straggler", "destroy straggler"), events.all())
                assertTrue(parent.coroutineContext.job.children.none())
                release.complete(Unit)
                runCurrent()
                assertEquals("straggler done", events.all().last())
            }
        }

        assertContains(lines, "WARN test: $message")
    }

    @Test
    fun `run returns although a coroutine ignores its cancellation`() {
        val release = CompletableDeferred<Unit>()
        val index = TestIndex(
            "core",
            bindings = listOf(
                poller("straggler", "core") {
                    withContext(NonCancellable) { release.await() }
                    events.record("straggler done")
                },
            ),
        )

        val termination = spec(explicit(index), dataDir, shutdownGrace = 0.seconds).execute()

        assertContains(termination.problems.map { it.kind }, RuntimeProblemKind.PLUGIN_SCOPE_NOT_DONE)
        assertFalse("straggler done" in events.all())
        release.complete(Unit)
    }

    @Test
    fun `a grace of zero still lets a cancelled coroutine finish without a report`() = runTest {
        val runtime = started(poller("poller", "core") { awaitCancellation() }, shutdownGrace = 0.seconds)
        runCurrent()

        runtime.stop()

        assertEquals(
            emptyList(),
            runtime.join().problems.filter {
                it.kind == RuntimeProblemKind.PLUGIN_SCOPE_NOT_DONE
            },
        )
    }
}
