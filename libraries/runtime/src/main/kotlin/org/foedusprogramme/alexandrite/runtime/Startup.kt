package org.foedusprogramme.alexandrite.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.foedusprogramme.alexandrite.sdk.di.Container
import org.foedusprogramme.alexandrite.sdk.di.StepReport
import org.foedusprogramme.alexandrite.sdk.di.StepReport.Outcome
import org.foedusprogramme.alexandrite.sdk.di.StepReport.Step
import org.foedusprogramme.alexandrite.sdk.problem.Problem
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.TimeMark

private val logger: Logger = LoggerFactory.getLogger(AlexandriteRuntime::class.java)

/** How far a start got and what it built. */
internal class Startup {
    @Volatile
    var stage: StartStage = StartStage.DATA_DIR

    @Volatile
    var lock: DataDirLock? = null

    @Volatile
    var container: Container? = null

    /** Stops and closes the container, then releases the data directory. */
    suspend fun tearDown(name: String, deadline: TimeMark, grace: Duration): List<Problem> {
        val problems = mutableListOf<Problem>()
        try {
            container?.let { built ->
                built.stop(deadline).mapNotNullTo(problems) { problem(name, it, grace) }
                problems += destroyProblems(name, built)
            }
        } catch (e: Exception) {
            logger.error("{}: stopping failed", name, e)
            problems += Problem(RuntimeProblemKind.STOP_FAILED, "Stopping failed: $e", null, null)
        } finally {
            lock?.let { release(name, it) }
        }
        return problems
    }
}

private fun problem(name: String, report: StepReport, grace: Duration): Problem? {
    val what = "${report.step.action} ${report.origin} (plugin ${report.plugin})"
    val outcome = report.outcome
    val message = when (outcome) {
        Outcome.Completed -> return null
        is Outcome.Failed -> "${what.capitalized()} failed: ${outcome.error}"
        Outcome.TimedOut -> "${what.capitalized()} was cancelled: the shutdown grace of $grace ran out."
        Outcome.NotCalled -> "Skipped $what: the shutdown grace of $grace had run out."
    }
    logger.warn("{}: {}", name, message, (outcome as? Outcome.Failed)?.error)
    return Problem(kind(report), message, report.plugin, null)
}

private val Step.action: String
    get() = when (this) {
        Step.CLOSE -> "closing"
        Step.DRAIN -> "draining"
        Step.STOP -> "stopping"
    }

private fun kind(report: StepReport): RuntimeProblemKind = when (report.step) {
    Step.CLOSE -> report.outcome.pick(
        RuntimeProblemKind.CLOSE_FAILED,
        RuntimeProblemKind.CLOSE_TIMED_OUT,
        RuntimeProblemKind.CLOSE_NOT_CALLED,
    )

    Step.DRAIN -> report.outcome.pick(
        RuntimeProblemKind.DRAIN_FAILED,
        RuntimeProblemKind.DRAIN_TIMED_OUT,
        RuntimeProblemKind.DRAIN_NOT_CALLED,
    )

    Step.STOP -> RuntimeProblemKind.STOP_FAILED
}

private fun <T> Outcome.pick(failed: T, timedOut: T, notCalled: T): T = when (this) {
    Outcome.TimedOut -> timedOut
    Outcome.NotCalled -> notCalled
    else -> failed
}

private suspend fun destroyProblems(name: String, container: Container): List<Problem> = try {
    withContext(Dispatchers.IO) { container.close() }
    emptyList()
} catch (e: Exception) {
    (listOf(e) + e.suppressed).map { error ->
        logger.warn("{}: destroying an instance failed", name, error)
        Problem(RuntimeProblemKind.DESTROY_FAILED, "Destroying an instance failed: $error", null, null)
    }
}

private suspend fun release(name: String, lock: DataDirLock) {
    try {
        withContext(Dispatchers.IO) { lock.close() }
    } catch (e: IOException) {
        logger.warn("{}: cannot release the data directory", name, e)
    }
}

private fun String.capitalized(): String = replaceFirstChar(Char::uppercaseChar)
