package org.foedusprogramme.alexandrite.sdk.hook

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** A subscription to one [HookPoint]. */
public sealed interface Hook {
    /** Lower runs first. */
    public val order: Int get() = 0

    /** How long one call may run. */
    public val timeout: Duration get() = 10.seconds
}

/** A hook that may replace the payload of an [InterceptorPoint] or stop its chain. */
public interface InterceptorHook<P : Any> : Hook {
    public val point: InterceptorPoint<P>

    public suspend fun intercept(payload: P): HookDecision<P>
}

/** A hook that sees the payload of an [ObserverPoint]. */
public interface ObserverHook<P : Any> : Hook {
    public val point: ObserverPoint<P>
    public val delivery: Delivery get() = Delivery.INLINE

    public suspend fun observe(payload: P)
}

/** How an [ObserverHook] gets its payloads. */
public enum class Delivery {
    /** Awaited by the caller, in order. */
    INLINE,

    /** Queued and run off the caller's path. */
    ASYNC,
}

/** What an [InterceptorHook] does with the payload. */
public sealed interface HookDecision<out P : Any> {
    public data object Continue : HookDecision<Nothing>

    public data class Replace<P : Any>(val payload: P) : HookDecision<P>

    public data class Abort(val reason: String? = null) : HookDecision<Nothing>
}
