package org.foedusprogramme.alexandrite.sdk.hook

/** Fires hook points. */
public interface Hooks {
    public suspend fun <P : Any> intercept(point: InterceptorPoint<P>, payload: P): Interception<P>

    /** Runs the INLINE observers of [point] and queues [payload] for the ASYNC ones. */
    public suspend fun <P : Any> observe(point: ObserverPoint<P>, payload: P)
}

/** How an interceptor chain ended. */
public sealed interface Interception<out P : Any> {
    public data class Proceed<P : Any>(val payload: P) : Interception<P>

    public data class Aborted(
        /** The hook's [HookDecision.Abort.reason], or what failed. */
        val reason: String?,
        /** Class name of the hook that stopped the chain. */
        val hook: String,
    ) : Interception<Nothing>
}
