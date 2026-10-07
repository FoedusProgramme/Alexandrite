package org.foedusprogramme.alexandrite.runtime

/** Where a started [AlexandriteRuntime] is in its life. */
public enum class RuntimeState {
    /** Started and open. */
    READY,

    /** A stop was requested. */
    STOPPING,

    /** Torn down. */
    STOPPED,
}
