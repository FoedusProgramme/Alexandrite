package org.foedusprogramme.alexandrite.sdk.di

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LifecycleTest {
    private val events = Events()

    @Test
    fun `singletons are created in dependency order`() {
        build(
            service("c", dep("b"), dep("tools", DependencyKind.ALL), events = events),
            service("b", dep("a"), dep("d", DependencyKind.OPTIONAL), events = events),
            service("a", events = events),
            service("d", events = events),
            service("t", key = svc("tools"), multi = true, events = events),
        )

        assertEquals(listOf("create a", "create d", "create b", "create t", "create c"), events.all())
    }

    @Test
    fun `each binding is created once per container`() {
        val container = build(service("a", events = events), service("b", dep("a"), events = events))

        repeat(3) { container.get(svc("a")) }

        assertEquals(listOf("create a", "create b"), events.all())
    }

    @Test
    fun `channel bindings are not created in the root container`() {
        build(service("a", events = events), service("c", scope = Scope.CHANNEL, events = events))

        assertEquals(listOf("create a"), events.all())
    }

    @Test
    fun `start starts instances in creation order`() {
        val container = build(service("b", dep("a"), events = events), service("a", events = events))

        runBlocking { container.start() }

        assertEquals(listOf("start a", "start b"), events.starting("start"))
    }

    @Test
    fun `a failing start closes the started instances in reverse order and rethrows`() {
        val container = build(
            service("a", events = events),
            service("b", events = events),
            service("c", events = events, failStart = true),
            service("d", events = events),
        )

        val error = assertFailsWith<IllegalStateException> { runBlocking { container.start() } }

        assertEquals("start c failed", error.message)
        assertEquals(listOf("start a", "start b", "start c", "close b", "close a"), events.all().drop(4))
    }

    @Test
    fun `close after a failed start skips the instances already closed`() {
        val container =
            build(
                service("a", events = events),
                service("b", events = events, failStart = true),
                service("c", events = events),
            )
        assertFailsWith<IllegalStateException> { runBlocking { container.start() } }

        container.close()

        assertEquals(listOf("close a", "close c", "close b"), events.starting("close"))
    }

    @Test
    fun `start twice is rejected`() {
        val container = build(service("a"))
        runBlocking { container.start() }

        assertFailsWith<DiException> { runBlocking { container.start() } }
    }

    @Test
    fun `close closes instances in reverse creation order`() {
        val container =
            build(service("b", dep("a"), events = events), service("a", events = events), service("c", events = events))

        container.close()

        assertEquals(listOf("close c", "close b", "close a"), events.starting("close"))
    }

    @Test
    fun `close continues past failures and throws the first with the others suppressed`() {
        val container = build(
            service("a", events = events),
            service("b", events = events, failClose = true),
            service("c", events = events, failClose = true),
        )

        val error = assertFailsWith<IllegalStateException> { container.close() }

        assertEquals("close c failed", error.message)
        assertEquals(listOf("close b failed"), error.suppressed.map { it.message })
        assertEquals(listOf("close c", "close b", "close a"), events.starting("close"))
    }

    @Test
    fun `close is idempotent`() {
        val container = build(service("a", events = events))

        container.close()
        container.close()

        assertEquals(listOf("close a"), events.starting("close"))
    }

    @Test
    fun `a closed container cannot be used`() {
        val container = build(service("a"))
        container.close()

        assertFailsWith<DiException> { container.get(svc("a")) }
        assertFailsWith<DiException> { container.getOrNull(svc("a")) }
        assertFailsWith<DiException> { container.getAll(svc("tools")) }
        assertFailsWith<DiException> { container.lazy(svc("a")) }
        assertFailsWith<DiException> { container.provider(svc("a")) }
        assertFailsWith<DiException> { container.child("tg") }
        assertFailsWith<DiException> { runBlocking { container.start() } }
    }

    @Test
    fun `a failing constructor names its origin and closes what was created`() {
        val error = assertFailsWith<DiException> {
            build(service("a", events = events), service("b", dep("a"), events = events, failCreate = true))
        }

        assertTrue("b (module test)" in error.message!!)
        assertIs<IllegalStateException>(error.cause)
        assertEquals(listOf("create a", "close a"), events.all())
    }

    // Managed instances.

    @Test
    fun `a managed binding is started and closed`() {
        val container = build(service("a", events = events))

        runBlocking { container.start() }
        container.close()

        assertEquals(listOf("create a", "start a", "close a"), events.all())
    }

    @Test
    fun `an instance binding is neither started nor closed`() {
        val given = Service("a", events, emptyMap(), failStart = false, failClose = false)
        val container =
            build(instanceBinding(svc("a"), given, "a (module test)"), service("b", dep("a"), events = events))

        runBlocking { container.start() }
        container.close()

        assertEquals(listOf("create b", "start b", "close b"), events.all())
    }

    @Test
    fun `an unmanaged binding is neither started nor closed`() {
        val container = build(service("a", events = events, managed = false), service("b", dep("a"), events = events))

        runBlocking { container.start() }
        container.close()

        assertEquals(listOf("create a", "create b", "start b", "close b"), events.all())
    }
}
