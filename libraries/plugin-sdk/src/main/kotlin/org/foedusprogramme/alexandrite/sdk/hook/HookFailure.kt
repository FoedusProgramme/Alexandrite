package org.foedusprogramme.alexandrite.sdk.hook

import kotlin.time.Duration

/** How a hook failed. */
public sealed interface HookFailure {
    public data class Threw(val error: Throwable) : HookFailure

    public data class TimedOut(val timeout: Duration) : HookFailure

    /** The hook returned a decision its point does not allow. */
    public data class Disallowed(val decision: HookDecision<*>) : HookFailure

    /** The hook's ASYNC queue was full, so an event was dropped. */
    public data object Dropped : HookFailure
}

/** Told about each hook failure. Exceptions it throws are ignored. */
public fun interface HookFailureListener {
    public fun onFailure(hook: Hook, point: HookPoint<*>, failure: HookFailure)

    public companion object {
        public val NONE: HookFailureListener = HookFailureListener { _, _, _ -> }
    }
}
