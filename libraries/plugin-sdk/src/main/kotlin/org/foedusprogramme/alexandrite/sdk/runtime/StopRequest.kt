package org.foedusprogramme.alexandrite.sdk.runtime

import dev.drewhamilton.poko.Poko
import org.foedusprogramme.alexandrite.sdk.InternalAlexandriteApi

/** A request to stop a runtime. */
@Poko
public class StopRequest private constructor(
    public val kind: StopKind,
    public val reason: String,
    /** The plugin that requested the stop, null when a host or the runtime did. */
    public val plugin: String?,
) {
    public constructor(kind: StopKind, reason: String) : this(kind, reason, null)

    /** This request as made by [plugin]. */
    @InternalAlexandriteApi
    public fun from(plugin: String): StopRequest = StopRequest(kind, reason, plugin)

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
