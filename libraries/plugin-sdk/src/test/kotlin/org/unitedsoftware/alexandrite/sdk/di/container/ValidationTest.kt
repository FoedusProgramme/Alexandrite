package org.unitedsoftware.alexandrite.sdk.di.container

import org.unitedsoftware.alexandrite.sdk.di.container.DiProblemKind.AMBIGUOUS
import org.unitedsoftware.alexandrite.sdk.di.container.DiProblemKind.CYCLE
import org.unitedsoftware.alexandrite.sdk.di.container.DiProblemKind.MISSING
import org.unitedsoftware.alexandrite.sdk.di.container.DiProblemKind.PLUGIN_MISMATCH
import org.unitedsoftware.alexandrite.sdk.di.container.DiProblemKind.SCOPE
import org.unitedsoftware.alexandrite.sdk.di.container.Scope.CHANNEL_INSTANCE
import org.unitedsoftware.alexandrite.sdk.di.key
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame

class ValidationTest {
    private val serviceType = "org.unitedsoftware.alexandrite.sdk.di.container.Service"

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
            - Ambiguous binding: @Named("registry") $serviceType is bound by registry (plugin core) and registry (plugin extra). Remove all but one of them, or switch off all but one of their plugins.
            - Scope violation: singleton registry (plugin core) depends on channel-instance-scoped state (plugin core) through parameter 'state'. Make registry channel-instance-scoped or drop the dependency.
            - Missing binding: nothing binds @Named("clock") $serviceType, which tool (plugin core) needs for parameter 'clock'. Loaded plugins: core, extra.
            - Dependency cycle: a (plugin core) -> b (plugin core) -> a (plugin core), through parameters 'b', 'a'. Inject one of them as Lazy or a provider.
            """.trimIndent(),
            error.message,
        )
        assertEquals(
            listOf(
                AMBIGUOUS to null,
                SCOPE to "core",
                MISSING to "core",
                CYCLE to "core",
            ),
            error.problems.map { it.kind to it.plugin },
        )
    }

    @Test
    fun `bindings listed under another plugin are rejected`() {
        val error = assertFailsWith<DiException> {
            Container.build(listOf(plugin("core", service("a", plugin = "core"), service("b", plugin = "extra"))))
        }

        assertEquals(listOf(PLUGIN_MISMATCH to "core"), error.problems.map { it.kind to it.plugin })
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
    fun `a cycle through INSTANCE, OPTIONAL or ALL is rejected, naming its path`() {
        val cycles = mapOf(
            listOf(service("a", dep("b")), service("b", dep("c")), service("c", dep("a"))) to
                "a (plugin test) -> b (plugin test) -> c (plugin test) -> a (plugin test)",
            listOf(service("a", dep("a"))) to "a (plugin test) -> a (plugin test), through parameter 'a'",
            listOf(service("a", dep("b", DependencyKind.OPTIONAL)), service("b", dep("a"))) to
                "a (plugin test) -> b (plugin test) -> a (plugin test)",
            listOf(
                service("a", dep("tools", DependencyKind.ALL)),
                service("t", dep("a"), key = svc("tools"), multi = true),
            ) to "a (plugin test) -> t (plugin test) -> a (plugin test)",
        )

        for ((bindings, path) in cycles) {
            val error = assertFailsWith<DiException> { build(*bindings.toTypedArray()) }

            assertEquals(listOf(CYCLE), error.problems.map { it.kind }, path)
            assertContains(error.message!!, path)
        }
    }

    @Test
    fun `LAZY and PROVIDER break a cycle`() {
        val lazy = build(service("a", dep("b", DependencyKind.LAZY)), service("b", dep("a")))
        val provided = build(service("a", dep("b", DependencyKind.PROVIDER)), service("b", dep("a")))

        val lazyB = lazy.get(svc("a")).injected["b"] as Lazy<*>
        assertFalse(lazyB.isInitialized())
        assertSame(lazy.get(svc("a")), (lazyB.value as Service).dependency("a"))
        val provideB = provided.get(svc("a")).injected["b"] as Function0<*>
        assertSame(provided.get(svc("b")), provideB())
    }

    // Scopes.

    @Test
    fun `a singleton cannot depend on channel contributions through ALL`() {
        val error = assertFailsWith<DiException> {
            build(
                service("a", dep("tools", DependencyKind.ALL)),
                service("t1", key = svc("tools"), multi = true),
                service("t2", key = svc("tools"), multi = true, scope = CHANNEL_INSTANCE),
            )
        }

        assertEquals(listOf(SCOPE), error.problems.map { it.kind })
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
            listOf(SCOPE to "relay"),
            error.problems.map {
                it.kind to it.plugin
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
