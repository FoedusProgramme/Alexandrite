package org.foedusprogramme.alexandrite.runtime

/** Where an [AlexandriteRuntime] is in its life. */
public enum class RuntimeState {
    STARTING,

    /** Started and open. */
    READY,

    /** A stop was requested. */
    STOPPING,

    /** Stopped on request. */
    STOPPED,

    /** Its start failed. */
    FAILED,
}
