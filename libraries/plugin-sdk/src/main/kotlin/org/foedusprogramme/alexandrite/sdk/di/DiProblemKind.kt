package org.foedusprogramme.alexandrite.sdk.di

import org.foedusprogramme.alexandrite.sdk.problem.ProblemKind

/** What a problem of a [Container] is about. */
public enum class DiProblemKind : ProblemKind {
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

    /** A binding added to a channel instance container that is not channel-instance-scoped. */
    EXTRA_SCOPE,

    /** A dependency cycle. */
    CYCLE,

    /** Two binding lists of one plugin. */
    DUPLICATE_PLUGIN,

    /** A binding listed under another plugin. */
    PLUGIN_MISMATCH,

    /** A channel-instance-scoped binding of a plugin the channel instance container is not for. */
    UNLISTED_PLUGIN,

    /** A channel instance container for a plugin that is not loaded. */
    UNKNOWN_PLUGIN,

    /** A binding that threw while creating its instance. */
    CREATION_FAILED,

    /** A closed container. */
    CLOSED,

    /** A container started twice. */
    STARTED_TWICE,

    /** A container opened twice. */
    OPENED_TWICE,

    /** A channel instance container created inside another. */
    NESTED_CHILD,

    /** A key a binding resolved without declaring it. */
    UNDECLARED,

    /** A binding resolved during its own creation. */
    REENTRANT,
    ;

    override val id: String get() = "di.${name.lowercase()}"
}
