package org.foedusprogramme.alexandrite.runtime

import org.foedusprogramme.alexandrite.sdk.problem.Problem

/** Why an [AlexandriteRuntime] could not start. */
public class RuntimeStartException internal constructor(
    message: String,
    public val stage: StartStage,
    public val problems: List<Problem>,
    cause: Throwable?,
) : RuntimeException(message, cause)

internal fun startFailure(
    runtime: String,
    stage: StartStage,
    problems: List<Problem> = emptyList(),
    cause: Throwable? = null,
    detail: String? = null,
): RuntimeStartException {
    val head = "Cannot start runtime '$runtime': stage $stage failed"
    val message = if (problems.isEmpty()) {
        "$head: ${detail ?: cause}"
    } else {
        val count = if (problems.size == 1) "1 problem" else "${problems.size} problems"
        "$head ($count):" + problems.joinToString("") { "\n- ${it.message}" }
    }
    return RuntimeStartException(message, stage, problems, cause)
}
