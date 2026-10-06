package org.foedusprogramme.alexandrite.runtime

/** A stage of [AlexandriteRuntime.start]. */
public enum class StartStage {
    /** Locking the data directory. */
    DATA_DIR,

    /** Checking the plugin set. */
    PLUGINS,

    /** Switching plugins on and decoding their config. */
    CONFIG,

    /** Building and validating the container. */
    GRAPH,

    /** Starting the container. */
    START,

    /** Opening the container. */
    OPEN,
}
