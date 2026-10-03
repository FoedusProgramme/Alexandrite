package org.foedusprogramme.alexandrite.sdk.di

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
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
    fun `concurrent lazy access resolves one instance`() {
        val container = build(service("a", dep("b", DependencyKind.LAZY)), service("b"))
        val lazyB = container.get(svc("a")).injected["b"] as Lazy<*>

        val results = concurrently { lazyB.value }

        assertTrue(results.all { it === container.get(svc("b")) })
    }

    @Test
    fun `concurrent resolution from a root and its children is safe`() {
        val root = build(service("a"), service("c", dep("a"), scope = Scope.CHANNEL_INSTANCE))
        val children = List(4) { root.child("channel $it", setOf("test")) }

        val results = concurrently { index -> children[index % children.size].get(svc("c")) }

        assertEquals(children.map { it.get(svc("c")) }.toSet(), results.toSet())
        assertTrue(results.all { it.dependency("a") === root.get(svc("a")) })
    }

    private fun <T> concurrently(threads: Int = 16, action: (Int) -> T): List<T> {
        val start = CountDownLatch(1)
        val results = ConcurrentLinkedQueue<T>()
        val workers = List(threads) { index ->
            thread {
                start.await()
                repeat(100) { results += action(index) }
            }
        }
        start.countDown()
        workers.forEach { it.join() }
        assertEquals(threads * 100, results.size)
        return results.toList()
    }
}
