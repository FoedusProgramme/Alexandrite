package org.foedusprogramme.alexandrite.sdk.runtime

import dev.drewhamilton.poko.Poko

/** A request to stop a runtime. */
@Poko
public class StopRequest(public val kind: StopKind, public val reason: String) {
    public companion object {
        public fun shutdown(reason: String): StopRequest = StopRequest(StopKind.SHUTDOWN, reason)

        public fun restart(reason: String): StopRequest = StopRequest(StopKind.RESTART, reason)

        public fun failure(reason: String): StopRequest = StopRequest(StopKind.FAILURE, reason)
    }
}

/** What a stop is for. */
public enum class StopKind {
    /** A stop for good. */
    SHUTDOWN,

    /** A stop the host follows with a new start. */
    RESTART,

    /** A stop forced by a fault. */
    FAILURE,
}
