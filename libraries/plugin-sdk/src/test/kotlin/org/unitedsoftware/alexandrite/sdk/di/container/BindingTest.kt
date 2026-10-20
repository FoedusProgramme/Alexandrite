package org.unitedsoftware.alexandrite.sdk.di.container

import org.unitedsoftware.alexandrite.sdk.di.container.DiProblemKind.MISSING
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class BindingTest {
    private val tools = svc("tools")

    private fun tool(name: String, plugin: String) = service(name, key = tools, multi = true, plugin = plugin)

    // Single bindings.

    @Test
    fun `an override replaces the binding of its key`() {
        val events = Events()
        val container = Container.build(
            listOf(plugin("test", service("a", events = events))),
            overrides = listOf(service("fake", key = svc("a"), events = events)),
        )

        assertEquals("fake", container.get(svc("a")).name)
        assertEquals(listOf("create fake"), events.all())
    }

    @Test
    fun `an override replaces ambiguous bindings`() {
        val container = Container.build(
            listOf(plugin("core", service("a", plugin = "core")), plugin("extra", service("a", plugin = "extra"))),
            overrides = listOf(
                instanceBinding(svc("a"), Service("fake", Events(), emptyMap()), "test", "test"),
            ),
        )

        assertEquals("fake", container.get(svc("a")).name)
    }

    @Test
    fun `an override for a new key adds it`() {
        val container = Container.build(listOf(plugin("test", service("a"))), overrides = listOf(service("b")))

        assertEquals("b", container.get(svc("b")).name)
    }

    @Test
    fun `an override replaces every contribution of its key`() {
        val container = Container.build(
            listOf(plugin("test", service("t1", key = tools, multi = true), service("t2", key = tools, multi = true))),
            overrides = listOf(service("fake", key = tools, multi = true)),
        )

        assertEquals(listOf("fake"), container.getAll(tools).map { it.name })
    }

    // Multibindings.

    @Test
    fun `getAll orders contributions by plugin, then declaration`() {
        val container = Container.build(
            listOf(
                plugin("zeta", tool("z1", "zeta"), tool("z2", "zeta")),
                plugin("alpha", tool("a2", "alpha"), tool("a1", "alpha")),
            ),
        )

        assertEquals(listOf("a2", "a1", "z1", "z2"), container.getAll(tools).map { it.name })
    }

    @Test
    fun `a key without contributions resolves and injects as an empty list`() {
        val container = build(service("a", dep("tools", DependencyKind.ALL)))

        assertEquals(emptyList(), container.getAll(tools))
        assertEquals(emptyList<Any>(), container.get(svc("a")).injected["tools"])
    }

    @Test
    fun `only a contribution has a name`() {
        val named = binding(tools, "test", "t1", multi = true, name = "one") { Service("t1", Events(), emptyMap()) }

        assertFailsWith<IllegalArgumentException> {
            binding(svc("a"), "test", "a", name = "a") { Service("a", Events(), emptyMap()) }
        }

        assertEquals("one", named.name)
        assertNull(tool("t2", "test").name)
    }

    // Dependency kinds.

    @Test
    fun `OPTIONAL injects null when nothing is bound and the instance when bound`() {
        val container = build(service("a", dep("b", DependencyKind.OPTIONAL)))
        val bound = build(service("a", dep("b", DependencyKind.OPTIONAL)), service("b"))

        assertNull(container.get(svc("a")).injected["b"])
        assertNull(container.getOrNull(svc("b")))
        assertSame(bound.get(svc("b")), bound.get(svc("a")).injected["b"])
    }

    @Test
    fun `LAZY and PROVIDER need a binding`() {
        for (kind in listOf(DependencyKind.LAZY, DependencyKind.PROVIDER)) {
            val error = assertFailsWith<DiException> { build(service("a", dep("b", kind))) }

            assertEquals(listOf(MISSING), error.problems.map { it.kind }, "$kind")
        }
    }
}
