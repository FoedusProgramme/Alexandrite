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
import kotlinx.coroutines.withContext
import org.foedusprogramme.alexandrite.runtime.AlexandriteRuntime
import org.foedusprogramme.alexandrite.runtime.Events
import org.foedusprogramme.alexandrite.runtime.RuntimeProblemKind
import org.foedusprogramme.alexandrite.runtime.RuntimeState
import org.foedusprogramme.alexandrite.runtime.TestIndex
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

    private fun TestScope.launched(
        vararg pollers: Binding<Poller>,
        parent: CoroutineScope? = null,
        dispatcher: CoroutineDispatcher = StandardTestDispatcher(testScheduler),
    ): AlexandriteRuntime {
        val indexes = pollers.groupBy { it.plugin }.map { (id, bound) -> TestIndex(id, bindings = bound) }
        val spec = spec(explicit(*indexes.toTypedArray()), dataDir, shutdownGrace = 1.seconds, dispatcher = dispatcher)
        return AlexandriteRuntime.launch(spec, parent)
    }

    private fun AlexandriteRuntime.poller(name: String): Poller = services.resolver().get(key(name))

    @Test
    fun `a coroutine launched when opening runs until the stop and is cancelled after onStop and before onDestroy`() =
        runTest {
            val runtime = launched(
                poller("poller", "core") {
                    events.record("polling")
                    try {
                        awaitCancellation()
                    } finally {
                        events.record("cancelled")
                    }
                },
            )

            runtime.awaitReady()
            runCurrent()
            assertEquals(listOf("open poller", "polling"), events.all())
            runtime.requestStop()

            assertEquals(emptyList(), runtime.awaitTermination().problems)
            assertEquals(
                listOf("open poller", "polling", "stop poller", "cancelled", "destroy poller"),
                events.all(),
            )
        }

    @Test
    fun `each plugin has a scope of its own, named after it and on the runtime's dispatcher`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val runtime = launched(poller("a", "first") {}, poller("b", "second") {}, dispatcher = dispatcher)

        runtime.awaitReady()
        val first = runtime.poller("a").scope.coroutineContext
        val second = runtime.poller("b").scope.coroutineContext

        assertEquals(listOf("first", "second"), listOf(first, second).map { it[CoroutineName]?.name })
        assertNotSame(first.job, second.job)
        assertEquals(listOf(dispatcher, dispatcher), listOf(first, second).map { it[ContinuationInterceptor] })
        runtime.requestStop()
        runtime.awaitTermination()
        assertTrue(first.job.isCancelled && second.job.isCancelled)
    }

    @Test
    fun `a coroutine that fails is logged at ERROR and stops neither the runtime nor other coroutines`() {
        lateinit var steady: Job
        lateinit var other: Job

        val lines = logged {
            runTest {
                val runtime = launched(
                    poller("crashing", "core") { error("poll failed") },
                    poller("steady", "core") { awaitCancellation() },
                    poller("other", "other") { awaitCancellation() },
                )

                runtime.awaitReady()
                runCurrent()
                steady = runtime.poller("steady").job
                other = runtime.poller("other").job

                assertEquals(RuntimeState.READY, runtime.state.value)
                assertTrue(steady.isActive && other.isActive)
                runtime.requestStop()
                assertEquals(emptyList(), runtime.awaitTermination().problems)
            }
        }

        assertTrue(steady.isCancelled && other.isCancelled)
        assertContains(lines, "ERROR test: a coroutine of plugin core failed")
    }

    @Test
    fun `a plugin's coroutine cannot await the termination of its runtime`() = runTest {
        val handle = CompletableDeferred<AlexandriteRuntime>()
        val runtime = launched(
            poller("waiter", "core") {
                val error = runCatching { handle.await().awaitTermination() }.exceptionOrNull()
                events.record("${error?.javaClass?.simpleName}: ${error?.message}")
            },
        )
        handle.complete(runtime)

        runtime.awaitReady()
        runCurrent()
        runtime.requestStop()
        runtime.awaitTermination()

        assertContains(
            events.all(),
            "IllegalStateException: Runtime 'test' cannot await its termination from its listener, its lifecycle " +
                "calls or its plugins' coroutines.",
        )
    }

    @Test
    fun `coroutines still running at the shutdown deadline are reported and the teardown goes on`() {
        val release = CompletableDeferred<Unit>()
        val parent = CoroutineScope(Job())
        val message = "Coroutines of plugin core were still running: the shutdown grace of 1s ran out."

        val lines = logged {
            runTest {
                val runtime = launched(
                    poller("straggler", "core") {
                        withContext(NonCancellable) { release.await() }
                        events.record("straggler done")
                    },
                    parent = parent,
                )

                runtime.awaitReady()
                runCurrent()
                runtime.requestStop()

                assertEquals(
                    listOf(Problem(RuntimeProblemKind.PLUGIN_SCOPE_NOT_DONE, message, "core", null)),
                    runtime.awaitTermination().problems,
                )
                assertEquals(listOf("open straggler", "stop straggler", "destroy straggler"), events.all())
                assertFalse(parent.coroutineContext.job.children.none())
                release.complete(Unit)
                runCurrent()
                assertEquals("straggler done", events.all().last())
                assertTrue(parent.coroutineContext.job.children.none())
            }
        }

        assertContains(lines, "WARN test: $message")
    }
}
