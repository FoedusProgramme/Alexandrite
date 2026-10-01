package org.foedusprogramme.alexandrite.sdk.hook

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class CancellationTest {
    private val listener = RecordingListener()
    private val records = Records()

    // Hook timeouts.

    @Test
    fun `an interceptor past its timeout is skipped at a SKIP point`() = runTest {
        val rewrite = rewritePoint()
        val dispatcher = HookDispatcher(
            listOf(
                TestInterceptor(rewrite, timeout = 1.seconds) {
                    delay(1.minutes)
                    HookDecision.Replace("late")
                },
                TestInterceptor(rewrite, order = 1) { HookDecision.Replace("$it!") },
            ),
            listener,
        )

        val result = dispatcher.intercept(rewrite, "x")

        assertEquals(Interception.Proceed("x!"), result)
        assertEquals(1000, currentTime)
        assertEquals(HookFailure.TimedOut(1.seconds), listener.all().single().failure)
    }

    @Test
    fun `an interceptor past its timeout aborts an ABORT point`() = runTest {
        val guard = guardPoint()
        val hook = TestInterceptor(guard, timeout = 2.seconds) { awaitCancellation() }

        val result = HookDispatcher(listOf(hook), listener).intercept(guard, "x")

        assertEquals(Interception.Aborted("Hook timed out after 2s", hook::class.java.name), result)
        assertEquals(2000, currentTime)
    }

    @Test
    fun `an inline observer past its timeout is reported and later observers still run`() = runTest {
        val seen = seenPoint()
        val dispatcher = HookDispatcher(
            listOf(
                TestObserver(seen, timeout = 1.seconds) { awaitCancellation() },
                TestObserver(seen, order = 1) { records.add("second at $currentTime") },
            ),
            listener,
        )

        dispatcher.observe(seen, "x")

        assertEquals(listOf("second at 1000"), records.all())
        assertEquals(HookFailure.TimedOut(1.seconds), listener.all().single().failure)
    }

    // Caller cancellation.

    @Test
    fun `cancelling the caller stops the chain and is not a failure`() = runTest {
        val guard = guardPoint()
        val dispatcher = HookDispatcher(
            listOf(
                TestInterceptor(guard) {
                    try {
                        awaitCancellation()
                    } finally {
                        records.add("first cancelled")
                    }
                },
                TestInterceptor(guard, order = 1) {
                    records.add("second ran")
                    HookDecision.Continue
                },
            ),
            listener,
        )

        val caller = launch {
            try {
                dispatcher.intercept(guard, "x")
                records.add("caller returned")
            } catch (e: CancellationException) {
                records.add("caller cancelled")
                throw e
            }
        }
        runCurrent()
        caller.cancel()
        caller.join()

        assertEquals(listOf("first cancelled", "caller cancelled"), records.all())
        assertEquals(emptyList(), listener.all())
    }

    @Test
    fun `cancelling the caller stops inline observers and is not a failure`() = runTest {
        val seen = seenPoint()
        val dispatcher = HookDispatcher(
            listOf(
                TestObserver(seen) { awaitCancellation() },
                TestObserver(seen, order = 1) { records.add("second ran") },
            ),
            listener,
        )

        val caller = launch { dispatcher.observe(seen, "x") }
        runCurrent()
        caller.cancel()
        caller.join()

        assertTrue(caller.isCancelled)
        assertEquals(emptyList(), records.all())
        assertEquals(emptyList(), listener.all())
    }

    @Test
    fun `the caller's own timeout is not reported as the hook's`() = runTest {
        val guard = guardPoint()
        val dispatcher = HookDispatcher(listOf(TestInterceptor(guard) { awaitCancellation() }), listener)

        val result = withTimeoutOrNull(1.seconds) { dispatcher.intercept(guard, "x") }

        assertNull(result)
        assertEquals(1000, currentTime)
        assertEquals(emptyList(), listener.all())
    }

    @Test
    fun `a CancellationException thrown while the caller is active is a failure`() = runTest {
        val rewrite = rewritePoint()
        val dispatcher =
            HookDispatcher(
                listOf(
                    TestInterceptor(rewrite) {
                        throw CancellationException("not the caller's")
                    },
                ),
                listener,
            )

        val result = dispatcher.intercept(rewrite, "x")

        assertEquals(Interception.Proceed("x"), result)
        val failure = assertIs<HookFailure.Threw>(listener.all().single().failure)
        assertEquals("not the caller's", failure.error.message)
    }
}
