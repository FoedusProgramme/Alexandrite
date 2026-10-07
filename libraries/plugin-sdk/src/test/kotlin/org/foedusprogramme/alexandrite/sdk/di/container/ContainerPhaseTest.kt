package org.foedusprogramme.alexandrite.sdk.di.container

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import org.foedusprogramme.alexandrite.sdk.di.Lifecycle
import org.foedusprogramme.alexandrite.sdk.di.container.StepReport.Outcome
import org.foedusprogramme.alexandrite.sdk.di.container.StepReport.Step
import org.foedusprogramme.alexandrite.sdk.di.key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class ContainerPhaseTest {
    private val events = Events()

    private class Phased(
        private val name: String,
        private val events: Events,
        private val start: suspend () -> Unit,
        private val open: suspend () -> Unit,
        private val close: suspend () -> Unit,
        private val drain: suspend () -> Unit,
        private val stop: () -> Unit,
    ) : Lifecycle {
        override suspend fun onStart() {
            events.record("start $name")
            start()
        }

        override suspend fun onOpen() {
            events.record("open $name")
            open()
        }

        override suspend fun onClose() {
            events.record("close $name")
            close()
        }

        override suspend fun onDrain() {
            events.record("drain $name")
            drain()
        }

        override fun onStop() {
            events.record("stop $name")
            stop()
        }

        override fun onDestroy() = events.record("destroy $name")
    }

    private class Client(private val events: Events) : AutoCloseable {
        override fun close() = events.record("auto-close client")
    }

    private fun phased(
        name: String,
        vararg dependencies: String,
        onStart: suspend () -> Unit = {},
        onOpen: suspend () -> Unit = {},
        onClose: suspend () -> Unit = {},
        onDrain: suspend () -> Unit = {},
        onStop: () -> Unit = {},
    ): Binding<Phased> = binding(
        key<Phased>(name),
        "test",
        name,
        dependencies = dependencies.map { Dependency(key<Phased>(it), DependencyKind.INSTANCE, it) },
    ) { r ->
        dependencies.forEach { r.get(key<Phased>(it)) }
        Phased(name, events, onStart, onOpen, onClose, onDrain, onStop)
    }

    private fun reports(step: Step, vararg names: String, outcome: Outcome = Outcome.Completed) =
        names.map { StepReport("test", it, step, outcome) }

    private fun TestScope.later() = testTimeSource.markNow() + 1.minutes

    @Test
    fun `each callback runs over the managed instances in its order`() = runTest {
        val container = build(
            phased("b", "a"),
            phased("a"),
            binding(key<Client>(), "test", "client") { Client(events) },
            phased("c", "b"),
        )

        container.start()
        container.open()
        val stopped = container.stop(later())
        container.close()

        assertEquals(
            listOf(
                "start a", "start b", "start c",
                "open a", "open b", "open c",
                "close c", "close b", "close a",
                "drain c", "drain b", "drain a",
                "stop c", "stop b", "stop a",
                "destroy c", "auto-close client", "destroy b", "destroy a",
            ),
            events.all(),
        )
        assertEquals(listOf(Step.CLOSE, Step.DRAIN, Step.STOP).flatMap { reports(it, "c", "b", "a") }, stopped)
    }

    // Pairing.

    @Test
    fun `a failed start leaves the started instances to stop, which drains and stops them`() = runTest {
        val container = build(
            phased("a"),
            phased("b"),
            phased("c", onStart = { error("start c failed") }),
            phased("d"),
        )

        val error = assertFailsWith<IllegalStateException> { container.start() }
        val stopped = container.stop(later())
        container.close()

        assertEquals("start c failed", error.message)
        assertEquals(listOf(Step.DRAIN, Step.STOP).flatMap { reports(it, "b", "a") }, stopped)
        assertEquals(
            listOf(
                "start a", "start b", "start c",
                "drain b", "drain a",
                "stop b", "stop a",
                "destroy d", "destroy c", "destroy b", "destroy a",
            ),
            events.all(),
        )
    }

    @Test
    fun `a failing open leaves the opened instances to stop, which closes, drains and stops them`() = runTest {
        val container = build(
            phased("a"),
            phased("b", onClose = { error("close b failed") }),
            phased("c", onOpen = { error("open c failed") }),
            phased("d"),
        )
        container.start()

        val error = assertFailsWith<IllegalStateException> { container.open() }
        val stopped = container.stop(later())
        container.close()

        assertEquals("open c failed", error.message)
        assertEquals(emptyList(), error.suppressed.toList())
        assertEquals("close b failed", assertIs<Outcome.Failed>(stopped[0].outcome).error.message)
        assertEquals(
            listOf(StepReport("test", "b", Step.CLOSE, stopped[0].outcome)) + reports(Step.CLOSE, "a") +
                listOf(Step.DRAIN, Step.STOP).flatMap { reports(it, "d", "c", "b", "a") },
            stopped,
        )
        assertEquals(
            listOf(
                "start a", "start b", "start c", "start d",
                "open a", "open b", "open c",
                "close b", "close a",
                "drain d", "drain c", "drain b", "drain a",
                "stop d", "stop c", "stop b", "stop a",
                "destroy d", "destroy c", "destroy b", "destroy a",
            ),
            events.all(),
        )
    }

    @Test
    fun `a cancelled open leaves the opened instances to stop`() = runTest {
        val container = build(phased("a"), phased("b", onOpen = { awaitCancellation() }), phased("c"))
        container.start()
        val opening = async(start = CoroutineStart.UNDISPATCHED) { container.open() }

        opening.cancelAndJoin()
        val stopped = container.stop(later())

        assertEquals(
            reports(Step.CLOSE, "a") + listOf(Step.DRAIN, Step.STOP).flatMap { reports(it, "c", "b", "a") },
            stopped,
        )
        assertEquals(
            listOf(
                "start a", "start b", "start c",
                "open a", "open b",
                "close a",
                "drain c", "drain b", "drain a",
                "stop c", "stop b", "stop a",
            ),
            events.all(),
        )
    }

    @Test
    fun `stop and close call each callback once`() = runTest {
        val container = build(phased("a"), phased("b"))
        container.start()
        container.open()

        container.stop(later())
        val again = container.stop(later())
        container.close()
        container.close()

        assertEquals(emptyList(), again)
        assertEquals(
            listOf(
                "start a", "start b",
                "open a", "open b",
                "close b", "close a",
                "drain b", "drain a",
                "stop b", "stop a",
                "destroy b", "destroy a",
            ),
            events.all(),
        )
    }

    @Test
    fun `a failing stop is reported and the next one still runs`() = runTest {
        val container = build(phased("a"), phased("b", onStop = { error("stop b failed") }))
        container.start()

        val stopped = container.stop(later()).filter { it.step == Step.STOP }
        container.close()

        assertEquals(listOf("b", "a"), stopped.map { it.origin })
        assertEquals("stop b failed", assertIs<Outcome.Failed>(stopped[0].outcome).error.message)
        assertEquals(Outcome.Completed, stopped[1].outcome)
        assertEquals(listOf("stop b", "stop a"), events.starting("stop"))
    }

    // Deadline.

    @Test
    fun `closing and draining share the deadline`() = runTest {
        val cancelled = CompletableDeferred<Unit>()
        val container = build(
            phased("a"),
            phased(
                "b",
                onDrain = {
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled.complete(Unit)
                    }
                },
            ),
            phased("c", onClose = { delay(2.seconds) }, onDrain = { delay(3.seconds) }),
        )
        container.start()
        container.open()
        val deadline = testTimeSource.markNow() + 10.seconds

        val stopped = container.stop(deadline)

        assertEquals(
            reports(Step.CLOSE, "c", "b", "a") +
                reports(Step.DRAIN, "c") +
                reports(Step.DRAIN, "b", outcome = Outcome.TimedOut) +
                reports(Step.DRAIN, "a", outcome = Outcome.NotCalled) +
                reports(Step.STOP, "c", "b", "a"),
            stopped,
        )
        assertEquals(10_000, currentTime)
        assertTrue(cancelled.isCompleted)
        assertEquals(listOf("drain c", "drain b"), events.starting("drain"))
    }

    @Test
    fun `a failing drain is reported and the next one still runs`() = runTest {
        val container = build(phased("a"), phased("b", onDrain = { error("drain b failed") }))
        container.start()

        val drained = container.stop(later()).filter { it.step == Step.DRAIN }

        assertEquals(listOf("b", "a"), drained.map { it.origin })
        assertEquals("drain b failed", assertIs<Outcome.Failed>(drained[0].outcome).error.message)
        assertEquals(Outcome.Completed, drained[1].outcome)
    }

    @Test
    fun `a close that fails or outlasts the deadline is reported and the later ones are skipped`() = runTest {
        val container = build(
            phased("a"),
            phased("b", onClose = { awaitCancellation() }),
            phased("c", onClose = { error("close c failed") }),
        )
        container.start()
        container.open()

        val stopped = container.stop(testTimeSource.markNow() + 5.seconds)

        assertEquals("close c failed", assertIs<Outcome.Failed>(stopped[0].outcome).error.message)
        assertEquals(
            listOf(
                Step.CLOSE to "c",
                Step.CLOSE to "b",
                Step.CLOSE to "a",
            ),
            stopped.take(3).map { it.step to it.origin },
        )
        assertEquals(listOf(Outcome.TimedOut, Outcome.NotCalled), stopped.subList(1, 3).map { it.outcome })
        assertEquals(
            reports(Step.DRAIN, "c", "b", "a", outcome = Outcome.NotCalled) + reports(Step.STOP, "c", "b", "a"),
            stopped.drop(3),
        )
        assertEquals(5_000, currentTime)
    }

    @Test
    fun `a cancelled caller of stop gets its cancellation and close stops the rest`() = runTest {
        val draining = CompletableDeferred<Unit>()
        val container = build(
            phased("a"),
            phased(
                "b",
                onDrain = {
                    draining.complete(Unit)
                    awaitCancellation()
                },
            ),
        )
        container.start()
        val stopping = async { container.stop(later()) }
        draining.await()

        stopping.cancel()
        assertFailsWith<CancellationException> { stopping.await() }
        container.close()

        assertEquals(
            listOf("start a", "start b", "drain b", "stop b", "stop a", "destroy b", "destroy a"),
            events.all(),
        )
    }

    @Test
    fun `a drain that throws a CancellationException of its own is reported as failed`() = runTest {
        val container = build(phased("a"), phased("b", onDrain = { throw CancellationException("drain b gave up") }))
        container.start()

        val drained = container.stop(later()).filter { it.step == Step.DRAIN }

        assertEquals("drain b gave up", assertIs<Outcome.Failed>(drained[0].outcome).error.message)
        assertEquals(Outcome.Completed, drained[1].outcome)
    }

    @Test
    fun `past the deadline nothing is closed or drained, but every started instance is stopped`() = runTest {
        val container = build(phased("a"), phased("b"))
        container.start()
        container.open()

        val stopped = container.stop(testTimeSource.markNow() - 1.seconds)

        assertEquals(
            reports(Step.CLOSE, "b", "a", outcome = Outcome.NotCalled) +
                reports(Step.DRAIN, "b", "a", outcome = Outcome.NotCalled) +
                reports(Step.STOP, "b", "a"),
            stopped,
        )
        assertEquals(emptyList(), container.stop(later()))
        assertEquals(listOf("start a", "start b", "open a", "open b", "stop b", "stop a"), events.all())
    }

    // Guards.

    @Test
    fun `a container opens once and neither opens nor stops once closed`() = runTest {
        val container = build(phased("a"))
        container.start()
        container.open()

        val twice = assertFailsWith<DiException> { container.open() }
        container.stop(later())
        container.close()
        val closed = listOf<suspend () -> Unit>(
            { container.open() },
            { container.stop(later()) },
        ).map { assertFailsWith<DiException> { it() }.message }

        assertEquals("Cannot open container 'root' twice.", twice.message)
        assertEquals(listOf(DiProblemKind.OPENED_TWICE), twice.problems.map { it.kind })
        assertEquals(
            listOf(
                "Cannot open container 'root': it is closed.",
                "Cannot stop container 'root': it is closed.",
            ),
            closed,
        )
    }
}
