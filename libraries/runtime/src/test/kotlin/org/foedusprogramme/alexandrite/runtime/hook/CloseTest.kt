package org.foedusprogramme.alexandrite.runtime.hook

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.foedusprogramme.alexandrite.runtime.Events
import org.foedusprogramme.alexandrite.sdk.hook.HookDecision
import org.foedusprogramme.alexandrite.sdk.hook.Interception
import org.foedusprogramme.alexandrite.sdk.hook.ObserverDelivery
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class CloseTest {
    private val listener = RecordingListener()
    private val records = Events()
    private val seen = seenPoint()

    @Test
    fun `onDrain delivers the queued events, then later events are dropped silently`() = runTest {
        val dispatcher = dispatcher(
            listener,
            TestObserver(seen, delivery = ObserverDelivery.ASYNC) {
                delay(1.seconds)
                records.record(it)
            },
        )
        listOf("a", "b", "c").forEach { dispatcher.fire(seen, it) }

        dispatcher.onDrain()
        dispatcher.fire(seen, "d")
        advanceUntilIdle()

        assertEquals(listOf("a", "b", "c"), records.all())
        assertEquals(3000, currentTime)
        assertEquals(emptyList(), listener.all())
    }

    @Test
    fun `a cancelled onDrain stops waiting and onDestroy cancels the busy workers`() = runTest {
        val cancelled = CompletableDeferred<Unit>()
        val dispatcher = dispatcher(
            listener,
            TestObserver(seen, timeout = Duration.INFINITE, delivery = ObserverDelivery.ASYNC) {
                try {
                    awaitCancellation()
                } finally {
                    cancelled.complete(Unit)
                }
            },
        )
        dispatcher.fire(seen, "a")

        val drained = withTimeoutOrNull(100.milliseconds) { dispatcher.onDrain() }
        val waited = currentTime
        assertFalse(cancelled.isCompleted)
        dispatcher.onDestroy()
        advanceUntilIdle()

        assertNull(drained)
        assertEquals(100, waited)
        assertTrue(cancelled.isCompleted)
    }

    @Test
    fun `onDrain with nothing queued returns at once`() = runTest {
        val dispatcher =
            dispatcher(listener, TestObserver(seen, delivery = ObserverDelivery.ASYNC) { delay(1.seconds) })

        dispatcher.onDrain()

        assertEquals(0, currentTime)
        dispatcher.onDestroy()
    }

    @Test
    fun `onDrain and onDestroy are idempotent`() = runTest {
        val dispatcher = dispatcher(listener, TestObserver(seen, delivery = ObserverDelivery.ASYNC) {})

        repeat(2) { dispatcher.onDrain() }
        repeat(2) { dispatcher.onDestroy() }

        assertEquals(emptyList(), listener.all())
    }

    @Test
    fun `interceptors and inline observers keep working after onDestroy`() = runTest {
        val rewrite = rewritePoint()
        val dispatcher = dispatcher(
            listener,
            TestInterceptor(rewrite) { HookDecision.Replace("$it!") },
            TestObserver(seen) { records.record("inline $it") },
            TestObserver(seen, delivery = ObserverDelivery.ASYNC) { records.record("async $it") },
        )

        dispatcher.onDestroy()

        assertEquals(Interception.Proceed("x!"), dispatcher.fire(rewrite, "x"))
        dispatcher.fire(seen, "y")
        advanceUntilIdle()
        assertEquals(listOf("inline y"), records.all())
        assertEquals(emptyList(), listener.all())
    }
}
