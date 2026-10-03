package org.foedusprogramme.alexandrite.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import org.foedusprogramme.alexandrite.runtime.hook.HookDispatcher
import org.foedusprogramme.alexandrite.runtime.hook.HookFailureListener
import org.foedusprogramme.alexandrite.sdk.di.Container
import org.foedusprogramme.alexandrite.sdk.di.DiException
import org.foedusprogramme.alexandrite.sdk.di.PluginBindings
import org.foedusprogramme.alexandrite.sdk.di.Scope
import org.foedusprogramme.alexandrite.sdk.hook.HookFailure
import org.foedusprogramme.alexandrite.sdk.problem.Problem
import org.foedusprogramme.alexandrite.sdk.runtime.HostApi
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.time.TimeSource

/** An Alexandrite instance assembled from [spec]. */
public class AlexandriteRuntime(private val spec: RuntimeSpec) : AutoCloseable {
    private val name = spec.config.name
    private val lock = Any()
    private val eventLock = Any()
    private var state = State.NEW
    private var starting: Job? = null
    private var container: Container? = null
    private var hooks: HookDispatcher? = null
    private var handle: RuntimeServices? = null
    private var loaded: List<LoadedPlugin>? = null
    private var closedEmitted = false

    private val hookFailures = HookFailureListener { hook, point, failure ->
        val error = (failure as? HookFailure.Threw)?.error
        logger.warn("{}: hook {} failed at '{}': {}", name, hook.javaClass.name, point, failure, error)
    }

    /** Resolves the [HostApi] types once [start] has returned. */
    public val services: RuntimeServices
        get() = synchronized(lock) {
            unavailable()?.let { throw IllegalStateException(it) }
            checkNotNull(handle)
        }

    /** The plugins [start] loads. */
    public val plugins: List<LoadedPlugin>
        get() = synchronized(lock) {
            loaded ?: throw IllegalStateException(
                when (state) {
                    State.NEW -> "Runtime '$name' has not resolved its plugins: call start() first."
                    State.STARTING -> "Runtime '$name' is still resolving its plugins."
                    State.FAILED -> "Runtime '$name' failed to start before resolving its plugins."
                    State.STARTED, State.CLOSED -> "Runtime '$name' was closed before resolving its plugins."
                },
            )
        }

    /** Assembles the container from the plugin set and its config, then starts it, within the start timeout. */
    public suspend fun start() {
        synchronized(lock) {
            check(state == State.NEW) { "Cannot start runtime '$name': ${state.description}." }
            state = State.STARTING
        }
        val progress = Progress()
        val container = try {
            startWithin(progress)
        } catch (e: Throwable) {
            throw failed(progress, e, cancelled = !currentCoroutineContext().isActive)
        }
        val started = synchronized(eventLock) {
            val started = synchronized(lock) {
                (state == State.STARTING).also {
                    if (it) {
                        state = State.STARTED
                        starting = null
                        handle = RuntimeServices(container) { synchronized(lock) { unavailable() } }
                    }
                }
            }
            if (started) emit(RuntimeEvent.Started)
            started
        }
        if (!started) throw failed(progress, closedWhileStarting(progress), cancelled = false)
    }

    /** Drains the hook queues within the shutdown grace and closes the container, or cancels a running [start]. */
    override fun close() {
        val open = synchronized(lock) {
            if (state == State.CLOSED) return
            val wasStarting = state == State.STARTING
            state = State.CLOSED
            handle = null
            if (wasStarting) {
                null
            } else {
                (container to hooks).also {
                    container = null
                    hooks = null
                }
            }
        }
        if (open == null) {
            synchronized(lock) { starting }?.cancel()
            return
        }
        try {
            stop(open.first, open.second)
        } finally {
            emit(RuntimeEvent.Closed)
        }
    }

    private fun stop(container: Container?, hooks: HookDispatcher?) {
        if (container == null) return
        hooks?.let(::drain)
        container.close()
    }

    private fun drain(hooks: HookDispatcher) {
        val deadline = TimeSource.Monotonic.markNow() + spec.config.shutdownGrace
        runBlocking { hooks.drain(deadline) }
    }

    private suspend fun startWithin(progress: Progress): Container = coroutineScope {
        val work = async { withTimeoutOrNull(spec.config.startTimeout) { assembleAndStart(progress) } }
        val running = synchronized(lock) { (state == State.STARTING).also { if (it) starting = work } }
        if (!running) work.cancel()
        work.await() ?: throw failure(progress.stage, detail = "not started within ${spec.config.startTimeout}")
    }

    private suspend fun assembleAndStart(progress: Progress): Container {
        val container = runInterruptible(Dispatchers.IO) { assemble(progress) }
        progress.stage = StartStage.START
        try {
            container.start()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw failure(StartStage.START, cause = e)
        }
        return container
    }

    private fun assemble(progress: Progress): Container {
        val plugins = spec.plugins
        plugins.unlisted.forEach {
            logger.warn("{}: not loading {}: the index is neither built in nor added to the plugin set", name, it)
        }
        pluginProblems(plugins).takeIf { it.isNotEmpty() }?.let { throw failure(StartStage.PLUGINS, it) }
        progress.stage = StartStage.CONFIG
        val resolution = try {
            resolveConfig(plugins.entries, spec.pluginConfig)
        } catch (e: Exception) {
            throw failure(StartStage.CONFIG, cause = e)
        }
        resolution.unknownPluginConfig.forEach {
            logger.warn("{}: ignoring the config at '{}': no plugin of the plugin set reads it", name, it)
        }
        if (resolution.problems.isNotEmpty()) throw failure(StartStage.CONFIG, resolution.problems)
        val loaded = resolution.enabled.map { it.entry.plugin }
        synchronized(lock) { this.loaded = loaded }
        logger.info(
            "{}: loading plugins [{}], disabled [{}]",
            name,
            loaded.joinToString { it.info.id },
            resolution.disabled.joinToString { "${it.id} (${it.reason})" },
        )
        val unknown = resolution.unknownPluginConfig
        emit(RuntimeEvent.PluginsResolved(loaded, resolution.disabled, plugins.unlisted, unknown))
        progress.stage = StartStage.GRAPH
        return build(resolution.enabled)
    }

    private fun build(enabled: List<EnabledPlugin>): Container {
        val plugins = try {
            enabled.map { PluginBindings(it.entry.id, it.entry.index.bindings() + it.configBindings) }
        } catch (e: Exception) {
            throw failure(StartStage.GRAPH, cause = e)
        }
        val container = try {
            val infos = enabled.map { it.entry.plugin.info }
            Container.build(plugins + runtimeBindings(spec.config, infos, hookFailures, ::created))
        } catch (e: DiException) {
            throw failure(StartStage.GRAPH, e.problems, e)
        } catch (e: Exception) {
            throw failure(StartStage.GRAPH, cause = e)
        }
        synchronized(lock) { this.container = container }
        val problems = try {
            plugins.filter { plugin -> plugin.bindings.any { it.scope == Scope.CHANNEL_INSTANCE } }
                .flatMap { container.validateChild(setOf(it.id)) }
        } catch (e: Exception) {
            throw closing(container, failure(StartStage.GRAPH, cause = e))
        }
        if (problems.isNotEmpty()) throw closing(container, failure(StartStage.GRAPH, problems))
        return container
    }

    private fun created(dispatcher: HookDispatcher) {
        synchronized(lock) { hooks = dispatcher }
    }

    /** What [start] throws for [error], once what it built is closed and the failure reported. */
    private fun failed(progress: Progress, error: Throwable, cancelled: Boolean): Throwable {
        val closedEarly = synchronized(lock) { state == State.CLOSED }
        val failure = when {
            error is RuntimeStartException -> error
            cancelled -> failure(progress.stage, cause = error, detail = "cancelled")
            closedEarly -> closedWhileStarting(progress)
            else -> failure(progress.stage, cause = error)
        }
        val built = synchronized(lock) {
            starting = null
            container.also {
                container = null
                hooks = null
            }
        }
        built?.let { closing(it, failure) }
        synchronized(eventLock) {
            val closed = synchronized(lock) { (state == State.CLOSED).also { if (!it) state = State.FAILED } }
            emit(RuntimeEvent.StartFailed(failure))
            if (closed) emit(RuntimeEvent.Closed)
        }
        return if (cancelled && error is CancellationException) error else failure
    }

    /** [error] after closing [container], with what the closing threw suppressed. */
    private fun <E : Throwable> closing(container: Container, error: E): E {
        synchronized(lock) {
            if (this.container === container) {
                this.container = null
                hooks = null
            }
        }
        try {
            container.close()
        } catch (e: Exception) {
            error.addSuppressed(e)
        }
        return error
    }

    private fun failure(
        stage: StartStage,
        problems: List<Problem> = emptyList(),
        cause: Throwable? = null,
        detail: String? = null,
    ): RuntimeStartException {
        val head = "Cannot start runtime '$name': stage $stage failed"
        val message = if (problems.isEmpty()) {
            "$head: ${detail ?: cause}"
        } else {
            val count = if (problems.size == 1) "1 problem" else "${problems.size} problems"
            "$head ($count):" + problems.joinToString("") { "\n- ${it.message}" }
        }
        return RuntimeStartException(message, stage, problems, cause)
    }

    private fun closedWhileStarting(progress: Progress) = failure(progress.stage, detail = "closed while starting")

    /** Why the runtime has no services, null while it has them. */
    private fun unavailable(): String? = when (state) {
        State.STARTED -> null
        State.NEW, State.STARTING -> "Runtime '$name' has no services until start() returns."
        else -> "Runtime '$name' has no services: ${state.description}."
    }

    private fun emit(event: RuntimeEvent) {
        synchronized(eventLock) {
            if (closedEmitted) return
            if (event == RuntimeEvent.Closed) closedEmitted = true
            try {
                spec.listener.onEvent(event)
            } catch (e: Exception) {
                logger.warn("{}: listener failed on {}", name, event, e)
            }
        }
    }

    /** The stage a [start] has reached. */
    private class Progress {
        @Volatile
        var stage = StartStage.PLUGINS
    }

    private enum class State(val description: String) {
        NEW("it is new"),
        STARTING("it is starting"),
        STARTED("it has started"),
        FAILED("it failed to start"),
        CLOSED("it is closed"),
    }

    private companion object {
        val logger: Logger = LoggerFactory.getLogger(AlexandriteRuntime::class.java)
    }
}
