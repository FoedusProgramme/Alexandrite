package org.foedusprogramme.alexandrite.app

import org.foedusprogramme.alexandrite.runtime.RuntimeProblemKind
import org.foedusprogramme.alexandrite.runtime.RuntimeStartException
import org.foedusprogramme.alexandrite.runtime.StartStage
import org.foedusprogramme.alexandrite.sdk.di.container.DiProblemKind
import org.foedusprogramme.alexandrite.sdk.runtime.StopKind

internal object ExitCode {
    const val OK = 0
    const val FAILURE = 1

    /** `EX_USAGE` */
    const val USAGE = 64

    /** `EX_TEMPFAIL` */
    const val RESTART = 75

    /** `EX_CONFIG` */
    const val CONFIG = 78
}

internal fun exitCode(kind: StopKind): Int = when (kind) {
    StopKind.SHUTDOWN -> ExitCode.OK
    StopKind.RESTART -> ExitCode.RESTART
    StopKind.FAILURE -> ExitCode.FAILURE
}

internal fun exitCode(error: RuntimeStartException): Int {
    error.stopRequest?.let { return exitCode(it.kind) }
    return when (error.stage) {
        StartStage.PLUGINS, StartStage.CONFIG -> ExitCode.CONFIG
        StartStage.GRAPH -> if (invalidGraph(error) || invalidConfig(error)) ExitCode.CONFIG else ExitCode.FAILURE
        StartStage.START -> if (invalidConfig(error)) ExitCode.CONFIG else ExitCode.FAILURE
        StartStage.DATA_DIR, StartStage.OPEN -> ExitCode.FAILURE
    }
}

/** Whether [error] failed on the shape of the graph. */
private fun invalidGraph(error: RuntimeStartException): Boolean {
    val kinds = error.problems.mapNotNull { it.kind as? DiProblemKind }
    return kinds.isNotEmpty() && DiProblemKind.CREATION_FAILED !in kinds
}

/** Whether [error] failed on config that a component refused. */
private fun invalidConfig(error: RuntimeStartException): Boolean =
    error.problems.any { it.kind == RuntimeProblemKind.INVALID_CONFIG }
