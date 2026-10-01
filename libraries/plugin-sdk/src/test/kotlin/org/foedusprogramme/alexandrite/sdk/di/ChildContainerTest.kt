package org.foedusprogramme.alexandrite.sdk.di

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ChildContainerTest {
    private val events = Events()
    private val channelName = key<String>("channel.name")
    private val nameOfChannel = Dependency(channelName, DependencyKind.INSTANCE, "name")

    private fun nameBinding(name: String) = instanceBinding(channelName, name, "channel config")

    @Test
    fun `a child sees the parent's singletons and its own bindings`() {
        val root = build(service("a"), service("c", dep("a"), nameOfChannel, scope = Scope.CHANNEL))

        val child = root.child("tg", listOf(nameBinding("tg")))

        val c = child.get(svc("c"))
        assertSame(root.get(svc("a")), c.dependency("a"))
        assertEquals("tg", c.injected["name"])
        assertSame(root.get(svc("a")), child.get(svc("a")))
    }

    @Test
    fun `a child creates channel bindings in dependency order`() {
        val root = build(
            service("a", events = events),
            service("d", dep("c"), scope = Scope.CHANNEL, events = events),
            service("c", dep("a"), dep("e"), scope = Scope.CHANNEL, events = events),
            service("e", scope = Scope.CHANNEL, events = events),
        )

        root.child("tg")

        assertEquals(listOf("create a", "create e", "create c", "create d"), events.all())
    }

    @Test
    fun `two children get distinct channel instances`() {
        val root = build(service("a"), service("c", dep("a"), scope = Scope.CHANNEL))

        val first = root.child("first").get(svc("c"))
        val second = root.child("second").get(svc("c"))

        assertNotSame(first, second)
        assertSame(first.dependency("a"), second.dependency("a"))
    }

    @Test
    fun `a child validates its graph when created`() {
        val root = build(service("c", nameOfChannel, scope = Scope.CHANNEL))

        val error = assertFailsWith<DiException> { root.child("tg") }

        assertTrue(error.message!!.startsWith("Cannot build channel container 'tg' (1 problem):"))
        assertTrue("which c (module test) needs for parameter 'name'" in error.message!!)
        assertTrue("pass it to child()" in error.message!!)
    }

    @Test
    fun `an extra binding that collides with a parent binding is ambiguous`() {
        val root = build(service("a"))

        val error = assertFailsWith<DiException> { root.child("tg", listOf(service("a", module = "extra"))) }

        assertTrue("a (module test) and a (module extra)" in error.message!!)
    }

    @Test
    fun `closing a child closes only its own instances`() {
        val root = build(service("a", events = events), service("c", scope = Scope.CHANNEL, events = events))
        val child = root.child("tg")

        child.close()

        assertEquals(listOf("close c"), events.starting("close"))
        assertEquals("a", root.get(svc("a")).name)
    }

    @Test
    fun `closing the parent closes live children first`() {
        val root = build(service("a", events = events), service("c", scope = Scope.CHANNEL, events = events))
        root.child("first").close()
        val live = root.child("second")

        root.close()

        assertEquals(listOf("close c", "close c", "close a"), events.starting("close"))
        assertFailsWith<DiException> { live.get(svc("c")) }
    }

    @Test
    fun `a child starts only its own instances`() {
        val root = build(service("a", events = events), service("c", scope = Scope.CHANNEL, events = events))

        runBlocking { root.child("tg").start() }

        assertEquals(listOf("start c"), events.starting("start"))
    }

    @Test
    fun `the root container refuses channel-scoped keys`() {
        val root = build(service("c", scope = Scope.CHANNEL))

        val error = assertFailsWith<DiException> { root.get(svc("c")) }

        assertTrue("is channel-scoped, bound by c (module test)" in error.message!!)
    }

    @Test
    fun `a child's getAll mixes singleton and channel contributions in order`() {
        val tools = svc("tools")
        val root = Container.build(
            listOf(
                index("beta", service("b1", key = tools, multi = true, scope = Scope.CHANNEL)),
                index(
                    "alpha",
                    service("a1", key = tools, multi = true),
                    service("a2", key = tools, multi = true, scope = Scope.CHANNEL),
                ),
            ),
        )

        val child = root.child("tg", listOf(service("extra", key = tools, multi = true)))

        assertEquals(listOf("a1", "a2", "b1", "extra"), child.getAll(tools).map { it.name })
        assertSame(child.getAll(tools).first(), root.child("other").getAll(tools).first())
        assertFailsWith<DiException> { root.getAll(tools) }
    }

    @Test
    fun `a channel container cannot create children`() {
        val child = build().child("tg")

        assertFailsWith<DiException> { child.child("nested") }
    }
}
