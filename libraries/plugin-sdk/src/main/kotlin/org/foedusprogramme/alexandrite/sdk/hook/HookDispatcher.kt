package org.foedusprogramme.alexandrite.sdk.hook

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The [Hooks] that runs contributed hooks.
 * Each ASYNC observer has its own queue of [asyncCapacity] events, worked off on [asyncDispatcher].
 */
public class HookDispatcher(
    hooks: List<Hook>,
    private val listener: HookFailureListener = HookFailureListener.NONE,
    private val asyncCapacity: Int = 256,
    private val closeGrace: Duration = 5.seconds,
    asyncDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : Hooks,
    AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + asyncDispatcher)
    private val closed = AtomicBoolean()
    private val interceptors: Map<HookPoint<*>, List<InterceptorHook<*>>>
    private val inlineObservers: Map<HookPoint<*>, List<ObserverHook<*>>>
    private val queues: Map<HookPoint<*>, List<Queue>>

    init {
        require(asyncCapacity > 0) { "asyncCapacity must be positive, was $asyncCapacity." }
        requireUniqueIds(hooks)
        val sorted = hooks.sortedBy { it.order }
        interceptors = sorted.filterIsInstance<InterceptorHook<*>>().groupBy { it.point }
        val observers = sorted.filterIsInstance<ObserverHook<*>>().groupBy { it.delivery }
        inlineObservers = observers[Delivery.INLINE].orEmpty().groupBy { it.point }
        queues = observers[Delivery.ASYNC].orEmpty().map { Queue(it) }.groupBy { it.hook.point }
    }

    override suspend fun <P : Any> intercept(point: InterceptorPoint<P>, payload: P): Interception<P> {
        var current = payload
        for (hook in interceptors.at<InterceptorHook<P>>(point)) {
            val failure = when (val outcome = call(hook) { hook.intercept(current) }) {
                is Outcome.Failed -> outcome.failure

                is Outcome.Returned -> {
                    val decision = outcome.value
                    if (!point.allows(decision)) {
                        HookFailure.Disallowed(decision)
                    } else {
                        when (decision) {
                            HookDecision.Continue -> Unit
                            is HookDecision.Replace -> current = decision.payload
                            is HookDecision.Abort -> return Interception.Aborted(decision.reason, hook.name)
                        }
                        continue
                    }
                }
            }
            report(hook, point, failure)
            if (point.onFailure == FailurePolicy.ABORT) return Interception.Aborted(failure.describe(point), hook.name)
        }
        return Interception.Proceed(current)
    }

    override suspend fun <P : Any> observe(point: ObserverPoint<P>, payload: P) {
        queues[point]?.forEach { it.offer(payload) }
        for (hook in inlineObservers.at<ObserverHook<P>>(point)) notify(hook, payload)
    }

    /** Stops ASYNC delivery after draining for up to [closeGrace]. Interceptors and INLINE observers keep working. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val all = queues.values.flatten()
        all.forEach { it.events.close() }
        runBlocking { withTimeoutOrNull(closeGrace) { all.map { it.worker }.joinAll() } }
        scope.cancel()
    }

    private suspend fun <P : Any> notify(hook: ObserverHook<P>, payload: P) {
        val outcome = call(hook) { hook.observe(payload) }
        if (outcome is Outcome.Failed) report(hook, hook.point, outcome.failure)
    }

    /** Runs [block] within the hook's timeout, rethrowing only the caller's cancellation. */
    private suspend fun <T : Any> call(hook: Hook, block: suspend () -> T): Outcome<T> {
        val timeout = hook.timeout
        return try {
            withTimeoutOrNull(timeout) { block() }?.let { Outcome.Returned(it) }
                ?: Outcome.Failed(HookFailure.TimedOut(timeout))
        } catch (e: Throwable) {
            if (e is VirtualMachineError) throw e
            currentCoroutineContext().ensureActive()
            Outcome.Failed(HookFailure.Threw(e))
        }
    }

    private fun report(hook: Hook, point: HookPoint<*>, failure: HookFailure) {
        try {
            listener.onFailure(hook, point, failure)
        } catch (_: Exception) {
        }
    }

    private inner class Queue(hook: ObserverHook<*>) {
        @Suppress("UNCHECKED_CAST")
        val hook = hook as ObserverHook<Any>
        val events = Channel<Any>(asyncCapacity)
        val worker = scope.launch { for (payload in events) notify(this@Queue.hook, payload) }

        fun offer(payload: Any) {
            val sent = events.trySend(payload)
            if (sent.isFailure && !sent.isClosed) report(hook, hook.point, HookFailure.Dropped)
        }
    }

    private sealed interface Outcome<out T> {
        class Returned<T>(val value: T) : Outcome<T>

        class Failed(val failure: HookFailure) : Outcome<Nothing>
    }
}

private fun requireUniqueIds(hooks: List<Hook>) {
    val first = HashMap<String, Hook>()
    for (hook in hooks) {
        val other = first.getOrPut(hook.point.id) { hook }
        require(other.point === hook.point) {
            "Two hook points share the id '${hook.point.id}': ${other.name} uses one, ${hook.name} the other. " +
                "Give each point a unique id."
        }
    }
}

private val Hook.point: HookPoint<*>
    get() = when (this) {
        is InterceptorHook<*> -> point
        is ObserverHook<*> -> point
    }

private val Hook.name: String get() = this::class.java.name

private fun InterceptorPoint<*>.allows(decision: HookDecision<*>): Boolean = when (decision) {
    HookDecision.Continue -> true
    is HookDecision.Replace -> HookEffect.REPLACE in effects
    is HookDecision.Abort -> HookEffect.ABORT in effects
}

private fun HookFailure.describe(point: HookPoint<*>): String = when (this) {
    is HookFailure.Threw -> "Hook threw $error"
    is HookFailure.TimedOut -> "Hook timed out after $timeout"
    is HookFailure.Disallowed -> "Hook returned ${decision::class.simpleName}, which point '$point' does not allow"
    HookFailure.Dropped -> "Hook queue was full"
}

@Suppress("UNCHECKED_CAST")
private fun <H> Map<HookPoint<*>, List<*>>.at(point: HookPoint<*>): List<H> = get(point).orEmpty() as List<H>
