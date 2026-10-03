package org.foedusprogramme.alexandrite.sdk.di

import org.foedusprogramme.alexandrite.sdk.di.ProblemKind.AMBIGUOUS
import org.foedusprogramme.alexandrite.sdk.di.ProblemKind.CYCLE
import org.foedusprogramme.alexandrite.sdk.di.ProblemKind.MISSING
import org.foedusprogramme.alexandrite.sdk.di.ProblemKind.MODULE_MISMATCH
import org.foedusprogramme.alexandrite.sdk.di.ProblemKind.SCOPE
import org.foedusprogramme.alexandrite.sdk.di.Scope.CHANNEL_INSTANCE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ValidationTest {
    private val serviceType = "org.foedusprogramme.alexandrite.sdk.di.Service"

    @Test
    fun `every problem is reported on its own line, saying what to fix`() {
        val error = assertFailsWith<DiException> {
            Container.build(
                listOf(
                    index(
                        "core",
                        service("a", dep("b"), module = "core"),
                        service("b", dep("a"), module = "core"),
                        service("registry", dep("state"), module = "core"),
                        service("state", scope = CHANNEL_INSTANCE, module = "core"),
                        service("tool", dep("clock"), module = "core"),
                    ),
                    index("extra", service("registry", module = "extra")),
                ),
            )
        }

        assertEquals(
            """
            Cannot build container 'root' (4 problems):
            - Ambiguous binding: @Named("registry") $serviceType is bound by registry (module core) and registry (module extra). Remove all but one of them, or override the key.
            - Scope violation: singleton registry (module core) depends on channel-instance-scoped state (module core) through parameter 'state'. Make registry (module core) channel-instance-scoped or drop the dependency.
            - Missing binding: nothing binds @Named("clock") $serviceType, which tool (module core) needs for parameter 'clock'. Loaded modules: core, extra. Bind it in one of them or pass an override to Container.build().
            - Dependency cycle: a (module core) -> b (module core) -> a (module core), through parameters 'b', 'a'. Inject one of them as Lazy or a provider.
            """.trimIndent(),
            error.message,
        )
        assertEquals(
            listOf(
                Problem(AMBIGUOUS, error.message!!.lines()[1].removePrefix("- "), null, svc("registry")),
                Problem(SCOPE, error.message!!.lines()[2].removePrefix("- "), "core", svc("state")),
                Problem(MISSING, error.message!!.lines()[3].removePrefix("- "), "core", svc("clock")),
                Problem(CYCLE, error.message!!.lines()[4].removePrefix("- "), "core", svc("a")),
            ),
            error.problems,
        )
    }

    @Test
    fun `an index that returns a binding of another module is rejected`() {
        val core = index("core", service("a", module = "core"), service("b", module = "extra"))

        val error = assertFailsWith<DiException> { Container.build(listOf(core)) }

        assertEquals(
            "Cannot build container 'root' (1 problem):\n" +
                "- Module mismatch: the index of module 'core' (${core::class.java.name}) returns " +
                "b (module extra), which belongs to module 'extra'. Return only bindings of module 'core' from it.",
            error.message,
        )
        assertEquals(
            listOf(Problem(MODULE_MISMATCH, error.problems.single().message, "core", svc("b"))),
            error.problems,
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

        assertTrue("a (module test) -> b (module test) -> c (module test) -> a (module test)" in error.message!!)
    }

    @Test
    fun `a binding that depends on itself is a cycle`() {
        val error = assertFailsWith<DiException> { build(service("a", dep("a"))) }

        assertTrue("a (module test) -> a (module test), through parameter 'a'" in error.message!!)
    }

    @Test
    fun `a cycle through OPTIONAL is rejected`() {
        val error = assertFailsWith<DiException> {
            build(service("a", dep("b", DependencyKind.OPTIONAL)), service("b", dep("a")))
        }

        assertTrue("Dependency cycle: a (module test) -> b (module test) -> a (module test)" in error.message!!)
    }

    @Test
    fun `a cycle through ALL is rejected`() {
        val error = assertFailsWith<DiException> {
            build(
                service("a", dep("tools", DependencyKind.ALL)),
                service("t", dep("a"), key = svc("tools"), multi = true),
            )
        }

        assertTrue("Dependency cycle: a (module test) -> t (module test) -> a (module test)" in error.message!!)
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

        assertTrue(
            "Scope violation: singleton a (module test) depends on channel-instance-scoped c (module test)" in
                error.message!!,
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

        assertTrue("depends on channel-instance-scoped t2 (module test) through parameter 'tools'" in error.message!!)
    }

    @Test
    fun `a singleton cannot depend on a channel binding through LAZY`() {
        val error = assertFailsWith<DiException> {
            build(service("a", dep("c", DependencyKind.LAZY)), service("c", scope = CHANNEL_INSTANCE))
        }

        assertTrue("Scope violation" in error.message!!)
    }
}
