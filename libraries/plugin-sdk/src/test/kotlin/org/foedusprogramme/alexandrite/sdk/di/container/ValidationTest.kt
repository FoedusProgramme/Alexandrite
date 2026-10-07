package org.foedusprogramme.alexandrite.sdk.di.container

import org.foedusprogramme.alexandrite.sdk.di.container.DiProblemKind.AMBIGUOUS
import org.foedusprogramme.alexandrite.sdk.di.container.DiProblemKind.CYCLE
import org.foedusprogramme.alexandrite.sdk.di.container.DiProblemKind.MISSING
import org.foedusprogramme.alexandrite.sdk.di.container.DiProblemKind.PLUGIN_MISMATCH
import org.foedusprogramme.alexandrite.sdk.di.container.DiProblemKind.SCOPE
import org.foedusprogramme.alexandrite.sdk.di.container.Scope.CHANNEL_INSTANCE
import org.foedusprogramme.alexandrite.sdk.di.key
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class ValidationTest {
    private val serviceType = "org.foedusprogramme.alexandrite.sdk.di.container.Service"

    @Test
    fun `every problem is reported on its own line, saying what is wrong`() {
        val error = assertFailsWith<DiException> {
            Container.build(
                listOf(
                    plugin(
                        "core",
                        service("a", dep("b"), plugin = "core"),
                        service("b", dep("a"), plugin = "core"),
                        service("registry", dep("state"), plugin = "core"),
                        service("state", scope = CHANNEL_INSTANCE, plugin = "core"),
                        service("tool", dep("clock"), plugin = "core"),
                    ),
                    plugin("extra", service("registry", plugin = "extra")),
                ),
            )
        }

        assertEquals(
            """
            Cannot build container 'root' (4 problems):
            - Ambiguous binding: @Named("registry") $serviceType is bound by registry (plugin core) and registry (plugin extra). Remove all but one of them.
            - Scope violation: singleton registry (plugin core) depends on channel-instance-scoped state (plugin core) through parameter 'state'. Make registry channel-instance-scoped or drop the dependency.
            - Missing binding: nothing binds @Named("clock") $serviceType, which tool (plugin core) needs for parameter 'clock'. Loaded plugins: core, extra.
            - Dependency cycle: a (plugin core) -> b (plugin core) -> a (plugin core), through parameters 'b', 'a'. Inject one of them as Lazy or a provider.
            """.trimIndent(),
            error.message,
        )
        assertEquals(
            listOf(
                Triple(AMBIGUOUS, null, svc("registry")),
                Triple(SCOPE, "core", svc("state")),
                Triple(MISSING, "core", svc("clock")),
                Triple(CYCLE, "core", svc("a")),
            ),
            error.problems.map { Triple(it.kind, it.plugin, it.key) },
        )
    }

    @Test
    fun `bindings listed under another plugin are rejected`() {
        val error = assertFailsWith<DiException> {
            Container.build(listOf(plugin("core", service("a", plugin = "core"), service("b", plugin = "extra"))))
        }

        assertEquals(
            "Cannot build container 'root' (1 problem):\n" +
                "- Plugin mismatch: the bindings of plugin 'core' include b (plugin extra), " +
                "which belongs to plugin 'extra'.",
            error.message,
        )
        assertEquals(
            listOf(Triple(PLUGIN_MISMATCH, "core", svc("b"))),
            error.problems.map { Triple(it.kind, it.plugin, it.key) },
        )
    }

    @Test
    fun `nothing is created when validation fails`() {
        val events = Events()

        assertFailsWith<DiException> {
            build(service("a", events = events), service("b", dep("missing"), events = events))
        }

        assertEquals(emptyList(), events.all())
    }

    // Cycles.

    @Test
    fun `a cycle through INSTANCE is rejected`() {
        val error =
            assertFailsWith<DiException> {
                build(service("a", dep("b")), service("b", dep("c")), service("c", dep("a")))
            }

        assertContains(error.message!!, "a (plugin test) -> b (plugin test) -> c (plugin test) -> a (plugin test)")
    }

    @Test
    fun `a binding that depends on itself is a cycle`() {
        val error = assertFailsWith<DiException> { build(service("a", dep("a"))) }

        assertContains(error.message!!, "a (plugin test) -> a (plugin test), through parameter 'a'")
    }

    @Test
    fun `a cycle through OPTIONAL is rejected`() {
        val error = assertFailsWith<DiException> {
            build(service("a", dep("b", DependencyKind.OPTIONAL)), service("b", dep("a")))
        }

        assertContains(error.message!!, "Dependency cycle: a (plugin test) -> b (plugin test) -> a (plugin test)")
    }

    @Test
    fun `a cycle through ALL is rejected`() {
        val error = assertFailsWith<DiException> {
            build(
                service("a", dep("tools", DependencyKind.ALL)),
                service("t", dep("a"), key = svc("tools"), multi = true),
            )
        }

        assertContains(error.message!!, "Dependency cycle: a (plugin test) -> t (plugin test) -> a (plugin test)")
    }

    @Test
    fun `LAZY breaks a cycle`() {
        val container = build(service("a", dep("b", DependencyKind.LAZY)), service("b", dep("a")))

        val lazyB = container.get(svc("a")).injected["b"] as Lazy<*>
        assertSame(container.get(svc("a")), (lazyB.value as Service).dependency("a"))
    }

    @Test
    fun `PROVIDER breaks a cycle`() {
        val container = build(service("a", dep("b", DependencyKind.PROVIDER)), service("b", dep("a")))

        val provideB = container.get(svc("a")).injected["b"] as Function0<*>
        assertSame(container.get(svc("b")), provideB())
    }

    // Scopes.

    @Test
    fun `a singleton cannot depend on a channel binding`() {
        val error =
            assertFailsWith<DiException> { build(service("a", dep("c")), service("c", scope = CHANNEL_INSTANCE)) }

        assertContains(
            error.message!!,
            "Scope violation: singleton a (plugin test) depends on channel-instance-scoped c (plugin test)",
        )
    }

    @Test
    fun `a singleton cannot depend on channel contributions through ALL`() {
        val error = assertFailsWith<DiException> {
            build(
                service("a", dep("tools", DependencyKind.ALL)),
                service("t1", key = svc("tools"), multi = true),
                service("t2", key = svc("tools"), multi = true, scope = CHANNEL_INSTANCE),
            )
        }

        assertContains(
            error.message!!,
            "Scope violation: channel-instance-scoped t2 (plugin test) contributes to " +
                "@Named(\"tools\") $serviceType, which singleton a (plugin test) collects through parameter 'tools'. " +
                "Make it a singleton or make a channel-instance-scoped.",
        )
    }

    @Test
    fun `channel contributions a singleton collects are the fault of their plugin`() {
        val error = assertFailsWith<DiException> {
            Container.build(
                listOf(
                    plugin("core", service("registry", dep("tools", DependencyKind.ALL), plugin = "core")),
                    plugin(
                        "relay",
                        service("t", key = svc("tools"), multi = true, scope = CHANNEL_INSTANCE, plugin = "relay"),
                    ),
                ),
            )
        }

        assertEquals(
            listOf(Triple(SCOPE, "relay", svc("tools"))),
            error.problems.map {
                Triple(it.kind, it.plugin, it.key)
            },
        )
    }

    @Test
    fun `a singleton cannot depend on a channel binding through LAZY`() {
        val error = assertFailsWith<DiException> {
            build(service("a", dep("c", DependencyKind.LAZY)), service("c", scope = CHANNEL_INSTANCE))
        }

        assertEquals(listOf(SCOPE), error.problems.map { it.kind })
    }
}
