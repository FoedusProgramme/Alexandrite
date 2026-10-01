package org.foedusprogramme.alexandrite.sdk.hook

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class InterceptTest {
    private val listener = RecordingListener()
    private val records = Records()
    private val rewrite = rewritePoint()

    private fun appending(suffix: String, order: Int = 0, point: InterceptorPoint<String> = rewrite) =
        TestInterceptor(point, order) {
            records.add(suffix)
            HookDecision.Replace(it + suffix)
        }

    // Order.

    @Test
    fun `interceptors run by ascending order and ties keep the list order`() = runTest {
        val hooks = listOf(appending("c", order = 5), appending("a", order = -1), appending("d", 5), appending("b"))

        val result = HookDispatcher(hooks).intercept(rewrite, "")

        assertEquals(Interception.Proceed("abcd"), result)
    }

    @Test
    fun `a hook runs only for its own point object`() = runTest {
        val other = InterceptorPoint<String>("test.other", setOf(HookEffect.REPLACE), FailurePolicy.SKIP)
        val dispatcher = HookDispatcher(listOf(appending("a"), appending("b", point = other)))

        val result = dispatcher.intercept(rewrite, "")
        val sameId = dispatcher.intercept(rewritePoint(), "")

        assertEquals(Interception.Proceed("a"), result)
        assertEquals(Interception.Proceed(""), sameId)
        assertEquals(listOf("a"), records.all())
    }

    @Test
    fun `two point objects sharing an id are rejected naming both hooks`() {
        val error = assertFailsWith<IllegalArgumentException> {
            HookDispatcher(listOf(Rewriter(rewritePoint()), Watcher(ObserverPoint("test.rewrite"))))
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

        val result = HookDispatcher(hooks).intercept(rewrite, "x")

        assertEquals(Interception.Proceed("xa!"), result)
        assertEquals(listOf("xa", "xa"), seen)
    }

    @Test
    fun `abort stops the chain and names the hook`() = runTest {
        val dispatcher = HookDispatcher(listOf(appending("a"), Denier(rewrite), appending("b", order = 1)), listener)

        val result = dispatcher.intercept(rewrite, "x")

        assertEquals(Interception.Aborted("denied", Denier::class.java.name), result)
        assertEquals(listOf("a"), records.all())
        assertEquals(emptyList(), listener.all())
    }

    @Test
    fun `without interceptors the original payload proceeds`() = runTest {
        assertEquals(Interception.Proceed("x"), HookDispatcher(emptyList()).intercept(rewrite, "x"))
    }

    // Disallowed decisions.

    @Test
    fun `a replace at a point without REPLACE is a disallowed decision`() = runTest {
        val point = InterceptorPoint<String>("test.deny", setOf(HookEffect.ABORT), FailurePolicy.SKIP)
        val next = TestInterceptor(point, order = 1) {
            records.add("next got $it")
            HookDecision.Continue
        }
        val dispatcher = HookDispatcher(listOf(Rewriter(point), next), listener)

        val result = dispatcher.intercept(point, "x")

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
        val point = InterceptorPoint<String>("test.replace", setOf(HookEffect.REPLACE), FailurePolicy.SKIP)
        val dispatcher = HookDispatcher(listOf(Denier(point), appending("b", order = 1, point = point)), listener)

        val result = dispatcher.intercept(point, "x")

        assertEquals(Interception.Proceed("xb"), result)
        assertEquals(
            listOf(Reported(Denier::class.java.name, point, HookFailure.Disallowed(HookDecision.Abort("denied")))),
            listener.all(),
        )
    }

    // Failure policy.

    @Test
    fun `a failing interceptor at a SKIP point is skipped with the payload unchanged`() = runTest {
        val dispatcher = HookDispatcher(listOf(appending("a"), Crasher(rewrite), appending("b", order = 1)), listener)

        val result = dispatcher.intercept(rewrite, "x")

        assertEquals(Interception.Proceed("xab"), result)
        val report = listener.all().single()
        assertEquals(Crasher::class.java.name, report.hook)
        assertEquals("crashed", assertIs<HookFailure.Threw>(report.failure).error.message)
    }

    @Test
    fun `a failing interceptor at an ABORT point stops the chain naming the failure`() = runTest {
        val guard = guardPoint()
        val dispatcher = HookDispatcher(listOf(Crasher(guard), appending("b", order = 1, point = guard)), listener)

        val result = dispatcher.intercept(guard, "x")

        val aborted = assertIs<Interception.Aborted>(result)
        assertEquals(Crasher::class.java.name, aborted.hook)
        assertEquals("Hook threw java.lang.IllegalStateException: crashed", aborted.reason)
        assertEquals(emptyList(), records.all())
        assertIs<HookFailure.Threw>(listener.all().single().failure)
    }

    @Test
    fun `a disallowed decision at an ABORT point stops the chain`() = runTest {
        val guard = guardPoint()

        val result = HookDispatcher(listOf(Rewriter(guard)), listener).intercept(guard, "x")

        assertEquals(
            Interception.Aborted(
                "Hook returned Replace, which point 'test.guard' does not allow",
                Rewriter::class.java.name,
            ),
            result,
        )
    }

    @Test
    fun `the ABORT policy applies even at a point that allows no abort decision`() = runTest {
        val point = InterceptorPoint<String>("test.strict", emptySet(), FailurePolicy.ABORT)

        val result = HookDispatcher(listOf(Crasher(point)), listener).intercept(point, "x")

        assertEquals(
            Interception.Aborted("Hook threw java.lang.IllegalStateException: crashed", Crasher::class.java.name),
            result,
        )
    }

    @Test
    fun `an exception thrown by the listener does not reach the caller`() = runTest {
        val dispatcher = HookDispatcher(listOf(Crasher(rewrite)), { _, _, _ -> error("listener failed") })

        assertEquals(Interception.Proceed("x"), dispatcher.intercept(rewrite, "x"))
    }

    @Test
    fun `a virtual machine error is not a hook failure`() = runTest {
        val exhausted = TestInterceptor(rewrite) { throw OutOfMemoryError("exhausted") }
        val dispatcher = HookDispatcher(listOf(exhausted), listener)

        assertFailsWith<OutOfMemoryError> { dispatcher.intercept(rewrite, "x") }
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
