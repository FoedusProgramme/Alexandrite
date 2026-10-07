package org.foedusprogramme.alexandrite.app

import org.foedusprogramme.alexandrite.runtime.RuntimeStartException
import org.foedusprogramme.alexandrite.runtime.StartStage
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
        StartStage.PLUGINS, StartStage.CONFIG, StartStage.GRAPH -> ExitCode.CONFIG
        StartStage.DATA_DIR, StartStage.START, StartStage.OPEN -> ExitCode.FAILURE
    }
}
