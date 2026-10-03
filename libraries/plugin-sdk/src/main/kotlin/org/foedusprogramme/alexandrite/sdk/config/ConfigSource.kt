package org.foedusprogramme.alexandrite.sdk.config

import kotlinx.serialization.json.JsonObject

/** The app's config as raw subtrees. */
public interface ConfigSource {
    /** The object at the dot-separated [path], the root for "", or null when it is missing. */
    public fun tree(path: String): JsonObject?

    public companion object {
        public val EMPTY: ConfigSource = JsonConfigSource(JsonObject(emptyMap()))
    }
}
