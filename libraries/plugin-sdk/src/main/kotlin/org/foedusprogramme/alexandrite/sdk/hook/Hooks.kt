package org.foedusprogramme.alexandrite.sdk.hook

import java.util.Objects

/** Fires hook points. */
public interface Hooks {
    /** Runs the interceptors of [point] on [payload] in order. */
    public suspend fun <P : Any> fire(point: InterceptorPoint<P>, payload: P): Interception<P>

    /** Runs the INLINE observers of [point] and queues [payload] for the ASYNC ones. */
    public suspend fun <P : Any> fire(point: ObserverPoint<P>, payload: P)
}

/** How an interceptor chain ended. */
public sealed interface Interception<out P : Any> {
    public class Proceed<P : Any>(public val payload: P) : Interception<P> {
        override fun equals(other: Any?): Boolean = other is Proceed<*> && payload == other.payload

        override fun hashCode(): Int = payload.hashCode()

        override fun toString(): String = "Proceed(payload=$payload)"
    }

    public class Aborted(
        /** The reply of the hook's [HookDecision.Abort], null when it gave none or failed. */
        public val reply: String?,
        /** Class name of the hook that stopped the chain. */
        public val hook: String,
        /** How the hook failed, null when it decided to abort. */
        public val failure: HookFailure?,
    ) : Interception<Nothing> {
        override fun equals(other: Any?): Boolean =
            other is Aborted && reply == other.reply && hook == other.hook && failure == other.failure

        override fun hashCode(): Int = Objects.hash(reply, hook, failure)

        override fun toString(): String = "Aborted(reply=$reply, hook=$hook, failure=$failure)"
    }
}
