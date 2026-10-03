package org.foedusprogramme.alexandrite.sdk.config

import kotlinx.serialization.DeserializationStrategy
import org.foedusprogramme.alexandrite.sdk.di.Key

/** A config section of a plugin, at the dot-separated [path] below the plugin's config root. */
public class ConfigSectionSpec<T : Any>(
    public val key: Key<T>,
    public val path: String,
    public val deserializer: DeserializationStrategy<T>,
    /** The declaration named in error messages. */
    public val origin: String,
)
