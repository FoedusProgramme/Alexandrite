package org.foedusprogramme.alexandrite.runtime

import org.foedusprogramme.alexandrite.sdk.problem.Problem

/** A stage of [AlexandriteRuntime.start]. */
public enum class StartStage {
    /** Checking the plugin set. */
    PLUGINS,

    /** Switching plugins on and decoding their config. */
    CONFIG,

    /** Building and validating the container. */
    GRAPH,

    /** Starting the container. */
    START,
}

/** Thrown when an [AlexandriteRuntime] cannot start. */
public class RuntimeStartException internal constructor(
    message: String,
    public val stage: StartStage,
    public val problems: List<Problem>,
    cause: Throwable?,
) : RuntimeException(message, cause)
