package org.foedusprogramme.alexandrite.sdk.turn

import org.foedusprogramme.alexandrite.sdk.hook.FailurePolicy.FAIL_CLOSED
import org.foedusprogramme.alexandrite.sdk.hook.FailurePolicy.FAIL_OPEN
import org.foedusprogramme.alexandrite.sdk.hook.HookEffect.ABORT
import org.foedusprogramme.alexandrite.sdk.hook.HookEffect.REPLACE
import org.foedusprogramme.alexandrite.sdk.hook.HookPoint
import org.foedusprogramme.alexandrite.sdk.hook.InterceptorPoint
import org.foedusprogramme.alexandrite.sdk.hook.ObserverPoint
import kotlin.test.Test
import kotlin.test.assertEquals

class TurnPointsTest {
    private val declared: List<HookPoint<*>> = TurnPoints::class.java.declaredFields
        .filter { HookPoint::class.java.isAssignableFrom(it.type) }
        .map { field ->
            field.isAccessible = true
            field.get(TurnPoints) as HookPoint<*>
        }

    @Test
    fun `the points have unique ids, as the hook table names them`() {
        val ids = declared.map { it.id }

        assertEquals(
            setOf(
                "prompt.sections", "turn.start", "turn.input", "context.loaded", "context.inject", "llm.request",
                "llm.response", "tool.before", "tool.after", "response.preview", "response.before", "turn.committed",
                "conversation.sealed",
            ),
            ids.toSet(),
        )
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `interceptor points allow their decisions and follow their failure policy`() {
        val interceptors = declared.filterIsInstance<InterceptorPoint<*>>().associate {
            it.id to (it.effects to it.onFailure)
        }

        assertEquals(
            mapOf(
                "prompt.sections" to (setOf(REPLACE) to FAIL_OPEN),
                "turn.start" to (setOf(REPLACE, ABORT) to FAIL_OPEN),
                "turn.input" to (setOf(REPLACE, ABORT) to FAIL_CLOSED),
                "context.inject" to (setOf(REPLACE) to FAIL_OPEN),
                "llm.request" to (setOf(REPLACE, ABORT) to FAIL_OPEN),
                "llm.response" to (setOf(ABORT) to FAIL_OPEN),
                "tool.before" to (setOf(ABORT) to FAIL_CLOSED),
                "response.preview" to (setOf(REPLACE, ABORT) to FAIL_CLOSED),
                "response.before" to (setOf(REPLACE) to FAIL_OPEN),
            ),
            interceptors,
        )
    }

    @Test
    fun `the other points are observer points`() {
        assertEquals(
            setOf("context.loaded", "tool.after", "turn.committed", "conversation.sealed"),
            declared.filterIsInstance<ObserverPoint<*>>().map { it.id }.toSet(),
        )
    }
}
