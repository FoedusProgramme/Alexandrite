package org.foedusprogramme.alexandrite.runtime.hook

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.foedusprogramme.alexandrite.runtime.Events
import org.foedusprogramme.alexandrite.sdk.hook.FailurePolicy
import org.foedusprogramme.alexandrite.sdk.hook.Hook
import org.foedusprogramme.alexandrite.sdk.hook.HookDecision
import org.foedusprogramme.alexandrite.sdk.hook.HookEffect
import org.foedusprogramme.alexandrite.sdk.hook.HookFailure
import org.foedusprogramme.alexandrite.sdk.hook.HookPoint
import org.foedusprogramme.alexandrite.sdk.hook.Interception
import org.foedusprogramme.alexandrite.sdk.hook.InterceptorHook
import org.foedusprogramme.alexandrite.sdk.hook.InterceptorPoint
import org.foedusprogramme.alexandrite.sdk.hook.ObserverDelivery
import org.foedusprogramme.alexandrite.sdk.hook.ObserverHook
import org.foedusprogramme.alexandrite.sdk.hook.ObserverPoint
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
    private val records = Events()

    // Hook timeouts.

    @Test
    fun `an interceptor past its timeout is skipped at a fail-open point`() = runTest {
        val rewrite = rewritePoint()
        val dispatcher = hookDispatcher(
            listOf(
                TestInterceptor(rewrite, timeout = 1.seconds) {
                    delay(1.minutes)
                    HookDecision.Replace("late")
                },
                TestInterceptor(rewrite, order = 1) { HookDecision.Replace("$it!") },
            ),
            listener,
        )

        val result = dispatcher.fire(rewrite, "x")

        assertEquals(Interception.Proceed("x!"), result)
        assertEquals(1000, currentTime)
        assertEquals(HookFailure.TimedOut(1.seconds), listener.all().single().failure)
    }

    @Test
    fun `an interceptor past its timeout aborts a fail-closed point`() = runTest {
        val guard = guardPoint()
        val hook = TestInterceptor(guard, timeout = 2.seconds) { awaitCancellation() }

        val result = hookDispatcher(listOf(hook), listener).fire(guard, "x")

        assertEquals(Interception.Aborted(null, hook::class.java.name, HookFailure.TimedOut(2.seconds)), result)
        assertEquals(2000, currentTime)
    }

    @Test
    fun `an inline observer past its timeout is reported and later observers still run`() = runTest {
        val seen = seenPoint()
        val dispatcher = hookDispatcher(
            listOf(
                TestObserver(seen, timeout = 1.seconds) { awaitCancellation() },
                TestObserver(seen, order = 1) { records.record("second at $currentTime") },
            ),
            listener,
        )

        dispatcher.fire(seen, "x")

        assertEquals(listOf("second at 1000"), records.all())
        assertEquals(HookFailure.TimedOut(1.seconds), listener.all().single().failure)
    }

    // Caller cancellation.

    @Test
    fun `cancelling the caller stops the chain and is not a failure`() = runTest {
        val guard = guardPoint()
        val dispatcher = hookDispatcher(
            listOf(
                TestInterceptor(guard) {
                    try {
                        awaitCancellation()
                    } finally {
                        records.record("first cancelled")
                    }
                },
                TestInterceptor(guard, order = 1) {
                    records.record("second ran")
                    HookDecision.Continue
                },
            ),
            listener,
        )

        val caller = launch {
            try {
                dispatcher.fire(guard, "x")
                records.record("caller returned")
            } catch (e: CancellationException) {
                records.record("caller cancelled")
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
        val dispatcher = hookDispatcher(
            listOf(
                TestObserver(seen) { awaitCancellation() },
                TestObserver(seen, order = 1) { records.record("second ran") },
            ),
            listener,
        )

        val caller = launch { dispatcher.fire(seen, "x") }
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
        val dispatcher = hookDispatcher(listOf(TestInterceptor(guard) { awaitCancellation() }), listener)

        val result = withTimeoutOrNull(1.seconds) { dispatcher.fire(guard, "x") }

        assertNull(result)
        assertEquals(1000, currentTime)
        assertEquals(emptyList(), listener.all())
    }

    @Test
    fun `a CancellationException thrown while the caller is active is a failure`() = runTest {
        val rewrite = rewritePoint()
        val dispatcher =
            hookDispatcher(
                listOf(
                    TestInterceptor(rewrite) {
                        throw CancellationException("not the caller's")
                    },
                ),
                listener,
            )

        val result = dispatcher.fire(rewrite, "x")

        assertEquals(Interception.Proceed("x"), result)
        val failure = assertIs<HookFailure.Threw>(listener.all().single().failure)
        assertEquals("not the caller's", failure.error.message)
    }
}
