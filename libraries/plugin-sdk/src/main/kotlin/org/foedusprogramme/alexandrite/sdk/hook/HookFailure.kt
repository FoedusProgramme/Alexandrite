package org.foedusprogramme.alexandrite.sdk.hook

import dev.drewhamilton.poko.Poko
import kotlin.time.Duration

/** How a hook failed. */
public sealed interface HookFailure {
    @Poko
    public class Threw(public val error: Throwable) : HookFailure

    @Poko
    public class TimedOut(public val timeout: Duration) : HookFailure

    /** The hook returned a decision its point does not allow. */
    @Poko
    public class Disallowed(public val decision: HookDecision<*>) : HookFailure

    /** The hook's ASYNC queue was full, so an event was dropped. */
    public data object Dropped : HookFailure
}
