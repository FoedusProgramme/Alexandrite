package org.unitedsoftware.alexandrite.runtime

import kotlinx.serialization.DeserializationStrategy
import org.unitedsoftware.alexandrite.runtime.config.decodeRootSection
import org.unitedsoftware.alexandrite.sdk.config.ConfigSource

/** Decodes the section at [path] as the runtime decodes a plugin's root section. */
public fun <T> ConfigSource.decodeSection(path: String, deserializer: DeserializationStrategy<T>): T =
    decodeRootSection(this, path, deserializer)
