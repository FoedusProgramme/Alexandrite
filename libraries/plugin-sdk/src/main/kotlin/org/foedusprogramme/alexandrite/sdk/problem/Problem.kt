package org.foedusprogramme.alexandrite.sdk.problem

import dev.drewhamilton.poko.Poko
import org.foedusprogramme.alexandrite.sdk.di.Key

/** What a [Problem] is about. */
public interface ProblemKind {
    /** Unique across all kinds. */
    public val id: String
}

/** One reason something cannot be built, configured or used. */
@Poko
public class Problem(
    public val kind: ProblemKind,
    public val message: String,
    /** The plugin at fault, null when no single plugin is. */
    public val plugin: String?,
    /** The key at issue, null when there is none. */
    public val key: Key<*>?,
)
