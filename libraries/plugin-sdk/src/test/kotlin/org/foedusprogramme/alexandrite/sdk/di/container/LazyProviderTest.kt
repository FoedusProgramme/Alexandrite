package org.foedusprogramme.alexandrite.sdk.di.container

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LazyProviderTest {
    @Test
    fun `lazy resolves on first access`() {
        val container = build(service("a"))

        val lazy = container.lazy(svc("a"))

        assertFalse(lazy.isInitialized())
        assertSame(container.get(svc("a")), lazy.value)
        assertTrue(lazy.isInitialized())
    }

    @Test
    fun `lazy resolves only once`() {
        val container = build(service("a"))
        val resolved = container.lazy(svc("a")).also { it.value }
        val unresolved = container.lazy(svc("a"))

        container.close()

        assertEquals("a", resolved.value.name)
        assertFailsWith<DiException> { unresolved.value }
    }

    @Test
    fun `a LAZY dependency is injected unresolved`() {
        val container = build(service("a", dep("b", DependencyKind.LAZY)), service("b"))

        val lazyB = container.get(svc("a")).injected["b"] as Lazy<*>

        assertFalse(lazyB.isInitialized())
        assertSame(container.get(svc("b")), lazyB.value)
    }

    @Test
    fun `a provider returns the same singleton on every call`() {
        val container = build(service("a"))

        val provider = container.provider(svc("a"))

        assertSame(provider(), provider())
        assertSame(container.get(svc("a")), provider())
    }

    @Test
    fun `a provider resolves through the container on every call`() {
        val container = build(service("a"))
        val provider = container.provider(svc("a")).also { it() }

        container.close()

        assertFailsWith<DiException> { provider() }
    }

    @Test
    fun `lazy and provider need a binding`() {
        val container = build()

        assertFailsWith<DiException> { container.lazy(svc("a")) }
        assertFailsWith<DiException> { container.provider(svc("a")) }
    }

    // Concurrency.

    @Test
    fun `children created at once from one root are all registered and destroyed with it`() {
        val events = Events()
        val root = build(service("a", events = events), service("c", scope = Scope.CHANNEL_INSTANCE, events = events))

        val children = concurrently { index -> root.child("channel $index", setOf("test")) }
        root.close()

        assertEquals(children.size, children.toSet().size)
        assertEquals(List(children.size) { "destroy c" } + "destroy a", events.starting("destroy"))
        assertTrue(children.all { child -> runCatching { child.get(svc("c")) }.isFailure })
    }

    @Test
    fun `a child created while its root closes is destroyed exactly once, by the root or by itself`() {
        repeat(20) {
            val events = Events()
            val count = AtomicInteger()
            val channel = binding(svc("c"), "test", "c", scope = Scope.CHANNEL_INSTANCE) {
                Service("c${count.incrementAndGet()}", events, emptyMap())
            }
            val root = build(service("a", events = events), channel)
            val start = CountDownLatch(1)
            val failures = ConcurrentLinkedQueue<Throwable>()
            val creators = List(8) { index ->
                thread {
                    start.await()
                    repeat(5) {
                        runCatching { root.child("channel $index-$it", setOf("test")) }.onFailure(failures::add)
                    }
                }
            }
            val closer = thread {
                start.await()
                root.close()
            }

            start.countDown()
            (creators + closer).forEach { it.join() }

            assertTrue(
                failures.all {
                    (it as? DiException)?.problems?.single()?.kind == DiProblemKind.CLOSED
                },
                "$failures",
            )
            assertEquals(
                (1..count.get()).map { "destroy c$it" }.sorted(),
                events.starting("destroy c").sorted(),
            )
            assertEquals(listOf("destroy a"), events.starting("destroy a"))
        }
    }

    @Test
    fun `resolving while the container closes either resolves or fails as closed`() {
        repeat(20) {
            val root = build(service("a"), service("b", dep("a", DependencyKind.LAZY)))
            val lazyA = root.get(svc("b")).injected["a"] as Lazy<*>
            val start = CountDownLatch(1)
            val outcomes = ConcurrentLinkedQueue<Result<Any?>>()
            val resolvers = List(8) {
                thread {
                    start.await()
                    repeat(50) {
                        outcomes += runCatching { root.get(svc("a")) }
                        outcomes += runCatching { root.lazy(svc("a")).value }
                        outcomes += runCatching { lazyA.value }
                    }
                }
            }
            val closer = thread {
                start.await()
                root.close()
            }

            start.countDown()
            (resolvers + closer).forEach { it.join() }

            val failures = outcomes.mapNotNull { it.exceptionOrNull() }
            assertTrue(
                failures.all {
                    (it as? DiException)?.problems?.single()?.kind == DiProblemKind.CLOSED
                },
                "$failures",
            )
            assertTrue(outcomes.mapNotNull { it.getOrNull() }.toSet().size <= 1)
        }
    }

    private fun <T> concurrently(threads: Int = 16, action: (Int) -> T): List<T> {
        val start = CountDownLatch(1)
        val results = ConcurrentLinkedQueue<T>()
        val workers = List(threads) { index ->
            thread {
                start.await()
                results += action(index)
            }
        }
        start.countDown()
        workers.forEach { it.join() }
        assertEquals(threads, results.size)
        return results.toList()
    }
}
