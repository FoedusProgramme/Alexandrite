package org.foedusprogramme.alexandrite.runtime.lifecycle

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import org.foedusprogramme.alexandrite.runtime.AlexandriteRuntime
import org.foedusprogramme.alexandrite.runtime.RuntimeEvent
import org.foedusprogramme.alexandrite.runtime.RuntimeProblemKind
import org.foedusprogramme.alexandrite.runtime.RuntimeSpec
import org.foedusprogramme.alexandrite.runtime.RuntimeStartException
import org.foedusprogramme.alexandrite.runtime.StartStage
import org.foedusprogramme.alexandrite.runtime.startFailure
import org.foedusprogramme.alexandrite.runtime.startStopped
import org.foedusprogramme.alexandrite.sdk.di.container.Container
import org.foedusprogramme.alexandrite.sdk.di.container.StepReport
import org.foedusprogramme.alexandrite.sdk.di.container.StepReport.Outcome
import org.foedusprogramme.alexandrite.sdk.di.container.StepReport.Step
import org.foedusprogramme.alexandrite.sdk.problem.Problem
import org.foedusprogramme.alexandrite.sdk.runtime.RuntimeControl
import org.foedusprogramme.alexandrite.sdk.runtime.StopRequest
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.TimeMark

private val logger: Logger = LoggerFactory.getLogger(AlexandriteRuntime::class.java)

/** The start stages of one runtime in its scope [context], and the teardown of what they built. */
internal class Stages(
    private val spec: RuntimeSpec,
    private val redactor: Redactor,
    control: (plugin: String) -> RuntimeControl,
    private val emit: (RuntimeEvent) -> Unit,
    context: CoroutineContext,
) {
    private val name = spec.config.name
    private val scopes = PluginScopes(name, redactor, context)
    private val assembly = Assembly(spec, redactor, control, scopes, context)

    @Volatile
    private var stage: StartStage = StartStage.DATA_DIR

    @Volatile
    private var lock: DataDirLock? = null

    @Volatile
    var container: Container? = null
        private set

    suspend fun start() {
        val timeout = spec.config.startTimeout
        withTimeoutOrNull(timeout) { stages() }
            ?: throw startFailure(name, stage, detail = "not started within $timeout")
    }

    /** What the start failed with after it ended with [error]. */
    fun failure(error: Throwable): RuntimeStartException =
        error as? RuntimeStartException ?: startFailure(name, stage, cause = error)

    /** What the start throws after [request] cut it short and its teardown met [problems]. */
    fun stopped(request: StopRequest, problems: List<Problem>): RuntimeStartException =
        startStopped(name, stage, request, problems)

    /** Stops and destroys what the stages built, then releases the data directory. */
    suspend fun tearDown(deadline: TimeMark): List<Problem> {
        val grace = spec.config.shutdownGrace
        val problems = mutableListOf<Problem>()
        val fatal = mutableListOf<VirtualMachineError>()
        fun failed(kind: RuntimeProblemKind, what: String, error: Throwable) {
            if (error is VirtualMachineError) fatal += error
            val message = redactor.text("$what failed: $error")
            logger.error("{}: {}", name, message, redactor.error(error))
            problems += Problem(kind, message, null, null)
        }
        val built = container
        try {
            try {
                built?.stop(deadline)?.mapNotNullTo(problems) { problem(it, grace) }
            } catch (e: Throwable) {
                failed(RuntimeProblemKind.STOP_FAILED, "Stopping", e)
            }
            try {
                problems += scopes.cancel(deadline, grace)
            } catch (e: Throwable) {
                failed(RuntimeProblemKind.PLUGIN_SCOPE_NOT_DONE, "Cancelling the plugin scopes", e)
            }
            try {
                built?.destroy()?.mapNotNullTo(problems) { problem(it, grace) }
            } catch (e: Throwable) {
                failed(RuntimeProblemKind.DESTROY_FAILED, "Destroying", e)
            }
        } finally {
            lock?.let(::release)
        }
        fatal.firstOrNull()?.let { throw it }
        return problems
    }

    private suspend fun stages() {
        currentCoroutineContext().ensureActive()
        lock = assembly.lockDataDir()
        assembly.createCacheDir()
        val container = runInterruptible { assemble() }
        runStage(StartStage.START) { container.start() }
        emit(RuntimeEvent.Started)
        runStage(StartStage.OPEN) { container.open() }
    }

    private fun assemble(): Container {
        stage = StartStage.PLUGINS
        spec.plugins.unlisted.takeIf { it.isNotEmpty() }?.let { emit(RuntimeEvent.UnlistedIndexes(it)) }
        assembly.checkPlugins()
        stage = StartStage.CONFIG
        val resolution = assembly.resolvePlugins()
        val plugins = resolution.enabled.map { it.member.plugin }
        emit(RuntimeEvent.PluginsResolved(plugins, resolution.disabled, resolution.unknownPluginConfig))
        stage = StartStage.GRAPH
        val bindings = assembly.pluginBindings(resolution.enabled)
        val built = assembly.container(resolution.enabled, bindings)
        container = built
        assembly.checkChannelInstances(built, bindings)
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

    private fun problem(report: StepReport, grace: Duration): Problem? {
        val what = "${report.step.action} ${report.origin} (plugin ${report.plugin})"
        val outcome = report.outcome
        val message = when (outcome) {
            Outcome.Completed -> return null
            is Outcome.Failed -> "${what.capitalized()} failed: ${outcome.error}"
            Outcome.TimedOut -> "${what.capitalized()} was cancelled: the shutdown grace of $grace ran out."
            Outcome.NotCalled -> "Skipped $what: the shutdown grace of $grace had run out."
        }.let(redactor::text)
        logger.warn("{}: {}", name, message, (outcome as? Outcome.Failed)?.error?.let(redactor::error))
        return Problem(kind(report), message, report.plugin, null)
    }

    private fun release(lock: DataDirLock) {
        try {
            lock.close()
        } catch (e: IOException) {
            logger.warn("{}: cannot release the data directory", name, redactor.error(e))
        }
    }
}

private val Step.action: String
    get() = when (this) {
        Step.CLOSE -> "closing"
        Step.DRAIN -> "draining"
        Step.STOP -> "stopping"
        Step.DESTROY -> "destroying"
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

    Step.DESTROY -> RuntimeProblemKind.DESTROY_FAILED
}

private fun <T> Outcome.pick(failed: T, timedOut: T, notCalled: T): T = when (this) {
    Outcome.TimedOut -> timedOut
    Outcome.NotCalled -> notCalled
    else -> failed
}

private fun String.capitalized(): String = replaceFirstChar(Char::uppercaseChar)
