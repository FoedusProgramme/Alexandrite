package org.unitedsoftware.alexandrite.runtime

import dev.drewhamilton.poko.Poko
import org.unitedsoftware.alexandrite.sdk.problem.Problem
import org.unitedsoftware.alexandrite.sdk.runtime.StopRequest

/** How an [AlexandriteRuntime] ended. */
@Poko
public class Termination internal constructor(
    /** The stop request that won. */
    public val request: StopRequest,
    /** What went wrong while it stopped. */
    public val problems: List<Problem>,
)
