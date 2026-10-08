package org.foedusprogramme.alexandrite.sdk.config

import kotlinx.serialization.DeserializationStrategy
import org.foedusprogramme.alexandrite.sdk.InternalAlexandriteApi
import org.foedusprogramme.alexandrite.sdk.di.Key
import org.foedusprogramme.alexandrite.sdk.di.container.Scope

/**
 * A config section of a plugin, at the dot-separated [path] below the plugin's config root, or below the config of
 * each channel instance for a [Scope.CHANNEL_INSTANCE] section.
 */
@InternalAlexandriteApi
public class ConfigSectionSpec<T : Any>(
    public val key: Key<T>,
    public val path: String,
    public val deserializer: DeserializationStrategy<T>,
    /** The declaration named in error messages. */
    public val origin: String,
    public val scope: Scope = Scope.SINGLETON,
)
