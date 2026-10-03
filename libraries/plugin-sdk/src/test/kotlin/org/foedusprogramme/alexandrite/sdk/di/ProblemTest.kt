package org.foedusprogramme.alexandrite.sdk.di

import kotlinx.coroutines.runBlocking
import org.foedusprogramme.alexandrite.sdk.di.ProblemKind.AMBIGUOUS
import org.foedusprogramme.alexandrite.sdk.di.ProblemKind.CLOSED
import org.foedusprogramme.alexandrite.sdk.di.ProblemKind.CONFLICTING
import org.foedusprogramme.alexandrite.sdk.di.ProblemKind.CREATION_FAILED
import org.foedusprogramme.alexandrite.sdk.di.ProblemKind.DUPLICATE_MODULE
import org.foedusprogramme.alexandrite.sdk.di.ProblemKind.MISSING
import org.foedusprogramme.alexandrite.sdk.di.ProblemKind.NESTED_CHILD
import org.foedusprogramme.alexandrite.sdk.di.ProblemKind.REENTRANT
import org.foedusprogramme.alexandrite.sdk.di.ProblemKind.SCOPE
import org.foedusprogramme.alexandrite.sdk.di.ProblemKind.STARTED_TWICE
import org.foedusprogramme.alexandrite.sdk.di.ProblemKind.UNDECLARED
import org.foedusprogramme.alexandrite.sdk.di.ProblemKind.WRONG_KIND
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ProblemTest {
    private fun assertProblem(kind: ProblemKind, module: String?, key: Key<*>?, block: () -> Unit) {
        val error = assertFailsWith<DiException> { block() }
        val problem = error.problems.single()
        assertContains(error.message!!, problem.message)
        assertEquals(Triple(kind, module, key), Triple(problem.kind, problem.module, problem.key), problem.message)
    }

    @Test
    fun `graph problems name the module at fault and the key`() {
        assertProblem(DUPLICATE_MODULE, "test", null) { Container.build(listOf(index("test"), index("test"))) }
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
    fun `lifecycle problems name no module`() {
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
        val sneaky = binding(svc("a"), "test", "a (module test)") { it.get(svc("b")) }
        val selfish =
            binding(svc("a"), "test", "a (module test)", dependencies = listOf(dep("a", DependencyKind.PROVIDER))) {
                it.provider(svc("a")).invoke()
            }

        assertProblem(UNDECLARED, "test", svc("b")) { build(sneaky, service("b")) }
        assertProblem(REENTRANT, "test", svc("a")) { build(selfish) }
    }
}
