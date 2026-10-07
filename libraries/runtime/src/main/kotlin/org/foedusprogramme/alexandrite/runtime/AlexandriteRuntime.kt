package org.foedusprogramme.alexandrite.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.completeWith
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.foedusprogramme.alexandrite.runtime.lifecycle.Launch
import org.foedusprogramme.alexandrite.sdk.runtime.HostApi
import org.foedusprogramme.alexandrite.sdk.runtime.RuntimeControl
import org.foedusprogramme.alexandrite.sdk.runtime.StopRequest
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** A started Alexandrite instance that runs in a coroutine of its own until its final event. */
public interface AlexandriteRuntime {
    public val state: StateFlow<RuntimeState>

    /** Resolves the [HostApi] types until a stop is requested. */
    public val services: RuntimeServices

    /** Requests a stop unless one was requested before, and returns at once. */
    public fun stop(request: StopRequest = StopRequest.shutdown("requested by the host"))

    /** Returns how it ended once its final event was delivered. */
    public suspend fun join(): Termination

    public companion object {
        /**
         * Starts a runtime of [spec] as a child of [parent] and returns it once it is ready, or throws why it did
         * not start.
         */
        public suspend fun start(spec: RuntimeSpec, parent: CoroutineScope? = null): AlexandriteRuntime =
            RuntimeRun(spec, RuntimeRun.PARENT_CANCELLED).start(parent)

        /** Runs [block] on a runtime of [spec] once it is ready, then stops it and returns how it ended. */
        public suspend fun run(
            spec: RuntimeSpec,
            block: suspend AlexandriteRuntime.() -> Unit = { awaitCancellation() },
        ): Termination = RuntimeRun(spec, RuntimeRun.RUN_CANCELLED).runBlock(block)

        /** [run] that stops on SIGTERM or SIGINT and halts on a second signal. */
        public suspend fun runUntilSignal(
            spec: RuntimeSpec,
            block: suspend AlexandriteRuntime.() -> Unit = { awaitCancellation() },
        ): Termination = runUntilSignal(spec, block, JvmSignals)
    }
}

/** One life of a runtime, from its start to its final event. */
internal class RuntimeRun(private val spec: RuntimeSpec, private val parentCancelled: StopRequest) :
    AlexandriteRuntime {
    val name = spec.config.name
    private val ending = CompletableDeferred<Ending>()

    /** Null once it is ready, otherwise what ended it before then. */
    private val ready = CompletableDeferred<Throwable?>()
    private val terminated = CompletableDeferred<Termination>()
    private val blockEnded = CompletableDeferred<Unit>()
    private val mutableState = MutableStateFlow(RuntimeState.READY)
    private val events = Channel<RuntimeEvent>(Channel.UNLIMITED)
    private val job = SupervisorJob()
    private val scope = CoroutineScope(
        spec.config.dispatcher + job + CoroutineName("alexandrite $name") + OWNER.asContextElement(this) +
            CoroutineExceptionHandler { _, error -> logger.error("{}: the runtime failed", name, error) },
    )
    private val stages = Launch(spec, Control(), ::emit, scope.coroutineContext)
    private val life = scope.launch(start = CoroutineStart.LAZY) { live() }
    private val handle by lazy { RuntimeServices(checkNotNull(stages.container), ::unavailable) }

    override val state: StateFlow<RuntimeState> = mutableState.asStateFlow()

    override val services: RuntimeServices
        get() {
            unavailable()?.let { throw IllegalStateException(it) }
            return handle
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun stop(request: StopRequest) {
        if (settle(Cause.Requested(request))) return
        val first = when (val cause = ending.getCompleted().cause) {
            is Cause.Requested -> "${cause.request} came first"
            is Cause.StartFailed -> "its start failed"
        }
        logger.info("{}: ignoring the stop request {}: {}", name, request, first)
    }

    override suspend fun join(): Termination {
        check(terminated.isCompleted || OWNER.get() !== this) {
            "Runtime '$name' cannot be joined from its listener, its lifecycle calls or its plugins' coroutines."
        }
        return terminated.await()
    }

    /** Starts the life without a block and returns once it is ready. */
    suspend fun start(parent: CoroutineScope?): AlexandriteRuntime {
        blockEnded.complete(Unit)
        begin(parent)
        val failure = try {
            ready.await()
        } catch (e: CancellationException) {
            settle(Cause.Requested(START_CANCELLED))
            withContext(NonCancellable) { terminated.join() }
            throw e
        }
        if (failure != null) throw failure
        return this
    }

    /** Starts the life in the caller's scope, runs [block] once ready, and returns once the runtime's job completed. */
    suspend fun runBlock(block: suspend AlexandriteRuntime.() -> Unit): Termination = coroutineScope {
        begin(this)
        var ran = false
        val error = try {
            stoppable {
                if (ready.await() == null) {
                    ran = true
                    block()
                }
            }
        } finally {
            blockEnded.complete(Unit)
        }
        val callerCancelled = !isActive
        val settled = settle(
            when {
                callerCancelled -> Cause.Requested(parentCancelled)
                error == null -> Cause.Requested(BLOCK_RETURNED)
                else -> Cause.Requested(StopRequest.failure("the run block failed: $error"))
            },
        )
        withContext(NonCancellable) { terminated.join() }
        val stoppedByRequest = error is CancellationException && !settled
        if (error != null && (callerCancelled || (ran && !stoppedByRequest))) throw error
        terminated.await()
    }

    /** Starts the life, which [parent] waits for and stops when it is cancelled. */
    private fun begin(parent: CoroutineScope?) {
        parent?.launch(Dispatchers.Unconfined + CoroutineName("alexandrite $name"), CoroutineStart.UNDISPATCHED) {
            try {
                job.join()
            } catch (e: CancellationException) {
                settle(Cause.Requested(parentCancelled))
                withContext(NonCancellable) { job.join() }
                throw e
            }
        }
        life.start()
    }

    /** Starts, waits for a stop once ready, tears down and delivers the final event. */
    private suspend fun live() {
        val delivery = scope.launch { deliverEvents() }
        val ended = runCatching { startAndEnd() }
        events.close()
        withContext(NonCancellable) { delivery.join() }
        mutableState.value = RuntimeState.STOPPED
        ready.complete(ended.exceptionOrNull())
        terminated.completeWith(ended)
        job.complete()
        ended.onFailure { if (it !is RuntimeStartException) throw it }
    }

    private suspend fun startAndEnd(): Termination {
        var opened = false
        val error = stoppable {
            stages.start()
            opened = becomeReady()
            if (opened) {
                emit(RuntimeEvent.Ready)
                awaitCancellation()
            }
        }
        when {
            !currentCoroutineContext().isActive -> settle(Cause.Requested(LIFE_CANCELLED))

            opened || error == null -> Unit

            !settle(Cause.StartFailed(stages.failure(error))) && error !is CancellationException ->
                logger.warn("{}: the start failed after a stop was requested", name, error)
        }
        return end(opened)
    }

    /** Runs [work] until it ends or a stop is requested, and returns what it threw. */
    private suspend fun stoppable(work: suspend () -> Unit): Throwable? = try {
        coroutineScope {
            val cut = ending.invokeOnCompletion { cancel() }
            try {
                work()
            } finally {
                cut.dispose()
            }
        }
        null
    } catch (e: Throwable) {
        e
    }

    /** Tears down what the start built once the block has ended, and queues the final event. */
    private suspend fun end(opened: Boolean): Termination = withContext(NonCancellable) {
        val (cause, deadline) = ending.await()
        blockEnded.await()
        if (cause is Cause.Requested) emit(RuntimeEvent.Stopping(cause.request))
        val problems = stages.tearDown(deadline)
        when (cause) {
            is Cause.Requested -> {
                logger.info("{}: stopped", name)
                val termination = Termination(cause.request, problems)
                emit(RuntimeEvent.Stopped(termination))
                if (!opened) throw stages.stopped(cause.request, problems)
                termination
            }

            is Cause.StartFailed -> {
                val error = cause.error.plusTeardown(problems)
                emit(RuntimeEvent.StartFailed(error))
                throw error
            }
        }
    }

    private fun becomeReady(): Boolean = !ending.isCompleted && ready.complete(null)

    /** Settles how the run ends unless it was settled before, and returns whether [cause] settled it. */
    private fun settle(cause: Cause): Boolean {
        if (!ending.complete(Ending(cause, TimeSource.Monotonic.markNow() + spec.config.shutdownGrace))) return false
        if (cause is Cause.Requested) {
            mutableState.value = RuntimeState.STOPPING
            logger.info("{}: stopping: {}", name, cause.request)
        }
        return true
    }

    private fun emit(event: RuntimeEvent) {
        events.trySend(event)
    }

    private suspend fun deliverEvents() {
        for (event in events) {
            try {
                spec.listener.onEvent(event)
            } catch (e: Throwable) {
                if (e is VirtualMachineError) throw e
                logger.warn("{}: listener failed on {}", name, event, e)
            }
        }
    }

    /** Why the run has no services, null while it has them. */
    private fun unavailable(): String? =
        if (ending.isCompleted) "Runtime '$name' has no services: a stop was requested." else null

    private inner class Control : RuntimeControl {
        override fun stop(request: StopRequest) = this@RuntimeRun.stop(request)
    }

    /** Why a run ends. */
    private sealed interface Cause {
        class Requested(val request: StopRequest) : Cause

        class StartFailed(val error: RuntimeStartException) : Cause
    }

    /** How a run ends, and by when its instances must be closed and drained. */
    private data class Ending(val cause: Cause, val deadline: TimeMark)

    companion object {
        val RUN_CANCELLED = StopRequest.shutdown("the run was cancelled")

        val PARENT_CANCELLED = StopRequest.shutdown("the parent scope was cancelled")

        private val logger: Logger = LoggerFactory.getLogger(AlexandriteRuntime::class.java)

        private val START_CANCELLED = StopRequest.shutdown("the start was cancelled")

        private val BLOCK_RETURNED = StopRequest.shutdown("the run block returned")

        private val LIFE_CANCELLED = StopRequest.failure("the runtime's coroutine was cancelled")

        /** The runtime whose coroutine runs on the current thread. */
        private val OWNER = ThreadLocal<RuntimeRun?>()
    }
}
