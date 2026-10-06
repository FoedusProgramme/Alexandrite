package org.foedusprogramme.alexandrite.runtime

/** Where an [AlexandriteRuntime] is in its life. */
public enum class RuntimeState {
    NEW,

    STARTING,

    /** Started and open. */
    READY,

    STOPPING,

    /** Stopped on request. */
    STOPPED,

    /** Its start failed. */
    FAILED,
}
