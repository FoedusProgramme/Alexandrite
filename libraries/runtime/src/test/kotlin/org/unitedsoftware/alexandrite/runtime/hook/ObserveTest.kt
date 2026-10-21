package org.unitedsoftware.alexandrite.runtime.hook

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.unitedsoftware.alexandrite.runtime.Events
import org.unitedsoftware.alexandrite.sdk.hook.HookFailure
import org.unitedsoftware.alexandrite.sdk.hook.ObserverDelivery
import org.unitedsoftware.alexandrite.sdk.hook.ObserverPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class ObserveTest {
    private val listener = RecordingListener()
    private val records = Events()
    private val seen = seenPoint()

    private fun recording(name: String, order: Int = 0, delivery: ObserverDelivery = ObserverDelivery.INLINE) =
        TestObserver(seen, order, delivery = delivery) { records.record("$name $it") }

    // Inline.

    @Test
    fun `inline observers run in order, each awaited before the next`() = runTest {
        val dispatcher = dispatcher(
            listener,
            TestObserver(seen, order = 2) { records.record("last at $currentTime") },
            TestObserver(seen, order = 1) {
                delay(1.seconds)
                records.record("first done")
            },
        )

        dispatcher.fire(seen, "x")

        assertEquals(listOf("first done", "last at 1000"), records.all())
    }

    @Test
    fun `a throwing inline observer is reported and later observers still run`() = runTest {
        val dispatcher =
            dispatcher(listener, TestObserver(seen) { error("observer failed") }, recording("second", order = 1))

        dispatcher.fire(seen, "x")

        assertEquals(listOf("second x"), records.all())
        assertEquals("observer failed", assertIs<HookFailure.Threw>(listener.all().single().failure).error.message)
    }

    // Async.

    @Test
    fun `observe returns before async observers run`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val dispatcher = dispatcher(
            listener,
            TestObserver(seen, delivery = ObserverDelivery.ASYNC) {
                records.record("started $it")
                gate.await()
                records.record("finished $it")
            },
        )

        dispatcher.fire(seen, "x")
        records.record("returned")
        runCurrent()
        gate.complete(Unit)
        runCurrent()

        assertEquals(listOf("returned", "started x", "finished x"), records.all())
    }

    @Test
    fun `a slow async observer delays no other and each keeps its order`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val slow = mutableListOf<String>()
        val dispatcher = dispatcher(
            listener,
            TestObserver(seen, delivery = ObserverDelivery.ASYNC) {
                gate.await()
                slow += it
            },
            recording("fast", delivery = ObserverDelivery.ASYNC),
        )

        listOf("a", "b", "c").forEach { dispatcher.fire(seen, it) }
        runCurrent()

        assertEquals(listOf("fast a", "fast b", "fast c"), records.all())
        assertEquals(emptyList(), slow)

        gate.complete(Unit)
        runCurrent()

        assertEquals(listOf("a", "b", "c"), slow)
        assertEquals(emptyList(), listener.all())
    }

    @Test
    fun `a full async queue drops the event and reports it without suspending`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val slow = Slow(seen, gate, records)
        val dispatcher = dispatcher(listener, slow, asyncCapacity = 2)
        dispatcher.fire(seen, "a")
        runCurrent()

        listOf("b", "c", "d").forEach { dispatcher.fire(seen, it) }

        assertEquals(listOf(Reported(Slow::class.java.name, seen, HookFailure.Dropped)), listener.all())
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("slow a", "slow b", "slow c"), records.all())
    }

    @Test
    fun `a failing async observer is reported and still gets later events`() = runTest {
        val dispatcher = dispatcher(
            listener,
            TestObserver(seen, timeout = 1.seconds, delivery = ObserverDelivery.ASYNC) {
                when (it) {
                    "a" -> error("a failed")
                    "b" -> delay(2.seconds)
                    else -> records.record("got $it")
                }
            },
        )

        listOf("a", "b", "c").forEach { dispatcher.fire(seen, it) }
        advanceUntilIdle()

        assertEquals(listOf("got c"), records.all())
        val (threw, timedOut) = listener.all().map { it.failure }
        assertEquals("a failed", assertIs<HookFailure.Threw>(threw).error.message)
        assertEquals(HookFailure.TimedOut(1.seconds), timedOut)
    }

    @Test
    fun `a listener that throws an error does not stop an async observer`() = runTest {
        val dispatcher = hookDispatcher(
            listOf(
                TestObserver(seen, delivery = ObserverDelivery.ASYNC) {
                    if (it == "a") error("a failed") else records.record("got $it")
                },
            ),
            { _, _, _ -> TODO("listener") },
            asyncContext = StandardTestDispatcher(testScheduler),
        )

        listOf("a", "b").forEach { dispatcher.fire(seen, it) }
        advanceUntilIdle()

        assertEquals(listOf("got b"), records.all())
    }

    private class Slow(point: ObserverPoint<String>, gate: CompletableDeferred<Unit>, records: Events) :
        TestObserver<String>(point, delivery = ObserverDelivery.ASYNC, block = {
            gate.await()
            records.record("slow $it")
        })
}
