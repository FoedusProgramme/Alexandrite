package org.unitedsoftware.alexandrite.runtime.hook

import org.unitedsoftware.alexandrite.sdk.hook.Hook
import org.unitedsoftware.alexandrite.sdk.hook.HookDecision
import org.unitedsoftware.alexandrite.sdk.hook.HookFailure
import org.unitedsoftware.alexandrite.sdk.hook.HookPoint

/** Told about each hook failure. */
internal fun interface HookFailureListener {
    fun onFailure(hook: Hook, point: HookPoint<*>, failure: HookFailure)
}

/** Told about each decision of an interceptor that replaces the payload or stops the chain. */
internal fun interface HookDecisionListener {
    fun onDecision(hook: Hook, point: HookPoint<*>, decision: HookDecision<*>)
}
