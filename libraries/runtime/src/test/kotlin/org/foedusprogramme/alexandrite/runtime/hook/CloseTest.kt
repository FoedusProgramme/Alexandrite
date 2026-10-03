package org.foedusprogramme.alexandrite.runtime.hook

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import org.foedusprogramme.alexandrite.sdk.hook.Delivery
import org.foedusprogramme.alexandrite.sdk.hook.Hook
import org.foedusprogramme.alexandrite.sdk.hook.HookDecision
import org.foedusprogramme.alexandrite.sdk.hook.Interception
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class CloseTest {
    private val listener = RecordingListener()
    private val records = Records()
    private val seen = seenPoint()

    private fun TestScope.dispatcher(vararg hooks: Hook) =
        HookDispatcher(hooks.toList(), listener, asyncDispatcher = StandardTestDispatcher(testScheduler))

    @Test
    fun `drain delivers the queued events, then later events are dropped silently`() = runTest {
        val dispatcher = dispatcher(
            TestObserver(seen, delivery = Delivery.ASYNC) {
                delay(1.seconds)
                records.add(it)
            },
        )
        listOf("a", "b", "c").forEach { dispatcher.fire(seen, it) }

        dispatcher.drain(testTimeSource.markNow() + 1.minutes)
        dispatcher.fire(seen, "d")
        advanceUntilIdle()

        assertEquals(listOf("a", "b", "c"), records.all())
        assertEquals(3000, currentTime)
        assertEquals(emptyList(), listener.all())
    }

    @Test
    fun `drain stops waiting at the deadline and close cancels the busy workers`() = runTest {
        val cancelled = CompletableDeferred<Unit>()
        val dispatcher = dispatcher(
            TestObserver(seen, timeout = Duration.INFINITE, delivery = Delivery.ASYNC) {
                try {
                    awaitCancellation()
                } finally {
                    cancelled.complete(Unit)
                }
            },
        )
        dispatcher.fire(seen, "a")

        dispatcher.drain(testTimeSource.markNow() + 100.milliseconds)
        val drained = currentTime
        assertFalse(cancelled.isCompleted)
        dispatcher.close()
        advanceUntilIdle()

        assertEquals(100, drained)
        assertTrue(cancelled.isCompleted)
    }

    @Test
    fun `drain past its deadline returns at once`() = runTest {
        val dispatcher = dispatcher(TestObserver(seen, delivery = Delivery.ASYNC) { delay(1.seconds) })
        dispatcher.fire(seen, "a")

        dispatcher.drain(testTimeSource.markNow() - 1.seconds)

        assertEquals(0, currentTime)
        dispatcher.close()
    }

    @Test
    fun `drain and close are idempotent`() = runTest {
        val dispatcher = dispatcher(TestObserver(seen, delivery = Delivery.ASYNC) {})

        repeat(2) { dispatcher.drain(testTimeSource.markNow() + 1.seconds) }
        repeat(2) { dispatcher.close() }

        assertEquals(emptyList(), listener.all())
    }

    @Test
    fun `interceptors and inline observers keep working after close`() = runTest {
        val rewrite = rewritePoint()
        val dispatcher = dispatcher(
            TestInterceptor(rewrite) { HookDecision.Replace("$it!") },
            TestObserver(seen) { records.add("inline $it") },
            TestObserver(seen, delivery = Delivery.ASYNC) { records.add("async $it") },
        )

        dispatcher.close()

        assertEquals(Interception.Proceed("x!"), dispatcher.fire(rewrite, "x"))
        dispatcher.fire(seen, "y")
        advanceUntilIdle()
        assertEquals(listOf("inline y"), records.all())
        assertEquals(emptyList(), listener.all())
    }
}
