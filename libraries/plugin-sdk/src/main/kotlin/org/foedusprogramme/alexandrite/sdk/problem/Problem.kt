package org.foedusprogramme.alexandrite.sdk.problem

import org.foedusprogramme.alexandrite.sdk.di.Key
import java.util.Objects

/** What a [Problem] is about. */
public interface ProblemKind {
    /** Unique across all kinds. */
    public val id: String
}

/** One reason something cannot be built, configured or used. */
public class Problem(
    public val kind: ProblemKind,
    public val message: String,
    /** The plugin at fault, null when no single plugin is. */
    public val plugin: String?,
    /** The key at issue, null when there is none. */
    public val key: Key<*>?,
) {
    override fun equals(other: Any?): Boolean = other is Problem &&
        kind == other.kind &&
        message == other.message &&
        plugin == other.plugin &&
        key == other.key

    override fun hashCode(): Int = Objects.hash(kind, message, plugin, key)

    override fun toString(): String = "Problem(kind=$kind, message=$message, plugin=$plugin, key=$key)"
}
