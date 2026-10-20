package org.unitedsoftware.alexandrite.runtime

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.unitedsoftware.alexandrite.runtime.lifecycle.HostSignal
import org.unitedsoftware.alexandrite.runtime.lifecycle.JvmSignals
import org.unitedsoftware.alexandrite.runtime.lifecycle.Signals
import org.unitedsoftware.alexandrite.runtime.lifecycle.runUntilSignal
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

private object Untrappable : Signals by JvmSignals {
    override fun trap(handler: (HostSignal) -> Unit): AutoCloseable? = null
}

/** Runs a runtime in the data directory of [args] until a signal stops it. */
fun main(args: Array<String>) {
    val mode = args.getOrNull(1)
    val worker = worker("worker", "core", onDrain = { if (mode == "hang") awaitCancellation() })
    val index = TestIndex("core", bindings = listOf(worker))
    val listener = RuntimeListener { event ->
        when (event) {
            RuntimeEvent.Ready -> println("ready")
            is RuntimeEvent.Stopping -> println("stopping: ${event.request.reason}")
            is RuntimeEvent.Stopped -> println("terminated: ${event.termination.request.reason}")
            else -> return@RuntimeListener
        }
        System.out.flush()
    }
    val spec = spec(explicit(index), Path.of(args[0]), listener = listener, shutdownGrace = 60.seconds)
    val termination = runBlocking {
        if (mode == "hook") {
            runUntilSignal(spec, { awaitCancellation() }, Untrappable)
        } else {
            AlexandriteRuntime.runUntilSignal(spec)
        }
    }
    println("stopped: ${termination.request.reason}")
}
