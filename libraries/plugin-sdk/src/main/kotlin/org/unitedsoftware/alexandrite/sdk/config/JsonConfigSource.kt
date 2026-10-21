package org.unitedsoftware.alexandrite.sdk.config

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/** A [ConfigSource] over a parsed JSON config file. */
public class JsonConfigSource(private val root: JsonObject) : ConfigSource {
    override fun tree(path: String): JsonObject? {
        if (path.isEmpty()) return root
        val names = ConfigPath.parse(path)
        var node = root
        for ((index, name) in names.withIndex()) {
            val child = node[name]
            if (child == null || child == JsonNull) return null
            node = child as? JsonObject
                ?: throw ConfigException(ConfigPath.format(names.take(index + 1)), "must be an object")
        }
        return node
    }
}
