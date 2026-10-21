package org.unitedsoftware.alexandrite.runtime.hook

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.unitedsoftware.alexandrite.runtime.Events
import org.unitedsoftware.alexandrite.sdk.hook.HookDecision
import org.unitedsoftware.alexandrite.sdk.hook.Interception
import org.unitedsoftware.alexandrite.sdk.hook.ObserverDelivery
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
                TestObserver(seen, delivery = ObserverDelivery.ASYNC) { queued.record(it) },
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
