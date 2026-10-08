package org.foedusprogramme.alexandrite.sdk.hook

import dev.drewhamilton.poko.Poko
import org.foedusprogramme.alexandrite.sdk.di.ContributedSpi
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** A subscription to one [HookPoint]. */
@ContributedSpi
public sealed interface Hook {
    public val point: HookPoint<*>

    /** Lower runs first; equal orders run by plugin id, then by the hook's class name. */
    public val order: Int get() = 0

    /** How long one call may run. */
    public val timeout: Duration get() = 10.seconds
}

/** A hook that may replace the payload of an [InterceptorPoint] or stop its chain. */
public interface InterceptorHook<P : Any> : Hook {
    override val point: InterceptorPoint<P>

    public suspend fun intercept(payload: P): HookDecision<P>
}

/** A hook that sees the payload of an [ObserverPoint]. */
public interface ObserverHook<P : Any> : Hook {
    override val point: ObserverPoint<P>
    public val delivery: ObserverDelivery get() = ObserverDelivery.INLINE

    public suspend fun observe(payload: P)
}

/** How an [ObserverHook] gets its payloads. */
public enum class ObserverDelivery {
    /** Awaited by the caller, in order. */
    INLINE,

    /** Queued and run off the caller's path. */
    ASYNC,
}

/** What an [InterceptorHook] does with the payload, logged at DEBUG unless it is [Continue]. */
public sealed interface HookDecision<out P : Any> {
    public data object Continue : HookDecision<Nothing>

    @Poko
    public class Replace<P : Any>(public val payload: P) : HookDecision<P>

    /** Stops the chain and sends [reply] to the user as a notice. */
    @Poko
    public class Abort(public val reply: String? = null) : HookDecision<Nothing>
}
