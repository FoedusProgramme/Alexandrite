package org.foedusprogramme.alexandrite.runtime.hook

import org.foedusprogramme.alexandrite.sdk.hook.Hook
import org.foedusprogramme.alexandrite.sdk.hook.HookFailure
import org.foedusprogramme.alexandrite.sdk.hook.HookPoint

/** Told about each hook failure. Exceptions it throws are ignored. */
internal fun interface HookFailureListener {
    fun onFailure(hook: Hook, point: HookPoint<*>, failure: HookFailure)

    companion object {
        val NONE: HookFailureListener = HookFailureListener { _, _, _ -> }
    }
}
