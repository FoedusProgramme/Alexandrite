package org.unitedsoftware.alexandrite.sdk.di.container

import org.unitedsoftware.alexandrite.sdk.problem.Problem

/** Thrown when a [Container] cannot be built or used. */
public class DiException internal constructor(
    message: String,
    public val problems: List<Problem>,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    internal constructor(problem: Problem, cause: Throwable? = null) : this(problem.message, listOf(problem), cause)
}
