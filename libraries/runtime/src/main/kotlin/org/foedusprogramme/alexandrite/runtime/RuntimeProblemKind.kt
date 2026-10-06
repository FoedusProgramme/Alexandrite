package org.foedusprogramme.alexandrite.runtime

import org.foedusprogramme.alexandrite.sdk.problem.ProblemKind

/** What a problem of the runtime is about. */
public enum class RuntimeProblemKind : ProblemKind {
    /** A plugin id outside the id grammar. */
    MALFORMED_ID,

    /** A plugin that is not built in but has a reserved id. */
    RESERVED_ID,

    /** A plugin that is not built in and reads a config root other than its own below `plugins`. */
    WRONG_ROOT,

    /** Several indexes of the plugin set with one plugin id. */
    DUPLICATE_PLUGIN,

    /** An index class listed by several service files. */
    DUPLICATE_INDEX,

    /** A built-in index class that cannot be loaded. */
    BROKEN_INDEX,

    /** A built-in index whose id or config root differs from its row in the built-in list. */
    MISMATCHED_INDEX,

    /** Config that cannot be read or decoded. */
    INVALID_CONFIG,

    /** Config that belongs to no plugin. */
    UNKNOWN_CONFIG,

    /** Config roots of two plugins that are equal or nested. */
    OVERLAPPING_ROOTS,

    /** An enabled plugin that requires a plugin that is missing or disabled. */
    MISSING_REQUIREMENT,

    /** A data directory another runtime holds. */
    DATA_DIR_LOCKED,

    /** An instance that threw while closing. */
    CLOSE_FAILED,

    /** An instance still closing at the shutdown deadline. */
    CLOSE_TIMED_OUT,

    /** An instance left open because the shutdown deadline had passed. */
    CLOSE_NOT_CALLED,

    /** A drain that threw. */
    DRAIN_FAILED,

    /** A drain still running at the shutdown deadline. */
    DRAIN_TIMED_OUT,

    /** A drain skipped because the shutdown deadline had passed. */
    DRAIN_NOT_CALLED,

    /** An instance that threw while stopping. */
    STOP_FAILED,

    /** An instance that threw while being destroyed. */
    DESTROY_FAILED,
    ;

    override val id: String get() = "runtime.${name.lowercase()}"
}
