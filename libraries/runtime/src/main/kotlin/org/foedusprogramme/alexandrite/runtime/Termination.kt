package org.foedusprogramme.alexandrite.runtime

import dev.drewhamilton.poko.Poko
import org.foedusprogramme.alexandrite.sdk.problem.Problem
import org.foedusprogramme.alexandrite.sdk.runtime.StopRequest

/** How an [AlexandriteRuntime] ended. */
@Poko
public class Termination internal constructor(
    public val cause: Cause,
    /** What went wrong while it stopped. */
    public val problems: List<Problem>,
) {
    /** Why a runtime ended. */
    public sealed interface Cause {
        @Poko
        public class Requested internal constructor(public val request: StopRequest) : Cause

        @Poko
        public class StartFailed internal constructor(public val error: RuntimeStartException) : Cause
    }
}
