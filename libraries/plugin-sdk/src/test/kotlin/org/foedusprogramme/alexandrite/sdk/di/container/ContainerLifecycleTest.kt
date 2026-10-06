package org.foedusprogramme.alexandrite.sdk.di.container

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.foedusprogramme.alexandrite.sdk.di.Lifecycle
import org.foedusprogramme.alexandrite.sdk.di.key
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class ContainerLifecycleTest {
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
        build(service("a", events = events), service("c", scope = Scope.CHANNEL_INSTANCE, events = events))

        assertEquals(listOf("create a"), events.all())
    }

    @Test
    fun `start starts instances in creation order`() {
        val container = build(service("b", dep("a"), events = events), service("a", events = events))

        runBlocking { container.start() }

        assertEquals(listOf("start a", "start b"), events.lifecycle())
    }

    @Test
    fun `a failing start stops the started instances in reverse order and rethrows`() {
        val container = build(
            service("a", events = events, failStop = true),
            service("b", events = events),
            service("c", events = events, failStart = true),
            service("d", events = events),
        )

        val error = assertFailsWith<IllegalStateException> { runBlocking { container.start() } }

        assertEquals("start c failed", error.message)
        assertEquals(listOf("stop a failed"), error.suppressed.map { it.message })
        assertEquals(listOf("start a", "start b", "start c", "stop b", "stop a"), events.lifecycle())
    }

    @Test
    fun `close during a start ends it before the next instance and calls nothing after destroy`() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>()
        val container = build(service("a", events = events, startGate = gate), service("b", events = events))
        val starting = async(start = CoroutineStart.UNDISPATCHED) {
            assertFailsWith<DiException> { container.start() }
        }

        container.close()
        gate.complete(Unit)

        assertEquals(listOf(DiProblemKind.CLOSED), starting.await().problems.map { it.kind })
        assertEquals(listOf("start a", "destroy b", "destroy a"), events.lifecycle())
    }

    @Test
    fun `a start that fails after close never stops or destroys an instance twice`() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>()
        val container = build(
            service("a", events = events),
            service("b", events = events, failStart = true, startGate = gate),
            service("c", events = events),
        )
        val starting = async(start = CoroutineStart.UNDISPATCHED) {
            assertFailsWith<IllegalStateException> { container.start() }
        }

        container.close()
        gate.complete(Unit)

        assertEquals("start b failed", starting.await().message)
        assertEquals(
            listOf("start a", "start b", "stop a", "destroy c", "destroy b", "destroy a"),
            events.lifecycle(),
        )
    }

    @Test
    fun `close after a failed start destroys every instance and stops none again`() {
        val container =
            build(
                service("a", events = events),
                service("b", events = events, failStart = true),
                service("c", events = events),
            )
        assertFailsWith<IllegalStateException> { runBlocking { container.start() } }

        container.close()

        assertEquals(
            listOf("start a", "start b", "stop a", "destroy c", "destroy b", "destroy a"),
            events.lifecycle(),
        )
    }

    @Test
    fun `start twice is rejected`() {
        val container = build(service("a"))
        runBlocking { container.start() }

        assertFailsWith<DiException> { runBlocking { container.start() } }
    }

    @Test
    fun `close destroys instances in reverse creation order`() {
        val container =
            build(service("b", dep("a"), events = events), service("a", events = events), service("c", events = events))

        container.close()

        assertEquals(listOf("destroy c", "destroy b", "destroy a"), events.lifecycle())
    }

    @Test
    fun `close without stop still stops the started instances before destroying them`() {
        val container = build(service("a", events = events), service("b", events = events))
        runBlocking { container.start() }

        container.close()

        assertEquals(listOf("start a", "start b", "stop b", "stop a", "destroy b", "destroy a"), events.lifecycle())
    }

    @Test
    fun `close continues past failures and throws the first with the others suppressed`() {
        val container = build(
            service("a", events = events),
            service("b", events = events, failDestroy = true),
            service("c", events = events, failStop = true, failDestroy = true),
        )
        runBlocking { container.start() }

        val error = assertFailsWith<IllegalStateException> { container.close() }

        assertEquals("stop c failed", error.message)
        assertEquals(listOf("destroy c failed", "destroy b failed"), error.suppressed.map { it.message })
        assertEquals(
            listOf("stop c", "stop b", "stop a", "destroy c", "destroy b", "destroy a"),
            events.lifecycle().drop(3),
        )
    }

    @Test
    fun `close is idempotent`() {
        val container = build(service("a", events = events))
        runBlocking { container.start() }

        container.close()
        container.close()

        assertEquals(listOf("start a", "stop a", "destroy a"), events.lifecycle())
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
        assertFailsWith<DiException> { container.child("tg", setOf("test")) }
        assertFailsWith<DiException> { runBlocking { container.start() } }
    }

    @Test
    fun `a failing constructor names its origin and destroys what was created`() {
        val error = assertFailsWith<DiException> {
            build(service("a", events = events), service("b", dep("a"), events = events, failCreate = true))
        }

        assertContains(error.message!!, "Cannot create ${svc("b")} with b (plugin test): ")
        assertIs<IllegalStateException>(error.cause)
        assertEquals(listOf("create a", "destroy a"), events.all())
    }

    // Managed instances.

    private class Client(private val name: String, private val events: Events) : AutoCloseable {
        override fun close() = events.record("auto-close $name")
    }

    private class Pool(private val events: Events) :
        Lifecycle,
        AutoCloseable {
        override fun onStop() = events.record("stop pool")

        override fun onDestroy() {
            events.record("destroy pool")
            error("destroy pool failed")
        }

        override fun close() = events.record("auto-close pool")
    }

    @Test
    fun `a managed binding is started, stopped and destroyed`() {
        val container = build(service("a", events = events))

        runBlocking { container.start() }
        container.close()

        assertEquals(listOf("create a", "start a", "stop a", "destroy a"), events.all())
    }

    @Test
    fun `an instance binding is neither started nor destroyed`() {
        val given = Service("a", events, emptyMap())
        val container =
            build(instanceBinding(svc("a"), given, "test", "a"), service("b", dep("a"), events = events))

        runBlocking { container.start() }
        container.close()

        assertEquals(listOf("create b", "start b", "stop b", "destroy b"), events.all())
    }

    @Test
    fun `an unmanaged binding is neither started nor destroyed`() {
        val container = build(service("a", events = events, managed = false), service("b", dep("a"), events = events))

        runBlocking { container.start() }
        container.close()

        assertEquals(listOf("create a", "create b", "start b", "stop b", "destroy b"), events.all())
    }

    @Test
    fun `an AutoCloseable that is no Lifecycle is closed at the destroy step`() {
        val container = build(
            service("a", events = events),
            binding(key<Client>(), "test", "client") { Client("client", events) },
            service("b", events = events),
        )
        runBlocking { container.start() }

        container.close()

        assertEquals(
            listOf("start a", "start b", "stop b", "stop a", "destroy b", "auto-close client", "destroy a"),
            events.lifecycle(),
        )
    }

    @Test
    fun `an instance that is both a Lifecycle and an AutoCloseable is destroyed, then closed`() {
        val container = build(binding(key<Pool>(), "test", "pool") { Pool(events) })
        runBlocking { container.start() }

        val error = assertFailsWith<IllegalStateException> { container.close() }

        assertEquals("destroy pool failed", error.message)
        assertEquals(listOf("stop pool", "destroy pool", "auto-close pool"), events.all())
    }
}
