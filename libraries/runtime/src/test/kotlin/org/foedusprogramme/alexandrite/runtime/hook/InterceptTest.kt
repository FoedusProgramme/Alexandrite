package org.foedusprogramme.alexandrite.runtime.hook

import kotlinx.coroutines.test.runTest
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
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class InterceptTest {
    private val listener = RecordingListener()
    private val records = Events()
    private val rewrite = rewritePoint()

    private fun appending(suffix: String, order: Int = 0, point: InterceptorPoint<String> = rewrite) =
        TestInterceptor(point, order) {
            records.record(suffix)
            HookDecision.Replace(it + suffix)
        }

    // Order.

    @Test
    fun `interceptors run by ascending order and ties keep the list order`() = runTest {
        val hooks = listOf(appending("c", order = 5), appending("a", order = -1), appending("d", 5), appending("b"))

        val result = hookDispatcher(hooks).fire(rewrite, "")

        assertEquals(Interception.Proceed("abcd"), result)
    }

    @Test
    fun `a hook runs only for its own point`() = runTest {
        val other = InterceptorPoint<String>("test.other", setOf(HookEffect.REPLACE), FailurePolicy.FAIL_OPEN)
        val dispatcher = hookDispatcher(listOf(appending("a"), appending("b", point = other)))

        val result = dispatcher.fire(rewrite, "")

        assertEquals(Interception.Proceed("a"), result)
        assertEquals(listOf("a"), records.all())
    }

    @Test
    fun `firing another point object with a subscribed id is rejected`() = runTest {
        val dispatcher = hookDispatcher(listOf(appending("a")))

        val error = assertFailsWith<IllegalArgumentException> { dispatcher.fire(rewritePoint(), "") }

        assertEquals(
            "Cannot fire hook point 'test.rewrite': its hooks subscribe to another point object with this id.",
            error.message,
        )
        assertEquals(emptyList(), records.all())
    }

    @Test
    fun `two point objects sharing an id are rejected naming both hooks`() {
        val error = assertFailsWith<IllegalArgumentException> {
            hookDispatcher(listOf(Rewriter(rewritePoint()), Watcher(ObserverPoint("test.rewrite"))))
        }

        assertContains(error.message!!, "'test.rewrite'")
        assertContains(error.message!!, Rewriter::class.java.name)
        assertContains(error.message!!, Watcher::class.java.name)
    }

    // Chain.

    @Test
    fun `each interceptor gets the payload as the previous one left it`() = runTest {
        val seen = mutableListOf<String>()
        val hooks = listOf(
            appending("a", order = 0),
            TestInterceptor(rewrite, order = 1) {
                seen += it
                HookDecision.Continue
            },
            TestInterceptor(rewrite, order = 2) {
                seen += it
                HookDecision.Replace("$it!")
            },
        )

        val result = hookDispatcher(hooks).fire(rewrite, "x")

        assertEquals(Interception.Proceed("xa!"), result)
        assertEquals(listOf("xa", "xa"), seen)
    }

    @Test
    fun `abort stops the chain and names the hook`() = runTest {
        val dispatcher = hookDispatcher(listOf(appending("a"), Denier(rewrite), appending("b", order = 1)), listener)

        val result = dispatcher.fire(rewrite, "x")

        assertEquals(Interception.Aborted("denied", Denier::class.java.name, null), result)
        assertEquals(listOf("a"), records.all())
        assertEquals(emptyList(), listener.all())
    }

    @Test
    fun `without interceptors the original payload proceeds`() = runTest {
        assertEquals(Interception.Proceed("x"), hookDispatcher(emptyList()).fire(rewrite, "x"))
    }

    // Disallowed decisions.

    @Test
    fun `a replace at a point without REPLACE is a disallowed decision`() = runTest {
        val point = InterceptorPoint<String>("test.deny", setOf(HookEffect.ABORT), FailurePolicy.FAIL_OPEN)
        val next = TestInterceptor(point, order = 1) {
            records.record("next got $it")
            HookDecision.Continue
        }
        val dispatcher = hookDispatcher(listOf(Rewriter(point), next), listener)

        val result = dispatcher.fire(point, "x")

        assertEquals(Interception.Proceed("x"), result)
        assertEquals(listOf("next got x"), records.all())
        assertEquals(
            listOf(
                Reported(Rewriter::class.java.name, point, HookFailure.Disallowed(HookDecision.Replace("rewritten"))),
            ),
            listener.all(),
        )
    }

    @Test
    fun `an abort at a point without ABORT is a disallowed decision`() = runTest {
        val point = InterceptorPoint<String>("test.replace", setOf(HookEffect.REPLACE), FailurePolicy.FAIL_OPEN)
        val dispatcher = hookDispatcher(listOf(Denier(point), appending("b", order = 1, point = point)), listener)

        val result = dispatcher.fire(point, "x")

        assertEquals(Interception.Proceed("xb"), result)
        assertEquals(
            listOf(Reported(Denier::class.java.name, point, HookFailure.Disallowed(HookDecision.Abort("denied")))),
            listener.all(),
        )
    }

    // Failure policy.

    @Test
    fun `a failing interceptor at a fail-open point is skipped with the payload unchanged`() = runTest {
        val dispatcher = hookDispatcher(listOf(appending("a"), Crasher(rewrite), appending("b", order = 1)), listener)

        val result = dispatcher.fire(rewrite, "x")

        assertEquals(Interception.Proceed("xab"), result)
        val report = listener.all().single()
        assertEquals(Crasher::class.java.name, report.hook)
        assertEquals("crashed", assertIs<HookFailure.Threw>(report.failure).error.message)
    }

    @Test
    fun `a failing interceptor at a fail-closed point stops the chain with the failure and no reply`() = runTest {
        val guard = guardPoint()
        val dispatcher = hookDispatcher(listOf(Crasher(guard), appending("b", order = 1, point = guard)), listener)

        val result = dispatcher.fire(guard, "x")

        val aborted = assertIs<Interception.Aborted>(result)
        assertEquals(Crasher::class.java.name, aborted.hook)
        assertNull(aborted.reply)
        assertEquals("crashed", assertIs<HookFailure.Threw>(aborted.failure).error.message)
        assertEquals(emptyList(), records.all())
        assertEquals(aborted.failure, listener.all().single().failure)
    }

    @Test
    fun `a disallowed decision at a fail-closed point stops the chain`() = runTest {
        val guard = guardPoint()

        val result = hookDispatcher(listOf(Rewriter(guard)), listener).fire(guard, "x")

        assertEquals(
            Interception.Aborted(
                null,
                Rewriter::class.java.name,
                HookFailure.Disallowed(HookDecision.Replace("rewritten")),
            ),
            result,
        )
    }

    @Test
    fun `the fail-closed policy applies even at a point that allows no abort decision`() = runTest {
        val point = InterceptorPoint<String>("test.strict", emptySet(), FailurePolicy.FAIL_CLOSED)

        val result = hookDispatcher(listOf(Crasher(point)), listener).fire(point, "x")

        val aborted = assertIs<Interception.Aborted>(result)
        assertEquals(Crasher::class.java.name, aborted.hook)
        assertIs<HookFailure.Threw>(aborted.failure)
    }

    @Test
    fun `an exception thrown by the listener does not reach the caller`() = runTest {
        val dispatcher = hookDispatcher(listOf(Crasher(rewrite)), { _, _, _ -> error("listener failed") })

        assertEquals(Interception.Proceed("x"), dispatcher.fire(rewrite, "x"))
    }

    @Test
    fun `a virtual machine error is not a hook failure`() = runTest {
        val exhausted = TestInterceptor(rewrite) { throw OutOfMemoryError("exhausted") }
        val dispatcher = hookDispatcher(listOf(exhausted), listener)

        assertFailsWith<OutOfMemoryError> { dispatcher.fire(rewrite, "x") }
        assertEquals(emptyList(), listener.all())
    }

    private class Rewriter(point: InterceptorPoint<String>) :
        TestInterceptor<String>(point, block = { HookDecision.Replace("rewritten") })

    private class Denier(point: InterceptorPoint<String>) :
        TestInterceptor<String>(point, block = { HookDecision.Abort("denied") })

    private class Crasher(point: InterceptorPoint<String>) :
        TestInterceptor<String>(point, block = { error("crashed") })

    private class Watcher(point: ObserverPoint<String>) : TestObserver<String>(point, block = {})
}
