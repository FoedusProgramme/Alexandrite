package org.foedusprogramme.alexandrite.sdk.hook

/** A stage of the agent's work that hooks subscribe to. */
public sealed class HookPoint<P : Any>(
    /** Unique across the runtime. */
    public val id: String,
) {
    override fun toString(): String = id
}

/** A point whose hooks run as a chain that may replace the payload or stop. */
public class InterceptorPoint<P : Any>(
    id: String,
    /** What an interceptor may decide besides [HookDecision.Continue]. */
    public val effects: Set<HookEffect>,
    public val onFailure: FailurePolicy,
) : HookPoint<P>(id)

/** A point whose hooks only see the payload. */
public class ObserverPoint<P : Any>(id: String) : HookPoint<P>(id)

/** An effect an interceptor's decision may have. */
public enum class HookEffect {
    /** Replacing the payload. */
    REPLACE,

    /** Stopping the chain. */
    ABORT,
}

/** What a failing interceptor does to its chain. */
public enum class FailurePolicy {
    /** Skip the hook, keeping the payload. */
    FAIL_OPEN,

    /** Stop the chain. */
    FAIL_CLOSED,
}
