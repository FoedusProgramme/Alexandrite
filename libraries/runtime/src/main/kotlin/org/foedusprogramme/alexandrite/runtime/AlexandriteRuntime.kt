package org.foedusprogramme.alexandrite.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.StateFlow
import org.foedusprogramme.alexandrite.runtime.lifecycle.JvmSignals
import org.foedusprogramme.alexandrite.runtime.lifecycle.RuntimeRun
import org.foedusprogramme.alexandrite.runtime.lifecycle.runUntilSignal
import org.foedusprogramme.alexandrite.sdk.runtime.HostApi
import org.foedusprogramme.alexandrite.sdk.runtime.StopRequest

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
        /** Starts a runtime of [spec] that stops when [parent] is cancelled, and returns it once it is ready. */
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
