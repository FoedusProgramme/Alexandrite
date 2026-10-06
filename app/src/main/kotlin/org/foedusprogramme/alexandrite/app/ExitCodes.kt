package org.foedusprogramme.alexandrite.app

import org.foedusprogramme.alexandrite.runtime.StartStage
import org.foedusprogramme.alexandrite.runtime.Termination
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

internal fun exitCode(termination: Termination): Int = when (val cause = termination.cause) {
    is Termination.Cause.Requested -> when (cause.request.kind) {
        StopKind.SHUTDOWN -> ExitCode.OK
        StopKind.RESTART -> ExitCode.RESTART
        StopKind.FAILURE -> ExitCode.FAILURE
    }

    is Termination.Cause.StartFailed -> when (cause.error.stage) {
        StartStage.PLUGINS, StartStage.CONFIG, StartStage.GRAPH -> ExitCode.CONFIG
        StartStage.DATA_DIR, StartStage.START, StartStage.OPEN -> ExitCode.FAILURE
    }
}
