package org.foedusprogramme.alexandrite.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.completeWith
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.future.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.foedusprogramme.alexandrite.sdk.di.Container
import org.foedusprogramme.alexandrite.sdk.runtime.HostApi
import org.foedusprogramme.alexandrite.sdk.runtime.RuntimeControl
import org.foedusprogramme.alexandrite.sdk.runtime.StopKind
import org.foedusprogramme.alexandrite.sdk.runtime.StopRequest
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** An Alexandrite instance assembled from [spec]. */
public class AlexandriteRuntime(private val spec: RuntimeSpec) : AutoCloseable {
    private val name = spec.config.name
    private val lock = Any()
    private val mutableState = MutableStateFlow(RuntimeState.NEW)
    private val events = Channel<Queued>(Channel.UNLIMITED)
    private val terminated = CompletableFuture<Termination>()
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineName("runtime $name") + OWNER.asContextElement(this) +
            CoroutineExceptionHandler { _, e -> logger.error("{}: a lifecycle coroutine failed", name, e) },
    )
    private val control = object : RuntimeControl {
        override fun requestStop(request: StopRequest) = this@AlexandriteRuntime.requestStop(request)
    }
    private val assembly = Assembly(spec, control) { resolved ->
        synchronized(lock) {
            loaded = resolved.loaded
            emitWhileStarting(resolved)
        }
    }
    private var stopRequest: StopRequest? = null
    private var stopDeadline: TimeMark? = null
    private var startWork: Job? = null
    private var startCancelled = false
    private var startFailed = false
    private var running: Startup? = null
    private var handle: RuntimeServices? = null
    private var loaded: List<LoadedPlugin>? = null

    init {
        scope.launch { deliverEvents() }
    }

    public val state: StateFlow<RuntimeState> = mutableState.asStateFlow()

    /** Resolves the [HostApi] types while the runtime is [RuntimeState.READY]. */
    public val services: RuntimeServices
        get() = synchronized(lock) {
            unavailable()?.let { throw IllegalStateException(it) }
            checkNotNull(handle)
        }

    /** The plugins [start] loads. */
    public val plugins: List<LoadedPlugin>
        get() = synchronized(lock) {
            loaded ?: throw IllegalStateException(
                when (mutableState.value) {
                    RuntimeState.NEW -> "Runtime '$name' has not resolved its plugins: call start() first."
                    RuntimeState.STARTING -> "Runtime '$name' is still resolving its plugins."
                    RuntimeState.FAILED -> "Runtime '$name' failed to start before resolving its plugins."
                    else -> "Runtime '$name' was stopped before resolving its plugins."
                },
            )
        }

    /** Runs the start stages within the start timeout and returns once the runtime is [RuntimeState.READY]. */
    public suspend fun start() {
        synchronized(lock) {
            val current = mutableState.value
            check(current == RuntimeState.NEW) { "Cannot start runtime '$name': ${current.description}." }
            mutableState.value = RuntimeState.STARTING
        }
        val outcome = CompletableDeferred<Unit>()
        scope.launch { outcome.completeWith(runCatching { startUp() }) }
        try {
            outcome.await()
        } catch (e: RuntimeStartException) {
            terminated.await()
            throw e
        } catch (e: CancellationException) {
            if (currentCoroutineContext().isActive) throw e
            cancelStart()
            withContext(NonCancellable) { outcome.join() }
            throw e
        }
    }

    /** Requests a stop unless one was requested before, and returns at once. */
    public fun requestStop(request: StopRequest = HOST_REQUEST) {
        val deadline = TimeSource.Monotonic.markNow() + spec.config.shutdownGrace
        var cancelled: Job? = null
        var stopping: Startup? = null
        val ignored = synchronized(lock) {
            val state = mutableState.value
            when {
                stopRequest != null -> "$stopRequest came first"

                startFailed -> "its start failed"

                state == RuntimeState.STOPPING || state == RuntimeState.STOPPED || state == RuntimeState.FAILED ->
                    state.description

                else -> {
                    stopRequest = request
                    stopDeadline = deadline
                    if (state == RuntimeState.NEW) {
                        val termination = Termination(Termination.Cause.Requested(request), emptyList())
                        moveTo(RuntimeState.STOPPED, RuntimeEvent.Stopped(termination), termination)
                    } else {
                        moveTo(RuntimeState.STOPPING, RuntimeEvent.Stopping(request))
                        if (state == RuntimeState.STARTING) cancelled = startWork else stopping = running
                    }
                    null
                }
            }
        }
        if (ignored != null) {
            logger.info("{}: ignoring the stop request {}: {}", name, request, ignored)
            return
        }
        logger.info("{}: stopping: {}", name, request)
        cancelled?.cancel()
        stopping?.let { startup -> scope.launch { runStop(request, startup, deadline) } }
    }

    /** Requests a stop and waits for the [Termination]. */
    public suspend fun stop(request: StopRequest = HOST_REQUEST): Termination {
        requestStop(request)
        return awaitTermination()
    }

    /** The [Termination], once the final event has been delivered. */
    public suspend fun awaitTermination(): Termination {
        check(terminated.isDone || OWNER.get() !== this) {
            "Runtime '$name' cannot await its termination from its listener or its own lifecycle calls."
        }
        return terminated.await()
    }

    /** Requests a stop and blocks until the runtime has terminated, or only requests it from the listener. */
    override fun close() {
        val state = mutableState.value
        if (state != RuntimeState.STOPPED && state != RuntimeState.FAILED) requestStop()
        if (OWNER.get() !== this) terminated.join()
    }

    private suspend fun startUp() {
        val startup = Startup()
        val error = try {
            coroutineScope {
                val work = async { stagesWithin(startup) }
                if (!register(work)) work.cancel()
                work.await()
            }
            if (becameReady(startup)) return
            null
        } catch (e: Throwable) {
            e
        }
        val (stopped, deadline) = synchronized(lock) {
            startWork = null
            val request = stopRequest.takeIf { mutableState.value == RuntimeState.STOPPING }
            if (request == null) startFailed = true
            request to (stopDeadline ?: TimeSource.Monotonic.markNow() + spec.config.shutdownGrace)
        }
        val failure = startError(startup, error, stopped != null)
        val problems = startup.tearDown(name, deadline, spec.config.shutdownGrace)
        val cause = stopped?.let(Termination.Cause::Requested) ?: Termination.Cause.StartFailed(failure)
        terminate(Termination(cause, problems))
        throw failure
    }

    private suspend fun stagesWithin(startup: Startup) {
        val timeout = spec.config.startTimeout
        withTimeoutOrNull(timeout) { stages(startup) }
            ?: throw startFailure(name, startup.stage, detail = "not started within $timeout")
    }

    private suspend fun stages(startup: Startup) {
        withContext(Dispatchers.IO) { startup.lock = assembly.lockDataDir() }
        val container = runInterruptible(Dispatchers.IO) { assembly.assemble(startup) }
        startup.stage = StartStage.START
        stage(StartStage.START) { container.start() }
        emitWhileStarting(RuntimeEvent.Started)
        startup.stage = StartStage.OPEN
        stage(StartStage.OPEN) { container.open() }
    }

    private suspend fun stage(stage: StartStage, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw startFailure(name, stage, cause = e)
        }
        currentCoroutineContext().ensureActive()
    }

    /** Registers [work] for cancellation, or returns false when the start was already cut short. */
    private fun register(work: Job): Boolean = synchronized(lock) {
        (mutableState.value == RuntimeState.STARTING && !startCancelled).also { if (it) startWork = work }
    }

    private fun cancelStart() {
        synchronized(lock) {
            startCancelled = true
            startWork
        }?.cancel()
    }

    private fun becameReady(startup: Startup): Boolean = synchronized(lock) {
        startWork = null
        if (mutableState.value != RuntimeState.STARTING || startCancelled) return false
        running = startup
        handle = RuntimeServices(checkNotNull(startup.container)) { synchronized(lock) { unavailable() } }
        moveTo(RuntimeState.READY, RuntimeEvent.Ready)
        true
    }

    private fun startError(startup: Startup, error: Throwable?, stopped: Boolean): RuntimeStartException = when {
        stopped -> startFailure(name, startup.stage, detail = "stopped while starting").also { failure ->
            if (error != null && error !is CancellationException) failure.addSuppressed(error)
        }

        error is RuntimeStartException -> error

        synchronized(lock) { startCancelled } -> startFailure(name, startup.stage, cause = error, detail = "cancelled")

        else -> startFailure(name, startup.stage, cause = error)
    }

    private suspend fun runStop(request: StopRequest, startup: Startup, deadline: TimeMark) {
        val problems = startup.tearDown(name, deadline, spec.config.shutdownGrace)
        terminate(Termination(Termination.Cause.Requested(request), problems))
    }

    /** Moves to the final state of [termination] and queues the final event. */
    private fun terminate(termination: Termination) {
        when (val cause = termination.cause) {
            is Termination.Cause.Requested -> {
                logger.info("{}: stopped", name)
                moveTo(RuntimeState.STOPPED, RuntimeEvent.Stopped(termination), termination)
            }

            is Termination.Cause.StartFailed ->
                moveTo(RuntimeState.FAILED, RuntimeEvent.StartFailed(cause.error), termination)
        }
    }

    private fun moveTo(next: RuntimeState, event: RuntimeEvent, termination: Termination? = null) {
        synchronized(lock) {
            mutableState.value = next
            events.trySend(Queued(event, termination))
        }
    }

    private fun emitWhileStarting(event: RuntimeEvent) {
        synchronized(lock) {
            if (mutableState.value == RuntimeState.STARTING) events.trySend(Queued(event, null))
        }
    }

    private suspend fun deliverEvents() {
        for (queued in events) {
            try {
                spec.listener.onEvent(queued.event)
            } catch (e: Throwable) {
                if (e is VirtualMachineError) throw e
                logger.warn("{}: listener failed on {}", name, queued.event, e)
            }
            val termination = queued.termination ?: continue
            terminated.complete(termination)
            scope.cancel()
            return
        }
    }

    /** Why the runtime has no services, null while it has them. */
    private fun unavailable(): String? = when (val state = mutableState.value) {
        RuntimeState.READY -> null
        RuntimeState.NEW, RuntimeState.STARTING -> "Runtime '$name' has no services until start() returns."
        else -> "Runtime '$name' has no services: ${state.description}."
    }

    /** An event and, on the final one, the termination it ends with. */
    private class Queued(val event: RuntimeEvent, val termination: Termination?)

    private companion object {
        val logger: Logger = LoggerFactory.getLogger(AlexandriteRuntime::class.java)

        val HOST_REQUEST = StopRequest(StopKind.SHUTDOWN, "requested by the host")

        /** The runtime whose lifecycle coroutine runs on the current thread. */
        val OWNER = ThreadLocal<AlexandriteRuntime?>()
    }
}

private val RuntimeState.description: String
    get() = when (this) {
        RuntimeState.NEW -> "it is new"
        RuntimeState.STARTING -> "it is starting"
        RuntimeState.READY -> "it has started"
        RuntimeState.STOPPING -> "it is stopping"
        RuntimeState.STOPPED -> "it has stopped"
        RuntimeState.FAILED -> "it failed to start"
    }
