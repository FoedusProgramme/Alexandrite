package org.foedusprogramme.alexandrite.sdk.config

import kotlinx.serialization.DeserializationStrategy

/** The app's config, decoded one section at a time. */
public interface ConfigSource {
    /** Decodes the subtree at the dot-separated [path], or an empty object when it is missing. */
    public fun <T> section(path: String, deserializer: DeserializationStrategy<T>): T
}
