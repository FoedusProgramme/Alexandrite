package org.foedusprogramme.alexandrite.runtime.lifecycle

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
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
import org.foedusprogramme.alexandrite.runtime.channel.InstanceDirectory
import org.foedusprogramme.alexandrite.runtime.startFailure
import org.foedusprogramme.alexandrite.runtime.startStopped
import org.foedusprogramme.alexandrite.sdk.channel.ChannelControl
import org.foedusprogramme.alexandrite.sdk.channel.InstanceState
import org.foedusprogramme.alexandrite.sdk.chat.ChannelInstanceId
import org.foedusprogramme.alexandrite.sdk.chat.ChatUser
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
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

private val logger: Logger = LoggerFactory.getLogger(AlexandriteRuntime::class.java)

/** The start stages of one runtime in its scope [context], and the teardown of what they built. */
internal class Stages(
    private val spec: RuntimeSpec,
    private val redactor: Redactor,
    control: (plugin: String) -> RuntimeControl,
    private val emit: (RuntimeEvent) -> Unit,
    context: CoroutineContext,
    /** Measures the shutdown grace of an instance that [ChannelControl] stops. */
    private val timeSource: TimeSource,
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

    @Volatile
    private var directory: InstanceDirectory? = null

    /** The channel instance containers in config order. */
    private val instances = CopyOnWriteArrayList<InstanceContainer>()

    private val channels: ChannelControl = InstanceControl()

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

    /**
     * Stops and destroys what the stages built, then releases the data directory: the channel instances close before
     * the root and drain after it, and each instance's scope is cancelled once it has stopped. Instances that
     * [ChannelControl] stops are waited for first.
     */
    suspend fun tearDown(deadline: TimeMark): List<Problem> {
        val grace = spec.config.shutdownGrace
        val problems = mutableListOf<Problem>()
        val fatal = mutableListOf<VirtualMachineError>()
        fun failed(kind: RuntimeProblemKind, what: String, error: Throwable) {
            if (error is VirtualMachineError) fatal += error
            val message = redactor.text("$what failed: $error")
            logger.error("{}: {}", name, message, redactor.error(error))
            problems += Problem(kind, message, null)
        }
        suspend fun step(kind: RuntimeProblemKind, what: String, block: suspend () -> List<StepReport>) {
            try {
                block().mapNotNullTo(problems) { problem(it, grace) }
            } catch (e: Throwable) {
                failed(kind, what, e)
            }
        }
        val root = container
        val all = instances.toList()
        val owned = all.filter { it.claim(setOf(InstanceState.STARTING, InstanceState.OPEN)) }
        all.filterNot { it in owned }.forEach { it.stopped.await() }
        val children = owned.asReversed()
        try {
            for (child in children) {
                step(RuntimeProblemKind.CLOSE_FAILED, "Closing ${child.label}") { child.container.closeAll(deadline) }
            }
            root?.let { step(RuntimeProblemKind.CLOSE_FAILED, "Closing") { it.closeAll(deadline) } }
            root?.let { step(RuntimeProblemKind.DRAIN_FAILED, "Draining") { it.drainAll(deadline) } }
            for (child in children) {
                step(RuntimeProblemKind.DRAIN_FAILED, "Draining ${child.label}") { child.container.drainAll(deadline) }
            }
            for (child in children) {
                directory?.leave(child.id)
                step(RuntimeProblemKind.STOP_FAILED, "Stopping ${child.label}") { child.container.stopAll() }
                try {
                    scopes.cancel(child.scope, child.plugin, child.id, deadline, grace)?.let(problems::add)
                } catch (e: Throwable) {
                    failed(RuntimeProblemKind.PLUGIN_SCOPE_NOT_DONE, "Cancelling the scope of ${child.label}", e)
                }
                child.markStopped()
            }
            root?.let { step(RuntimeProblemKind.STOP_FAILED, "Stopping") { it.stopAll() } }
            try {
                problems += scopes.cancel(deadline, grace)
            } catch (e: Throwable) {
                failed(RuntimeProblemKind.PLUGIN_SCOPE_NOT_DONE, "Cancelling the plugin scopes", e)
            }
            root?.let { step(RuntimeProblemKind.DESTROY_FAILED, "Destroying") { it.destroy() } }
        } finally {
            owned.forEach(InstanceContainer::markStopped)
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
        runStage(StartStage.START, null) { container.start() }
        for (instance in instances) runStage(StartStage.START, instance) { instance.container.start() }
        emit(RuntimeEvent.Started)
        runStage(StartStage.OPEN, null) { container.open() }
        for (instance in instances) {
            runStage(StartStage.OPEN, instance) {
                instance.container.open()
                directory?.join(instance.id, instance.channel)
                instance.opened()
            }
        }
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
        val ids = resolution.enabled.flatMap { plugin -> plugin.config.instances.map { it.id } }
        val directory = InstanceDirectory(ids.toCollection(LinkedHashSet())).also { directory = it }
        assembly.checkChannels(resolution.enabled, bindings)
        val built = assembly.container(resolution.enabled, bindings, directory, channels)
        container = built
        assembly.checkChannelInstances(built, resolution.enabled, bindings)
        assembly.createInstances(built, resolution.enabled, instances::add)
        return built
    }

    /** Runs [block] as part of stage [next], in the container of [instance] or in the root when it is null. */
    private suspend fun runStage(next: StartStage, instance: InstanceContainer?, block: suspend () -> Unit) {
        stage = next
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw startFailure(name, next, cause = e, detail = instance?.let { "${it.label}: $e" })
        }
        currentCoroutineContext().ensureActive()
    }

    /** Closes, drains, stops and destroys [child] alone within the shutdown grace, as [by] asked. */
    private suspend fun stopAlone(child: InstanceContainer, by: ChatUser?) {
        val grace = spec.config.shutdownGrace
        val deadline = timeSource.markNow() + grace
        if (by == null) {
            logger.info("{}: stopping channel instance {}", name, child.id)
        } else {
            logger.info("{}: stopping channel instance {} at the request of {}", name, child.id, by.address)
        }
        suspend fun step(what: String, block: suspend () -> List<StepReport>) {
            try {
                block().forEach { problem(it, grace) }
            } catch (e: Exception) {
                logger.error("{}: {}", name, redactor.text("$what ${child.label} failed: $e"), redactor.error(e))
            }
        }
        try {
            step("Closing") { child.container.closeAll(deadline) }
            step("Draining") { child.container.drainAll(deadline) }
            directory?.leave(child.id)
            step("Stopping") { child.container.stopAll() }
            step("Cancelling the scope of") {
                scopes.cancel(child.scope, child.plugin, child.id, deadline, grace)
                emptyList()
            }
            step("Destroying") { child.container.destroy() }
        } finally {
            child.markStopped()
        }
        logger.info("{}: stopped channel instance {}", name, child.id)
    }

    private fun problem(report: StepReport, grace: Duration): Problem? {
        val where = report.container?.let { " in $it" }.orEmpty()
        val what = "${report.step.action} ${report.origin} (plugin ${report.plugin})$where"
        val outcome = report.outcome
        val message = when (outcome) {
            Outcome.Completed -> return null
            is Outcome.Failed -> "${what.capitalized()} failed: ${outcome.error}"
            Outcome.TimedOut -> "${what.capitalized()} was cancelled: the shutdown grace of $grace ran out."
            Outcome.NotCalled -> "Skipped $what: the shutdown grace of $grace had run out."
        }.let(redactor::text)
        logger.warn("{}: {}", name, message, (outcome as? Outcome.Failed)?.error?.let(redactor::error))
        return Problem(kind(report), message, report.plugin)
    }

    /** The [ChannelControl] over the instance containers of these stages. */
    private inner class InstanceControl : ChannelControl {
        override fun state(instance: ChannelInstanceId): InstanceState? {
            if (directory?.instances?.contains(instance) != true) return null
            return instances.firstOrNull { it.id == instance }?.state ?: InstanceState.STARTING
        }

        override suspend fun stop(instance: ChannelInstanceId, by: ChatUser?): Boolean {
            val child = instances.firstOrNull { it.id == instance } ?: return false
            if (!child.claim(setOf(InstanceState.OPEN))) return false
            withContext(NonCancellable) { stopAlone(child, by) }
            return true
        }
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
