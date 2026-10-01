package org.foedusprogramme.alexandrite.sdk.config

/** Thrown by [ConfigSource.section] when the config at [path] cannot be decoded. */
public class ConfigException(public val path: String, message: String, cause: Throwable? = null) :
    RuntimeException("Invalid config at '$path': $message", cause)
