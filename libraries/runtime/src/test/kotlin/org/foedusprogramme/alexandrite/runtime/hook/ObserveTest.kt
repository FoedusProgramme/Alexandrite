package org.foedusprogramme.alexandrite.runtime.hook

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.foedusprogramme.alexandrite.sdk.hook.Delivery
import org.foedusprogramme.alexandrite.sdk.hook.FailurePolicy
import org.foedusprogramme.alexandrite.sdk.hook.Hook
import org.foedusprogramme.alexandrite.sdk.hook.HookDecision
import org.foedusprogramme.alexandrite.sdk.hook.HookEffect
import org.foedusprogramme.alexandrite.sdk.hook.HookFailure
import org.foedusprogramme.alexandrite.sdk.hook.HookPoint
import org.foedusprogramme.alexandrite.sdk.hook.Interception
import org.foedusprogramme.alexandrite.sdk.hook.InterceptorHook
import org.foedusprogramme.alexandrite.sdk.hook.InterceptorPoint
import org.foedusprogramme.alexandrite.sdk.hook.ObserverHook
import org.foedusprogramme.alexandrite.sdk.hook.ObserverPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class ObserveTest {
    private val listener = RecordingListener()
    private val records = Records()
    private val seen = seenPoint()

    private fun TestScope.dispatcher(vararg hooks: Hook, asyncCapacity: Int = 256) = HookDispatcher(
        hooks.toList(),
        listener,
        asyncCapacity,
        asyncDispatcher = StandardTestDispatcher(testScheduler),
    )

    private fun recording(name: String, order: Int = 0, delivery: Delivery = Delivery.INLINE) =
        TestObserver(seen, order, delivery = delivery) { records.add("$name $it") }

    // Inline.

    @Test
    fun `inline observers run in order, each awaited before the next`() = runTest {
        val dispatcher = dispatcher(
            TestObserver(seen, order = 2) { records.add("last at $currentTime") },
            TestObserver(seen, order = 1) {
                delay(1.seconds)
                records.add("first done")
            },
        )

        dispatcher.fire(seen, "x")

        assertEquals(listOf("first done", "last at 1000"), records.all())
    }

    @Test
    fun `a throwing inline observer is reported and later observers still run`() = runTest {
        val dispatcher = dispatcher(TestObserver(seen) { error("observer failed") }, recording("second", order = 1))

        dispatcher.fire(seen, "x")

        assertEquals(listOf("second x"), records.all())
        assertEquals("observer failed", assertIs<HookFailure.Threw>(listener.all().single().failure).error.message)
    }

    @Test
    fun `an inline observer runs only for its own point`() = runTest {
        val dispatcher = dispatcher(recording("first"))

        dispatcher.fire(ObserverPoint<String>("test.other"), "x")

        assertEquals(emptyList(), records.all())
    }

    @Test
    fun `firing another observer point object with a subscribed id is rejected`() = runTest {
        val dispatcher = dispatcher(recording("first"), recording("queued", delivery = Delivery.ASYNC))

        assertFailsWith<IllegalArgumentException> { dispatcher.fire(seenPoint(), "x") }
        advanceUntilIdle()

        assertEquals(emptyList(), records.all())
    }

    // Async.

    @Test
    fun `observe returns before async observers run`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val dispatcher = dispatcher(
            TestObserver(seen, delivery = Delivery.ASYNC) {
                records.add("started $it")
                gate.await()
                records.add("finished $it")
            },
        )

        dispatcher.fire(seen, "x")
        records.add("returned")
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
            TestObserver(seen, delivery = Delivery.ASYNC) {
                gate.await()
                slow += it
            },
            recording("fast", delivery = Delivery.ASYNC),
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
        val dispatcher = dispatcher(slow, asyncCapacity = 2)
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
            TestObserver(seen, timeout = 1.seconds, delivery = Delivery.ASYNC) {
                when (it) {
                    "a" -> error("a failed")
                    "b" -> delay(2.seconds)
                    else -> records.add("got $it")
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
        val dispatcher = HookDispatcher(
            listOf(
                TestObserver(seen, delivery = Delivery.ASYNC) {
                    if (it == "a") error("a failed") else records.add("got $it")
                },
            ),
            { _, _, _ -> TODO("listener") },
            asyncDispatcher = StandardTestDispatcher(testScheduler),
        )

        listOf("a", "b").forEach { dispatcher.fire(seen, it) }
        advanceUntilIdle()

        assertEquals(listOf("got b"), records.all())
    }

    @Test
    fun `the async capacity must be positive`() {
        assertFailsWith<IllegalArgumentException> { HookDispatcher(emptyList(), asyncCapacity = 0) }
    }

    private class Slow(point: ObserverPoint<String>, gate: CompletableDeferred<Unit>, records: Records) :
        TestObserver<String>(point, delivery = Delivery.ASYNC, block = {
            gate.await()
            records.add("slow $it")
        })
}
