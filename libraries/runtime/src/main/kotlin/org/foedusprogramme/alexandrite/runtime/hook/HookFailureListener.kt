package org.foedusprogramme.alexandrite.runtime.hook

import org.foedusprogramme.alexandrite.sdk.hook.Hook
import org.foedusprogramme.alexandrite.sdk.hook.HookFailure
import org.foedusprogramme.alexandrite.sdk.hook.HookPoint

/** Told about each hook failure. */
internal fun interface HookFailureListener {
    fun onFailure(hook: Hook, point: HookPoint<*>, failure: HookFailure)
}
