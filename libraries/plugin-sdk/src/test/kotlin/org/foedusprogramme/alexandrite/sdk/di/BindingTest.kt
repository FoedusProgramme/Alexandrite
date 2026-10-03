package org.foedusprogramme.alexandrite.sdk.di

import org.foedusprogramme.alexandrite.sdk.di.DiProblemKind.AMBIGUOUS
import org.foedusprogramme.alexandrite.sdk.di.DiProblemKind.CONFLICTING
import org.foedusprogramme.alexandrite.sdk.di.DiProblemKind.DUPLICATE_PLUGIN
import org.foedusprogramme.alexandrite.sdk.di.DiProblemKind.MISSING
import org.foedusprogramme.alexandrite.sdk.di.DiProblemKind.UNDECLARED
import org.foedusprogramme.alexandrite.sdk.di.DiProblemKind.WRONG_KIND
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class BindingTest {
    private val tools = svc("tools")

    private fun tool(name: String, plugin: String) = service(name, key = tools, multi = true, plugin = plugin)

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
                listOf(plugin("core", service("a", plugin = "core")), plugin("extra", service("a", plugin = "extra"))),
            )
        }

        assertEquals(listOf(AMBIGUOUS), error.problems.map { it.kind })
        assertContains(error.message!!, "a (plugin core) and a (plugin extra)")
    }

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
                instanceBinding(svc("a"), Service("fake", Events(), emptyMap(), false, false), "test", "test"),
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
    fun `getAll of a key without contributions is empty`() {
        assertEquals(emptyList(), build().getAll(tools))
    }

    @Test
    fun `a key with both a single binding and contributions is rejected`() {
        val error = assertFailsWith<DiException> {
            build(service("single", key = tools), service("contribution", key = tools, multi = true))
        }

        assertEquals(listOf(CONFLICTING), error.problems.map { it.kind })
        assertContains(error.message!!, "Conflicting bindings")
    }

    @Test
    fun `injecting every contribution of a single binding is rejected`() {
        val error =
            assertFailsWith<DiException> { build(service("tools"), service("a", dep("tools", DependencyKind.ALL))) }

        assertEquals(listOf(WRONG_KIND), error.problems.map { it.kind })
        assertContains(error.message!!, "needs List<${svc("tools")}> for parameter 'tools', but")
        assertContains(error.message!!, "has a single binding, from tools (plugin test).")
    }

    @Test
    fun `injecting a multibinding key as one instance is rejected`() {
        val error = assertFailsWith<DiException> {
            build(service("t1", key = tools, multi = true), service("a", dep("tools")))
        }

        assertEquals(listOf(WRONG_KIND), error.problems.map { it.kind })
        assertContains(error.message!!, "only has multibinding contributions, from t1 (plugin test).")
    }

    @Test
    fun `get and getAll refuse a key of the other kind`() {
        val container = build(service("a"), service("t1", key = tools, multi = true))

        val single = assertFailsWith<DiException> { container.get(tools) }
        val all = assertFailsWith<DiException> { container.getAll(svc("a")) }

        assertEquals(
            "Wrong dependency kind: resolving one instance of $tools, but $tools only has multibinding " +
                "contributions, from t1 (plugin test).",
            single.message,
        )
        assertEquals(
            "Wrong dependency kind: resolving every contribution to ${svc("a")}, but ${svc("a")} has a single " +
                "binding, from a (plugin test).",
            all.message,
        )
    }

    @Test
    fun `two binding lists of one plugin are rejected`() {
        val error = assertFailsWith<DiException> {
            Container.build(listOf(plugin("test", service("a")), plugin("test", service("b"))))
        }

        assertEquals(listOf(DUPLICATE_PLUGIN), error.problems.map { it.kind })
        assertContains(error.message!!, "Duplicate plugin 'test'")
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

        assertEquals(listOf(MISSING), error.problems.map { it.kind })
    }

    @Test
    fun `PROVIDER needs a binding`() {
        val error = assertFailsWith<DiException> { build(service("a", dep("b", DependencyKind.PROVIDER))) }

        assertEquals(listOf(MISSING), error.problems.map { it.kind })
    }

    @Test
    fun `resolving an undeclared dependency fails`() {
        val sneaky = binding(svc("a"), "test", "a") { it.get(svc("b")) }

        val error = assertFailsWith<DiException> { build(sneaky, service("b")) }

        assertEquals(listOf(UNDECLARED), error.problems.map { it.kind })
        assertContains(error.message!!, "a (plugin test) resolved ${svc("b")} as INSTANCE without declaring it")
    }
}
