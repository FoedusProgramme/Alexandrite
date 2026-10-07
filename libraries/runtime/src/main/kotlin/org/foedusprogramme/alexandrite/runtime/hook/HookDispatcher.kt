package org.foedusprogramme.alexandrite.runtime.hook

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.foedusprogramme.alexandrite.sdk.di.Lifecycle
import org.foedusprogramme.alexandrite.sdk.hook.Delivery
import org.foedusprogramme.alexandrite.sdk.hook.FailurePolicy
import org.foedusprogramme.alexandrite.sdk.hook.Hook
import org.foedusprogramme.alexandrite.sdk.hook.HookDecision
import org.foedusprogramme.alexandrite.sdk.hook.HookEffect
import org.foedusprogramme.alexandrite.sdk.hook.HookFailure
import org.foedusprogramme.alexandrite.sdk.hook.HookPoint
import org.foedusprogramme.alexandrite.sdk.hook.Hooks
import org.foedusprogramme.alexandrite.sdk.hook.Interception
import org.foedusprogramme.alexandrite.sdk.hook.InterceptorHook
import org.foedusprogramme.alexandrite.sdk.hook.InterceptorPoint
import org.foedusprogramme.alexandrite.sdk.hook.ObserverHook
import org.foedusprogramme.alexandrite.sdk.hook.ObserverPoint
import kotlin.coroutines.CoroutineContext

/** Each ASYNC observer has its own queue of [asyncCapacity] events, worked off in a child scope of [asyncContext]. */
internal class HookDispatcher(
    hooks: List<Hook>,
    private val listener: HookFailureListener = HookFailureListener.NONE,
    private val asyncCapacity: Int = 256,
    asyncContext: CoroutineContext = Dispatchers.Default,
) : Hooks,
    Lifecycle {
    private val scope = CoroutineScope(asyncContext + SupervisorJob(asyncContext[Job]))
    private val points: Map<String, HookPoint<*>>
    private val interceptors: Map<String, List<InterceptorHook<*>>>
    private val inlineObservers: Map<String, List<ObserverHook<*>>>
    private val queues: Map<String, List<Queue>>

    init {
        require(asyncCapacity > 0) { "asyncCapacity must be positive, was $asyncCapacity." }
        points = subscribedPoints(hooks)
        val sorted = hooks.sortedBy { it.order }
        interceptors = sorted.filterIsInstance<InterceptorHook<*>>().groupBy { it.point.id }
        val observers = sorted.filterIsInstance<ObserverHook<*>>().groupBy { it.delivery }
        inlineObservers = observers[Delivery.INLINE].orEmpty().groupBy { it.point.id }
        queues = observers[Delivery.ASYNC].orEmpty().map { Queue(it) }.groupBy { it.hook.point.id }
    }

    override suspend fun <P : Any> fire(point: InterceptorPoint<P>, payload: P): Interception<P> {
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
                            is HookDecision.Abort -> return Interception.Aborted(decision.reply, hook.name, null)
                        }
                        continue
                    }
                }
            }
            report(hook, point, failure)
            if (point.onFailure == FailurePolicy.FAIL_CLOSED) return Interception.Aborted(null, hook.name, failure)
        }
        return Interception.Proceed(current)
    }

    override suspend fun <P : Any> fire(point: ObserverPoint<P>, payload: P) {
        queues.at<Queue>(point).forEach { it.offer(payload) }
        for (hook in inlineObservers.at<ObserverHook<P>>(point)) notify(hook, payload)
    }

    /** Stops taking ASYNC events and waits until the queued ones are delivered. */
    override suspend fun onDrain() {
        val all = queues.values.flatten()
        all.forEach { it.events.close() }
        all.map { it.worker }.joinAll()
    }

    /** Stops ASYNC delivery. */
    override fun onDestroy() {
        queues.values.flatten().forEach { it.events.close() }
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
        } catch (e: Throwable) {
            if (e is VirtualMachineError) throw e
        }
    }

    /** The hooks of [point], which must be the object they subscribe to. */
    @Suppress("UNCHECKED_CAST")
    private fun <H> Map<String, List<*>>.at(point: HookPoint<*>): List<H> {
        val subscribed = points[point.id] ?: return emptyList()
        require(subscribed === point) {
            "Cannot fire hook point '${point.id}': its hooks subscribe to another point object with this id."
        }
        return get(point.id).orEmpty() as List<H>
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

/** The point object of each subscribed id. */
private fun subscribedPoints(hooks: List<Hook>): Map<String, HookPoint<*>> {
    val first = HashMap<String, Hook>()
    for (hook in hooks) {
        val other = first.getOrPut(hook.point.id) { hook }
        require(other.point === hook.point) {
            "Two hook points share the id '${hook.point.id}': ${other.name} uses one, ${hook.name} the other. " +
                "Give each point a unique id."
        }
    }
    return first.mapValues { it.value.point }
}

private val Hook.name: String get() = this::class.java.name

private fun InterceptorPoint<*>.allows(decision: HookDecision<*>): Boolean = when (decision) {
    HookDecision.Continue -> true
    is HookDecision.Replace -> HookEffect.REPLACE in effects
    is HookDecision.Abort -> HookEffect.ABORT in effects
}
