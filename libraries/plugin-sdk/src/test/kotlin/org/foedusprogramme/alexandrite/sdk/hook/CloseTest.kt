package org.foedusprogramme.alexandrite.sdk.hook

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

class CloseTest {
    private val listener = RecordingListener()
    private val records = Records()
    private val seen = seenPoint()

    @Test
    fun `close drains the queues, then drops later events silently`() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>()
        val dispatcher = HookDispatcher(
            listOf(
                TestObserver(seen, delivery = Delivery.ASYNC) {
                    gate.await()
                    records.add(it)
                },
            ),
            listener,
        )
        listOf("a", "b", "c").forEach { dispatcher.observe(seen, it) }

        launch(Dispatchers.Default) {
            delay(50.milliseconds)
            gate.complete(Unit)
        }
        dispatcher.close()
        dispatcher.observe(seen, "d")

        assertEquals(listOf("a", "b", "c"), records.all())
        assertEquals(emptyList(), listener.all())
    }

    @Test
    fun `close cancels the workers still busy after the grace`() = runBlocking<Unit> {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val dispatcher = HookDispatcher(
            listOf(
                TestObserver(seen, timeout = Duration.INFINITE, delivery = Delivery.ASYNC) {
                    started.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled.complete(Unit)
                    }
                },
            ),
            listener,
            closeGrace = 100.milliseconds,
        )
        dispatcher.observe(seen, "a")
        started.await()

        val elapsed = measureTime { dispatcher.close() }

        withTimeout(5.seconds) { cancelled.await() }
        assertTrue(elapsed >= 100.milliseconds, "close returned after $elapsed")
    }

    @Test
    fun `close is idempotent`() {
        val dispatcher = HookDispatcher(listOf(TestObserver(seen, delivery = Delivery.ASYNC) {}), listener)

        dispatcher.close()
        dispatcher.close()

        assertEquals(emptyList(), listener.all())
    }

    @Test
    fun `interceptors and inline observers keep working after close`() = runBlocking<Unit> {
        val rewrite = rewritePoint()
        val dispatcher = HookDispatcher(
            listOf(
                TestInterceptor(rewrite) { HookDecision.Replace("$it!") },
                TestObserver(seen) { records.add("inline $it") },
                TestObserver(seen, delivery = Delivery.ASYNC) { records.add("async $it") },
            ),
            listener,
        )

        dispatcher.close()

        assertEquals(Interception.Proceed("x!"), dispatcher.intercept(rewrite, "x"))
        dispatcher.observe(seen, "y")
        assertEquals(listOf("inline y"), records.all())
        assertEquals(emptyList(), listener.all())
    }
}
