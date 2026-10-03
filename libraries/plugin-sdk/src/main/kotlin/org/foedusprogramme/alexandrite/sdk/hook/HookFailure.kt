package org.foedusprogramme.alexandrite.sdk.hook

import kotlin.time.Duration

/** How a hook failed. */
public sealed interface HookFailure {
    public class Threw(public val error: Throwable) : HookFailure {
        override fun equals(other: Any?): Boolean = other is Threw && error == other.error

        override fun hashCode(): Int = error.hashCode()

        override fun toString(): String = "Threw(error=$error)"
    }

    public class TimedOut(public val timeout: Duration) : HookFailure {
        override fun equals(other: Any?): Boolean = other is TimedOut && timeout == other.timeout

        override fun hashCode(): Int = timeout.hashCode()

        override fun toString(): String = "TimedOut(timeout=$timeout)"
    }

    /** The hook returned a decision its point does not allow. */
    public class Disallowed(public val decision: HookDecision<*>) : HookFailure {
        override fun equals(other: Any?): Boolean = other is Disallowed && decision == other.decision

        override fun hashCode(): Int = decision.hashCode()

        override fun toString(): String = "Disallowed(decision=$decision)"
    }

    /** The hook's ASYNC queue was full, so an event was dropped. */
    public data object Dropped : HookFailure
}
