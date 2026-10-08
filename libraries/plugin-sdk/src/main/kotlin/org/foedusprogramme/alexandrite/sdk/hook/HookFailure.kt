package org.foedusprogramme.alexandrite.sdk.hook

import dev.drewhamilton.poko.Poko
import org.foedusprogramme.alexandrite.sdk.InternalAlexandriteApi
import kotlin.time.Duration

/** How a hook failed. */
@SubclassOptInRequired(InternalAlexandriteApi::class)
public interface HookFailure {
    @OptIn(InternalAlexandriteApi::class)
    @Poko
    public class Threw(public val error: Throwable) : HookFailure

    @OptIn(InternalAlexandriteApi::class)
    @Poko
    public class TimedOut(public val timeout: Duration) : HookFailure

    /** The hook returned a decision its point does not allow. */
    @OptIn(InternalAlexandriteApi::class)
    @Poko
    public class Disallowed(public val decision: HookDecision<*>) : HookFailure

    /** An event queued for the hook was dropped. */
    @OptIn(InternalAlexandriteApi::class)
    public data object Dropped : HookFailure
}
