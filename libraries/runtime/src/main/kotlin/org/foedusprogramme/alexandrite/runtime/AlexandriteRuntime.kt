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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.future.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.foedusprogramme.alexandrite.runtime.lifecycle.Launch
import org.foedusprogramme.alexandrite.runtime.plugin.LoadedPlugin
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
    private var stopRequest: StopRequest? = null
    private var stopDeadline: TimeMark? = null
    private var startWork: Job? = null
    private var startCancelled = false
    private var startFailed = false
    private var running: Launch? = null
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
        var stopping: Launch? = null
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
        stopping?.let { stopped -> scope.launch { runStop(request, stopped, deadline) } }
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
        val launch = Launch(spec, control, ::pluginsResolved) { emitWhileStarting(RuntimeEvent.Started) }
        val error = try {
            coroutineScope {
                val work = async { launch.run() }
                if (!register(work)) work.cancel()
                work.await()
            }
            if (becameReady(launch)) return
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
        val failure = launch.failure(error, stopped != null, synchronized(lock) { startCancelled })
        val problems = launch.tearDown(deadline)
        val cause = stopped?.let(Termination.Cause::Requested) ?: Termination.Cause.StartFailed(failure)
        terminate(Termination(cause, problems))
        throw failure
    }

    private fun pluginsResolved(event: RuntimeEvent.PluginsResolved) {
        synchronized(lock) {
            loaded = event.loaded
            emitWhileStarting(event)
        }
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

    private fun becameReady(launch: Launch): Boolean = synchronized(lock) {
        startWork = null
        if (mutableState.value != RuntimeState.STARTING || startCancelled) return false
        running = launch
        handle = RuntimeServices(checkNotNull(launch.container)) { synchronized(lock) { unavailable() } }
        moveTo(RuntimeState.READY, RuntimeEvent.Ready)
        true
    }

    private suspend fun runStop(request: StopRequest, launch: Launch, deadline: TimeMark) {
        val problems = launch.tearDown(deadline)
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
