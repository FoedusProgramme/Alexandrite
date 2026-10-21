package org.unitedsoftware.alexandrite.internal.process

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Ends [process] and the processes it started: asks its descendants and then the process itself to stop, and kills
 * those still running after [grace].
 *
 * Descendants are found before the process ends, as they no longer count as its descendants afterwards.
 */
public suspend fun destroyTree(process: Process, grace: Duration) {
    val descendants = process.descendants().toList()
    descendants.forEach { stop(it, forcibly = false) }
    process.destroy()
    withTimeoutOrNull(grace) {
        while (process.isAlive || descendants.any { it.isAlive }) delay(POLL)
    }
    val late = if (process.isAlive) process.descendants().toList() else emptyList()
    (descendants + late).filter { it.isAlive }.forEach { stop(it, forcibly = true) }
    if (process.isAlive) process.destroyForcibly()
}

/** Asks [handles] to stop, and kills those still running after [grace]. */
public suspend fun stopAll(handles: List<ProcessHandle>, grace: Duration) {
    val alive = handles.filter { it.isAlive }
    alive.forEach { stop(it, forcibly = false) }
    withTimeoutOrNull(grace) { while (alive.any { it.isAlive }) delay(POLL) }
    alive.filter { it.isAlive }.forEach { stop(it, forcibly = true) }
}

private fun stop(handle: ProcessHandle, forcibly: Boolean) {
    try {
        if (forcibly) handle.destroyForcibly() else handle.destroy()
    } catch (e: IllegalStateException) {
        // The handle is this process.
    } catch (e: SecurityException) {
        // Not allowed to signal it.
    }
}

private val POLL = 10.milliseconds
