package org.foedusprogramme.alexandrite.runtime

import org.foedusprogramme.alexandrite.sdk.problem.ProblemKind

/** What a problem of the runtime is about. */
internal enum class RuntimeProblemKind : ProblemKind {
    /** A plugin id outside the id grammar. */
    MALFORMED_NAME,

    /** A plugin that is not built in but has a reserved id. */
    RESERVED_NAME,

    /** A plugin that is not built in and reads a config root other than its own below `plugins`. */
    WRONG_ROOT,

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
    ;

    override val id: String get() = "runtime.${name.lowercase()}"
}
