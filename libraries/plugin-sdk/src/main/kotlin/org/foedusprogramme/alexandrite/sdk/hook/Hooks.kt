package org.foedusprogramme.alexandrite.sdk.hook

import dev.drewhamilton.poko.Poko
import org.foedusprogramme.alexandrite.sdk.InternalAlexandriteApi

/** Fires hook points. */
@SubclassOptInRequired(InternalAlexandriteApi::class)
public interface Hooks {
    /** Runs the interceptors of [point] on [payload] in order. */
    public suspend fun <P : Any> fire(point: InterceptorPoint<P>, payload: P): Interception<P>

    /** Runs the INLINE observers of [point] and queues [payload] for the ASYNC ones. */
    public suspend fun <P : Any> fire(point: ObserverPoint<P>, payload: P)
}

/** How an interceptor chain ended. */
@SubclassOptInRequired(InternalAlexandriteApi::class)
public interface Interception<out P : Any> {
    @OptIn(InternalAlexandriteApi::class)
    @Poko
    public class Proceed<P : Any>(public val payload: P) : Interception<P>

    @OptIn(InternalAlexandriteApi::class)
    @Poko
    public class Aborted(
        /** The reply of the hook's [HookDecision.Abort], null when it gave none or failed. */
        public val reply: String?,
        /** Class name of the hook that stopped the chain. */
        public val hook: String,
        /** How the hook failed, null when it decided to abort. */
        public val failure: HookFailure?,
    ) : Interception<Nothing>
}
