package org.foedusprogramme.alexandrite.runtime

import org.foedusprogramme.alexandrite.sdk.runtime.StopKind
import org.foedusprogramme.alexandrite.sdk.runtime.StopRequest
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import sun.misc.Signal
import sun.misc.SignalHandler
import java.util.concurrent.atomic.AtomicBoolean

private val logger: Logger = LoggerFactory.getLogger(AlexandriteRuntime::class.java)

/** Starts the runtime, stops it on SIGTERM or SIGINT, halts on a second signal and returns its [Termination]. */
public suspend fun AlexandriteRuntime.runUntilSignal(): Termination = runUntilSignal(JvmSignals)

internal suspend fun AlexandriteRuntime.runUntilSignal(signals: Signals): Termination {
    val received = AtomicBoolean()
    val trap = signals.trap { signal ->
        if (received.compareAndSet(false, true)) {
            requestStop(StopRequest(StopKind.SHUTDOWN, "received SIG${signal.name}"))
        } else {
            val status = 128 + signal.number
            logger.warn("{}: received SIG{} while stopping, halting with status {}", name, signal.name, status)
            signals.halt(status)
        }
    } ?: signals.onShutdown {
        received.set(true)
        requestStop(StopRequest(StopKind.SHUTDOWN, "the JVM is shutting down"))
        joinTermination()
    }
    try {
        try {
            start()
        } catch (ignored: RuntimeStartException) {
        } catch (e: IllegalStateException) {
            if (!received.get()) throw e
        }
        return awaitTermination()
    } finally {
        trap.close()
    }
}

internal class HostSignal(val name: String, val number: Int)

/** Where [runUntilSignal] receives signals. */
internal interface Signals {
    /** Calls [handler] on each SIGTERM and SIGINT until the result is closed, or returns null where it cannot. */
    fun trap(handler: (HostSignal) -> Unit): AutoCloseable?

    /** Runs [hook] when the JVM shuts down, until the result is closed. */
    fun onShutdown(hook: () -> Unit): AutoCloseable

    fun halt(status: Int)
}

private object JvmSignals : Signals {
    private val NAMES = listOf("TERM", "INT")

    override fun trap(handler: (HostSignal) -> Unit): AutoCloseable? = try {
        SunMiscSignals.trap(NAMES, handler)
    } catch (e: LinkageError) {
        logger.warn("Cannot handle signals, stopping in a shutdown hook instead: {}", e.toString())
        null
    } catch (e: IllegalArgumentException) {
        logger.warn("Cannot handle signals, stopping in a shutdown hook instead: {}", e.toString())
        null
    }

    override fun onShutdown(hook: () -> Unit): AutoCloseable {
        val thread = Thread(hook, "alexandrite-shutdown")
        Runtime.getRuntime().addShutdownHook(thread)
        return AutoCloseable {
            try {
                Runtime.getRuntime().removeShutdownHook(thread)
            } catch (ignored: IllegalStateException) {
            }
        }
    }

    override fun halt(status: Int) {
        Runtime.getRuntime().halt(status)
    }
}

private object SunMiscSignals {
    fun trap(names: List<String>, handler: (HostSignal) -> Unit): AutoCloseable {
        val previous = mutableListOf<Pair<Signal, SignalHandler>>()
        try {
            for (name in names) {
                val signal = Signal(name)
                previous += signal to Signal.handle(signal) { handler(HostSignal(it.name, it.number)) }
            }
        } catch (e: IllegalArgumentException) {
            restore(previous)
            throw e
        }
        return AutoCloseable { restore(previous) }
    }

    private fun restore(previous: List<Pair<Signal, SignalHandler>>) {
        for ((signal, handler) in previous.asReversed()) Signal.handle(signal, handler)
    }
}
