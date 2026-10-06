package org.foedusprogramme.alexandrite.runtime.lifecycle

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.foedusprogramme.alexandrite.runtime.AlexandriteRuntime
import org.foedusprogramme.alexandrite.runtime.RuntimeEvent
import org.foedusprogramme.alexandrite.runtime.RuntimeProblemKind
import org.foedusprogramme.alexandrite.runtime.RuntimeSpec
import org.foedusprogramme.alexandrite.runtime.RuntimeStartException
import org.foedusprogramme.alexandrite.runtime.StartStage
import org.foedusprogramme.alexandrite.runtime.startFailure
import org.foedusprogramme.alexandrite.sdk.di.container.Container
import org.foedusprogramme.alexandrite.sdk.di.container.StepReport
import org.foedusprogramme.alexandrite.sdk.di.container.StepReport.Outcome
import org.foedusprogramme.alexandrite.sdk.di.container.StepReport.Step
import org.foedusprogramme.alexandrite.sdk.problem.Problem
import org.foedusprogramme.alexandrite.sdk.runtime.RuntimeControl
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.TimeMark

private val logger: Logger = LoggerFactory.getLogger(AlexandriteRuntime::class.java)

/** One start of a runtime: its stages, and the teardown of what they built. */
internal class Launch(
    private val spec: RuntimeSpec,
    control: RuntimeControl,
    private val resolved: (RuntimeEvent.PluginsResolved) -> Unit,
    private val started: () -> Unit,
) {
    private val name = spec.config.name
    private val assembly = Assembly(spec, control)

    @Volatile
    private var stage: StartStage = StartStage.DATA_DIR

    @Volatile
    private var lock: DataDirLock? = null

    @Volatile
    var container: Container? = null
        private set

    /** Runs the start stages within the start timeout. */
    suspend fun run() {
        val timeout = spec.config.startTimeout
        withTimeoutOrNull(timeout) { stages() }
            ?: throw startFailure(name, stage, detail = "not started within $timeout")
    }

    /** What the start throws after it ended with [error]. */
    fun failure(error: Throwable?, stopped: Boolean, cancelled: Boolean): RuntimeStartException = when {
        stopped -> startFailure(name, stage, detail = "stopped while starting").also { failure ->
            if (error != null && error !is CancellationException) failure.addSuppressed(error)
        }

        error is RuntimeStartException -> error

        cancelled -> startFailure(name, stage, cause = error, detail = "cancelled")

        else -> startFailure(name, stage, cause = error)
    }

    /** Stops and destroys what the stages built, then releases the data directory. */
    suspend fun tearDown(deadline: TimeMark): List<Problem> {
        val grace = spec.config.shutdownGrace
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

    private suspend fun stages() {
        withContext(Dispatchers.IO) { lock = assembly.lockDataDir() }
        val container = runInterruptible(Dispatchers.IO) { assemble() }
        runStage(StartStage.START) { container.start() }
        started()
        runStage(StartStage.OPEN) { container.open() }
    }

    private fun assemble(): Container {
        stage = StartStage.PLUGINS
        assembly.checkPlugins()
        stage = StartStage.CONFIG
        val resolution = assembly.resolvePlugins()
        val loaded = resolution.enabled.map { it.member.plugin }
        val unknown = resolution.unknownPluginConfig
        resolved(RuntimeEvent.PluginsResolved(loaded, resolution.disabled, spec.plugins.unlisted, unknown))
        stage = StartStage.GRAPH
        val plugins = assembly.pluginBindings(resolution.enabled)
        val built = assembly.container(resolution.enabled, plugins)
        container = built
        assembly.checkChannelInstances(built, plugins)
        return built
    }

    private suspend fun runStage(next: StartStage, block: suspend () -> Unit) {
        stage = next
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw startFailure(name, next, cause = e)
        }
        currentCoroutineContext().ensureActive()
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
