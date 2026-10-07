package org.foedusprogramme.alexandrite.runtime.hook

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.foedusprogramme.alexandrite.runtime.Events
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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class ConcurrencyTest {
    @Test
    fun `concurrent calls each run their own chain and reach every observer`() = runBlocking<Unit> {
        val calls = 1000
        val rewrite = rewritePoint()
        val seen = seenPoint()
        val listener = RecordingListener()
        val inline = AtomicInteger()
        val queued = Events()
        val dispatcher = hookDispatcher(
            listOf(
                TestInterceptor(rewrite, order = 1) {
                    yield()
                    HookDecision.Replace(it + "b")
                },
                TestInterceptor(rewrite) {
                    yield()
                    HookDecision.Replace(it + "a")
                },
                TestObserver(seen) { inline.incrementAndGet() },
                TestObserver(seen, delivery = Delivery.ASYNC) { queued.record(it) },
            ),
            listener,
            asyncCapacity = calls,
        )

        val results = (0 until calls).map { i ->
            async(Dispatchers.Default) {
                dispatcher.fire(rewrite, "p$i").also { dispatcher.fire(seen, "p$i") }
            }
        }.awaitAll()
        withTimeout(10.seconds) { dispatcher.onDrain() }
        dispatcher.onDestroy()

        assertEquals((0 until calls).map { Interception.Proceed("p${it}ab") }, results)
        assertEquals(calls, inline.get())
        assertEquals((0 until calls).map { "p$it" }.sorted(), queued.all().sorted())
        assertEquals(emptyList(), listener.all())
    }
}
