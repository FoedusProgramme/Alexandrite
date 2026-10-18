package org.foedusprogramme.alexandrite.tools.monitor

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.foedusprogramme.alexandrite.sdk.tool.ToolContext
import org.foedusprogramme.alexandrite.sdk.tool.ToolResult
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.TimeSource

internal class MonitorOptions(val filter: String, val batch: Duration, val interval: Duration, val lifetime: Duration)

internal class MonitorOutcome<T>(val value: T, val remaining: List<String>, val dropped: Long)

internal suspend fun <T> monitoring(
    options: MonitorOptions,
    context: ToolContext,
    run: suspend (((String) -> Unit)?) -> T,
): MonitorOutcome<T> = coroutineScope {
    val job = checkNotNull(context.background()) { "A monitor runs only as a background job." }
    val lines = Channel<String>(MAX_PENDING_LINES)
    val pending = mutableListOf<String>()
    val dropped = AtomicLong()
    val consumer = launch {
        var lastWake: TimeSource.Monotonic.ValueTimeMark? = null

        // Lines the agent has not taken yet stay pending and go out with the next batch.
        suspend fun flush() {
            if (pending.isEmpty()) return
            lastWake = TimeSource.Monotonic.markNow()
            if (job.update(ToolResult("Monitor matched output:\n" + pending.joinToString("\n")))) pending.clear()
        }
        while (true) {
            val first = lines.receiveCatching().getOrNull() ?: break
            if (pending.size < MAX_BATCH_LINES) pending += first else dropped.incrementAndGet()
            val wait = maxOf(options.batch, options.interval - (lastWake?.elapsedNow() ?: options.interval))
            val deadline = TimeSource.Monotonic.markNow() + wait
            var closed = false
            while (true) {
                val remaining = -deadline.elapsedNow()
                if (!remaining.isPositive()) break
                val next = withTimeoutOrNull(remaining) { lines.receiveCatching() } ?: break
                val line = next.getOrNull()
                if (line == null) {
                    closed = true
                    break
                }
                if (pending.size < MAX_BATCH_LINES) pending += line else dropped.incrementAndGet()
            }
            if (lastWake == null || lastWake.elapsedNow() >= options.interval) flush()
            if (closed) break
        }
        if (lastWake == null || lastWake.elapsedNow() >= options.interval) flush()
    }
    val value = try {
        run { line ->
            if (options.filter in line && lines.trySend(line).isFailure) dropped.incrementAndGet()
        }
    } finally {
        lines.close()
        consumer.join()
    }
    MonitorOutcome(value, pending.toList(), dropped.get())
}

private const val MAX_PENDING_LINES = 256
private const val MAX_BATCH_LINES = 128
