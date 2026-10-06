package org.foedusprogramme.alexandrite.runtime

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

/** Runs a runtime in the data directory of [args] until a signal stops it. */
fun main(args: Array<String>) {
    val hanging = args.getOrNull(1) == "hang"
    val worker = worker("worker", "core", onDrain = { if (hanging) awaitCancellation() })
    val index = TestIndex("core", bindings = listOf(worker))
    val listener = RuntimeListener { event ->
        when (event) {
            RuntimeEvent.Ready -> println("ready")
            is RuntimeEvent.Stopping -> println("stopping: ${event.request.reason}")
            else -> return@RuntimeListener
        }
        System.out.flush()
    }
    val runtime = runtime(explicit(index), Path.of(args[0]), listener = listener, shutdownGrace = 60.seconds)
    val termination = runBlocking { runtime.runUntilSignal() }
    println("stopped: ${(termination.cause as Termination.Cause.Requested).request.reason}")
}
