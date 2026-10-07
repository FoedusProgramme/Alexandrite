package org.foedusprogramme.alexandrite.sdk.runtime

/** Lets a plugin stop the runtime it runs in. */
public interface RuntimeControl {
    /** Requests a stop and returns at once. */
    public fun stop(request: StopRequest)
}
