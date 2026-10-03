package org.foedusprogramme.alexandrite.sdk.di

/** Thrown when a [Container] cannot be built or used. */
public class DiException(message: String, public val problems: List<Problem>, cause: Throwable? = null) :
    RuntimeException(message, cause) {
    internal constructor(problem: Problem, cause: Throwable? = null) : this(problem.message, listOf(problem), cause)
}

/** One reason a [Container] cannot be built or used. */
public data class Problem(
    val kind: ProblemKind,
    val message: String,
    /** The module at fault, null when no single module is. */
    val module: String?,
    /** The key at issue, null when there is none. */
    val key: Key<*>?,
)

/** What a [Problem] is about. */
public enum class ProblemKind {
    /** A key nothing binds. */
    MISSING,

    /** A key with several single bindings. */
    AMBIGUOUS,

    /** A key with both a single binding and multibinding contributions. */
    CONFLICTING,

    /** A single binding used as contributions, or contributions used as a single binding. */
    WRONG_KIND,

    /** A channel-instance-scoped binding used outside a channel instance container. */
    SCOPE,

    /** A dependency cycle. */
    CYCLE,

    /** Two indexes of one module. */
    DUPLICATE_MODULE,

    /** A binding returned by the index of another module. */
    MODULE_MISMATCH,

    /** A channel-instance-scoped binding of a module the channel instance container was not created for. */
    UNLISTED_MODULE,

    /** A channel instance container created for a module that is not loaded. */
    UNKNOWN_MODULE,

    /** A binding that threw while creating its instance. */
    CREATION_FAILED,

    /** A closed container. */
    CLOSED,

    /** A container started twice. */
    STARTED_TWICE,

    /** A channel instance container created inside another. */
    NESTED_CHILD,

    /** A key a binding resolved without declaring it. */
    UNDECLARED,

    /** A binding resolved during its own creation. */
    REENTRANT,
}
