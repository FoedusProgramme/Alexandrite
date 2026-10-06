package org.foedusprogramme.alexandrite.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.foedusprogramme.alexandrite.runtime.Termination.Cause
import org.foedusprogramme.alexandrite.runtime.lifecycle.Launch
import org.foedusprogramme.alexandrite.runtime.plugin.LoadedPlugin
import org.foedusprogramme.alexandrite.sdk.runtime.HostApi
import org.foedusprogramme.alexandrite.sdk.runtime.RuntimeControl
import org.foedusprogramme.alexandrite.sdk.runtime.StopKind
import org.foedusprogramme.alexandrite.sdk.runtime.StopRequest
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** A running Alexandrite instance. */
public interface AlexandriteRuntime {
    /** Resolves the [HostApi] types until a stop is requested. */
    public val services: RuntimeServices

    /** The plugins it loaded. */
    public val plugins: List<LoadedPlugin>

    /** Requests a stop unless one was requested before, and returns at once. */
    public fun requestStop(request: StopRequest = StopRequest(StopKind.SHUTDOWN, "requested by the host"))

    public companion object {
        /** Starts a runtime of [spec], runs [block] once it is ready, stops it and returns how it ended. */
        public suspend fun run(
            spec: RuntimeSpec,
            block: suspend AlexandriteRuntime.() -> Unit = { awaitCancellation() },
        ): Termination = RuntimeRun(spec).live(block)

        /** [run] that stops on SIGTERM or SIGINT and halts on a second signal. */
        public suspend fun runUntilSignal(
            spec: RuntimeSpec,
            block: suspend AlexandriteRuntime.() -> Unit = { awaitCancellation() },
        ): Termination = runUntilSignal(spec, block, JvmSignals)
    }
}

/** One life of a runtime, from its start to its final event. */
internal class RuntimeRun(private val spec: RuntimeSpec) : AlexandriteRuntime {
    val name = spec.config.name
    private val ending = CompletableDeferred<Ending>()
    private val events = Channel<RuntimeEvent>(Channel.UNLIMITED)
    private val launch = Launch(spec, Control(), ::emit)
    private val handle by lazy { RuntimeServices(checkNotNull(launch.container), ::unavailable) }

    override val services: RuntimeServices
        get() {
            unavailable()?.let { throw IllegalStateException(it) }
            return handle
        }

    override val plugins: List<LoadedPlugin> get() = launch.plugins

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun requestStop(request: StopRequest) {
        if (settle(Cause.Requested(request))) return
        val first = when (val cause = ending.getCompleted().cause) {
            is Cause.Requested -> "${cause.request} came first"
            is Cause.StartFailed -> "its start failed"
        }
        logger.info("{}: ignoring the stop request {}: {}", name, request, first)
    }

    /** Starts, runs [block] once ready, stops, and returns once the final event was delivered. */
    suspend fun live(block: suspend AlexandriteRuntime.() -> Unit): Termination = coroutineScope {
        val delivery = deliverEvents()
        try {
            var ready = false
            val error = stoppable {
                launch.start()
                ready = true
                emit(RuntimeEvent.Ready)
                block()
            }
            val cancelled = !isActive
            val settled = settle(
                when {
                    cancelled -> Cause.Requested(CANCELLED)
                    error == null -> Cause.Requested(BLOCK_RETURNED)
                    !ready -> Cause.StartFailed(launch.failure(error))
                    else -> Cause.Requested(StopRequest(StopKind.FAILURE, "the run block failed: $error"))
                },
            )
            if (!settled && !ready && error != null && error !is CancellationException) {
                logger.warn("{}: the start failed after a stop was requested", name, error)
            }
            val termination = end()
            val stoppedByRequest = error is CancellationException && !settled
            if (error != null && (cancelled || (ready && !stoppedByRequest))) throw error
            termination
        } finally {
            events.close()
            withContext(NonCancellable) { delivery.join() }
        }
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

    /** Tears down what the start built and queues the final event. */
    private suspend fun end(): Termination = withContext(NonCancellable) {
        val (cause, deadline) = ending.await()
        if (cause is Cause.Requested) emit(RuntimeEvent.Stopping(cause.request))
        val termination = Termination(cause, launch.tearDown(deadline))
        when (cause) {
            is Cause.Requested -> {
                logger.info("{}: stopped", name)
                emit(RuntimeEvent.Stopped(termination))
            }

            is Cause.StartFailed -> emit(RuntimeEvent.StartFailed(cause.error))
        }
        termination
    }

    /** Settles how the run ends unless it was settled before, and returns whether [cause] settled it. */
    private fun settle(cause: Cause): Boolean {
        val settled = ending.complete(Ending(cause, TimeSource.Monotonic.markNow() + spec.config.shutdownGrace))
        if (settled && cause is Cause.Requested) logger.info("{}: stopping: {}", name, cause.request)
        return settled
    }

    private fun emit(event: RuntimeEvent) {
        events.trySend(event)
    }

    private fun CoroutineScope.deliverEvents(): Job = launch(start = CoroutineStart.UNDISPATCHED) {
        withContext(NonCancellable) {
            for (event in events) {
                try {
                    spec.listener.onEvent(event)
                } catch (e: Throwable) {
                    if (e is VirtualMachineError) throw e
                    logger.warn("{}: listener failed on {}", name, event, e)
                }
            }
        }
    }

    /** Why the run has no services, null while it has them. */
    private fun unavailable(): String? =
        if (ending.isCompleted) "Runtime '$name' has no services: a stop was requested." else null

    private inner class Control : RuntimeControl {
        override fun requestStop(request: StopRequest) = this@RuntimeRun.requestStop(request)
    }

    /** How a run ends, and by when its instances must be closed and drained. */
    private data class Ending(val cause: Cause, val deadline: TimeMark)

    private companion object {
        val logger: Logger = LoggerFactory.getLogger(AlexandriteRuntime::class.java)

        val BLOCK_RETURNED = StopRequest(StopKind.SHUTDOWN, "the run block returned")

        val CANCELLED = StopRequest(StopKind.SHUTDOWN, "the run was cancelled")
    }
}
