package org.foedusprogramme.alexandrite.sdk.di.container

import kotlinx.coroutines.runBlocking
import org.foedusprogramme.alexandrite.sdk.di.Key
import org.foedusprogramme.alexandrite.sdk.di.container.DiProblemKind.AMBIGUOUS
import org.foedusprogramme.alexandrite.sdk.di.container.DiProblemKind.CLOSED
import org.foedusprogramme.alexandrite.sdk.di.container.DiProblemKind.CONFLICTING
import org.foedusprogramme.alexandrite.sdk.di.container.DiProblemKind.CREATION_FAILED
import org.foedusprogramme.alexandrite.sdk.di.container.DiProblemKind.DUPLICATE_PLUGIN
import org.foedusprogramme.alexandrite.sdk.di.container.DiProblemKind.MISSING
import org.foedusprogramme.alexandrite.sdk.di.container.DiProblemKind.REENTRANT
import org.foedusprogramme.alexandrite.sdk.di.container.DiProblemKind.SCOPE
import org.foedusprogramme.alexandrite.sdk.di.container.DiProblemKind.WRONG_KIND
import org.foedusprogramme.alexandrite.sdk.di.key
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ProblemTest {
    private fun assertProblem(kind: DiProblemKind, plugin: String?, key: Key<*>?, block: () -> Unit) {
        val error = assertFailsWith<DiException> { block() }
        val problem = error.problems.single()
        assertContains(error.message!!, problem.message)
        assertEquals(kind to plugin, problem.kind to problem.plugin, problem.message)
        key?.let { assertContains(problem.message, "$it") }
    }

    @Test
    fun `graph problems name the plugin at fault and the key`() {
        assertProblem(DUPLICATE_PLUGIN, "test", null) { Container.build(listOf(plugin("test"), plugin("test"))) }
        assertProblem(AMBIGUOUS, "test", svc("a")) { build(service("a"), service("a")) }
        assertProblem(CONFLICTING, "test", svc("t")) { build(service("t"), service("t", multi = true)) }
        assertProblem(WRONG_KIND, "test", svc("t")) { build(service("a", dep("t", DependencyKind.ALL)), service("t")) }
        assertProblem(WRONG_KIND, "test", svc("t")) { build(service("a", dep("t")), service("t", multi = true)) }
        assertProblem(CREATION_FAILED, "test", svc("a")) { build(service("a", failCreate = true)) }
    }

    @Test
    fun `resolution problems name the key`() {
        val container = build(
            service("a"),
            service("t", multi = true),
            service("c", scope = Scope.CHANNEL_INSTANCE),
        )

        assertProblem(MISSING, null, svc("b")) { container.get(svc("b")) }
        assertProblem(WRONG_KIND, null, svc("t")) { container.get(svc("t")) }
        assertProblem(WRONG_KIND, null, svc("a")) { container.getAll(svc("a")) }
        assertProblem(SCOPE, null, svc("c")) { container.get(svc("c")) }
    }

    @Test
    fun `misusing a container is a programming error`() {
        val container = build(service("a"))
        runBlocking { container.start() }

        val twice = assertFailsWith<IllegalStateException> { runBlocking { container.start() } }
        val nested = assertFailsWith<IllegalStateException> {
            container.child("tg", setOf("test")).child("dc", setOf("test"))
        }

        assertEquals("Cannot start container 'root' twice.", twice.message)
        assertEquals(
            "Cannot create channel instance container 'dc' inside channel instance container 'tg': " +
                "only a root container has channel instance containers.",
            nested.message,
        )
        container.close()
    }

    @Test
    fun `lifecycle problems name no plugin`() {
        val container = build(service("a"))
        container.close()
        assertProblem(CLOSED, null, svc("a")) { container.get(svc("a")) }
        assertProblem(CLOSED, null, null) { container.child("tg", setOf("test")) }
        assertProblem(CLOSED, null, null) { runBlocking { container.start() } }
    }

    @Test
    fun `a binding that resolves undeclared keys or itself while created is at fault`() {
        val sneaky = binding(svc("a"), "test", "a") { it.get(svc("b")) }
        val selfish = binding(svc("a"), "test", "a", dependencies = listOf(dep("a", DependencyKind.PROVIDER))) {
            it.provider(svc("a")).invoke()
        }

        assertProblem(CREATION_FAILED, "test", svc("b")) { build(sneaky, service("b")) }
        assertProblem(REENTRANT, "test", null) { build(selfish) }
    }

    @Test
    fun `each problem kind has a unique id`() {
        assertEquals("di.wrong_kind", WRONG_KIND.id)
        assertEquals(DiProblemKind.entries.size, DiProblemKind.entries.map { it.id }.toSet().size)
    }
}
