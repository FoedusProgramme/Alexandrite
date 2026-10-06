package org.foedusprogramme.alexandrite.sdk.di.container

import kotlinx.coroutines.runBlocking
import org.foedusprogramme.alexandrite.sdk.di.Key
import org.foedusprogramme.alexandrite.sdk.di.container.DiProblemKind.AMBIGUOUS
import org.foedusprogramme.alexandrite.sdk.di.container.DiProblemKind.CLOSED
import org.foedusprogramme.alexandrite.sdk.di.container.DiProblemKind.CONFLICTING
import org.foedusprogramme.alexandrite.sdk.di.container.DiProblemKind.CREATION_FAILED
import org.foedusprogramme.alexandrite.sdk.di.container.DiProblemKind.DUPLICATE_PLUGIN
import org.foedusprogramme.alexandrite.sdk.di.container.DiProblemKind.MISSING
import org.foedusprogramme.alexandrite.sdk.di.container.DiProblemKind.NESTED_CHILD
import org.foedusprogramme.alexandrite.sdk.di.container.DiProblemKind.REENTRANT
import org.foedusprogramme.alexandrite.sdk.di.container.DiProblemKind.SCOPE
import org.foedusprogramme.alexandrite.sdk.di.container.DiProblemKind.STARTED_TWICE
import org.foedusprogramme.alexandrite.sdk.di.container.DiProblemKind.UNDECLARED
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
        assertEquals(Triple(kind, plugin, key), Triple(problem.kind, problem.plugin, problem.key), problem.message)
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
    fun `lifecycle problems name no plugin`() {
        val container = build(service("a"))
        runBlocking { container.start() }

        assertProblem(STARTED_TWICE, null, null) { runBlocking { container.start() } }
        assertProblem(NESTED_CHILD, null, null) { container.child("tg", setOf("test")).child("dc", setOf("test")) }
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

        assertProblem(UNDECLARED, "test", svc("b")) { build(sneaky, service("b")) }
        assertProblem(REENTRANT, "test", svc("a")) { build(selfish) }
    }

    @Test
    fun `each problem kind has a unique id`() {
        assertEquals("di.wrong_kind", WRONG_KIND.id)
        assertEquals(DiProblemKind.entries.size, DiProblemKind.entries.map { it.id }.toSet().size)
    }
}
