package org.foedusprogramme.alexandrite.sdk.hook

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

class ConcurrencyTest {
    @Test
    fun `concurrent calls each run their own chain and reach every observer`() = runBlocking<Unit> {
        val calls = 1000
        val rewrite = rewritePoint()
        val seen = seenPoint()
        val listener = RecordingListener()
        val inline = AtomicInteger()
        val queued = Records()
        val dispatcher = HookDispatcher(
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
                TestObserver(seen, delivery = Delivery.ASYNC) { queued.add(it) },
            ),
            listener,
            asyncCapacity = calls,
        )

        val results = (0 until calls).map { i ->
            async(Dispatchers.Default) {
                dispatcher.intercept(rewrite, "p$i").also { dispatcher.observe(seen, "p$i") }
            }
        }.awaitAll()
        dispatcher.close()

        assertEquals((0 until calls).map { Interception.Proceed("p${it}ab") }, results)
        assertEquals(calls, inline.get())
        assertEquals((0 until calls).map { "p$it" }.sorted(), queued.all().sorted())
        assertEquals(emptyList(), listener.all())
    }
}
