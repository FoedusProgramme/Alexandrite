package org.unitedsoftware.alexandrite.sdk.turn

import org.unitedsoftware.alexandrite.sdk.hook.FailurePolicy.FAIL_CLOSED
import org.unitedsoftware.alexandrite.sdk.hook.FailurePolicy.FAIL_OPEN
import org.unitedsoftware.alexandrite.sdk.hook.HookEffect.ABORT
import org.unitedsoftware.alexandrite.sdk.hook.HookEffect.REPLACE
import org.unitedsoftware.alexandrite.sdk.hook.HookPoint
import org.unitedsoftware.alexandrite.sdk.hook.InterceptorPoint
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
    fun `the points have unique ids`() {
        val ids = declared.map { it.id }

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
                "tool.before" to (setOf(REPLACE, ABORT) to FAIL_CLOSED),
                "response.preview" to (setOf(REPLACE, ABORT) to FAIL_CLOSED),
                "response.before" to (setOf(REPLACE) to FAIL_OPEN),
                "context.compact" to (setOf(REPLACE, ABORT) to FAIL_OPEN),
            ),
            interceptors,
        )
    }
}
