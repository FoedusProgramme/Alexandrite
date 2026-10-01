package org.foedusprogramme.alexandrite.sdk.di

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BindingTest {
    private val tools = svc("tools")

    // Single bindings.

    @Test
    fun `get returns the instance of the single binding`() {
        val container = build(service("a"))

        assertEquals("a", container.get(svc("a")).name)
    }

    @Test
    fun `two single bindings for one key are ambiguous`() {
        val error = assertFailsWith<DiException> {
            Container.build(
                listOf(index("core", service("a", module = "core")), index("extra", service("a", module = "extra"))),
            )
        }

        assertTrue("a (module core) and a (module extra)" in error.message!!)
    }

    @Test
    fun `an override replaces the binding of its key`() {
        val events = Events()
        val container = Container.build(
            listOf(index("test", service("a", events = events))),
            overrides = listOf(service("fake", key = svc("a"), events = events)),
        )

        assertEquals("fake", container.get(svc("a")).name)
        assertEquals(listOf("create fake"), events.all())
    }

    @Test
    fun `an override replaces ambiguous bindings`() {
        val container = Container.build(
            listOf(index("core", service("a", module = "core")), index("extra", service("a", module = "extra"))),
            overrides = listOf(instanceBinding(svc("a"), Service("fake", Events(), emptyMap(), false, false), "test")),
        )

        assertEquals("fake", container.get(svc("a")).name)
    }

    @Test
    fun `an override for a new key adds it`() {
        val container = Container.build(listOf(index("test", service("a"))), overrides = listOf(service("b")))

        assertEquals("b", container.get(svc("b")).name)
    }

    @Test
    fun `an override replaces every contribution of its key`() {
        val container = Container.build(
            listOf(index("test", service("t1", key = tools, multi = true), service("t2", key = tools, multi = true))),
            overrides = listOf(service("fake", key = tools, multi = true)),
        )

        assertEquals(listOf("fake"), container.getAll(tools).map { it.name })
    }

    // Multibindings.

    @Test
    fun `getAll orders contributions by module name, then declaration`() {
        val container = Container.build(
            listOf(
                index("zeta", service("z1", key = tools, multi = true), service("z2", key = tools, multi = true)),
                index("alpha", service("a2", key = tools, multi = true), service("a1", key = tools, multi = true)),
            ),
        )

        assertEquals(listOf("a2", "a1", "z1", "z2"), container.getAll(tools).map { it.name })
    }

    @Test
    fun `getAll of a key without contributions is empty`() {
        assertEquals(emptyList(), build().getAll(tools))
    }

    @Test
    fun `a key with both a single binding and contributions is rejected`() {
        val error = assertFailsWith<DiException> {
            build(service("single", key = tools), service("contribution", key = tools, multi = true))
        }

        assertTrue("Conflicting bindings" in error.message!!)
    }

    @Test
    fun `injecting every contribution of a single binding is rejected`() {
        val error =
            assertFailsWith<DiException> { build(service("tools"), service("a", dep("tools", DependencyKind.ALL))) }

        assertTrue("Wrong dependency kind" in error.message!!)
    }

    @Test
    fun `injecting a multibinding key as one instance is rejected`() {
        val error = assertFailsWith<DiException> {
            build(service("t1", key = tools, multi = true), service("a", dep("tools")))
        }

        assertTrue("only has multibinding contributions" in error.message!!)
    }

    @Test
    fun `get and getAll refuse a key of the other kind`() {
        val container = build(service("a"), service("t1", key = tools, multi = true))

        assertFailsWith<DiException> { container.get(tools) }
        assertFailsWith<DiException> { container.getAll(svc("a")) }
    }

    @Test
    fun `two indexes of one module are rejected`() {
        val error = assertFailsWith<DiException> {
            Container.build(listOf(index("test", service("a")), index("test", service("b"))))
        }

        assertTrue("Duplicate module 'test'" in error.message!!)
    }

    // Dependency kinds.

    @Test
    fun `INSTANCE injects the bound instance`() {
        val container = build(service("a", dep("b")), service("b"))

        assertSame(container.get(svc("b")), container.get(svc("a")).dependency("b"))
    }

    @Test
    fun `OPTIONAL injects null when nothing is bound`() {
        val container = build(service("a", dep("b", DependencyKind.OPTIONAL)))

        assertNull(container.get(svc("a")).injected["b"])
        assertNull(container.getOrNull(svc("b")))
    }

    @Test
    fun `OPTIONAL injects the instance when bound`() {
        val container = build(service("a", dep("b", DependencyKind.OPTIONAL)), service("b"))

        assertSame(container.get(svc("b")), container.get(svc("a")).injected["b"])
    }

    @Test
    fun `ALL injects an empty list when nothing contributes`() {
        val container = build(service("a", dep("tools", DependencyKind.ALL)))

        assertEquals(emptyList<Any>(), container.get(svc("a")).injected["tools"])
    }

    @Test
    fun `LAZY needs a binding`() {
        val error = assertFailsWith<DiException> { build(service("a", dep("b", DependencyKind.LAZY))) }

        assertTrue("Missing binding" in error.message!!)
    }

    @Test
    fun `PROVIDER needs a binding`() {
        val error = assertFailsWith<DiException> { build(service("a", dep("b", DependencyKind.PROVIDER))) }

        assertTrue("Missing binding" in error.message!!)
    }

    @Test
    fun `resolving an undeclared dependency fails`() {
        val sneaky = binding(svc("a"), "a (module test)") { it.get(svc("b")) }

        val error = assertFailsWith<DiException> { build(sneaky, service("b")) }

        assertTrue("a (module test) resolved" in error.message!!)
        assertTrue("without declaring it" in error.message!!)
    }
}
