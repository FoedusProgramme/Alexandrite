package org.foedusprogramme.alexandrite.runtime

import org.foedusprogramme.alexandrite.sdk.problem.Problem
import org.foedusprogramme.alexandrite.sdk.runtime.StopRequest

/** Why an [AlexandriteRuntime] could not start. */
public class RuntimeStartException internal constructor(
    message: String,
    public val stage: StartStage,
    /** What failed the start, then what failed while tearing it down. */
    public val problems: List<Problem>,
    /** The stop request that cut the start short, null when the start failed. */
    public val stopRequest: StopRequest?,
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
    return RuntimeStartException(message, stage, problems, null, cause)
}

internal fun startStopped(
    runtime: String,
    stage: StartStage,
    request: StopRequest,
    problems: List<Problem>,
): RuntimeStartException = RuntimeStartException(
    "Cannot start runtime '$runtime': a stop was requested at stage $stage: $request",
    stage,
    problems,
    request,
    null,
)

internal fun RuntimeStartException.plusTeardown(teardown: List<Problem>): RuntimeStartException {
    if (teardown.isEmpty()) return this
    return RuntimeStartException(message.orEmpty(), stage, problems + teardown, stopRequest, cause)
        .also { it.stackTrace = stackTrace }
}
