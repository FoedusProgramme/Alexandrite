package org.foedusprogramme.alexandrite.sdk.config

/** Thrown when the config cannot be read or decoded. */
public class ConfigException(
    /** The full dot-separated path at fault, null when no single path is. */
    public val path: String?,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(if (path == null) message else "Invalid config at '$path': $message", cause)
