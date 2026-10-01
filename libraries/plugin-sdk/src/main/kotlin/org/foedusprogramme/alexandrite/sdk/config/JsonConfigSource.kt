package org.foedusprogramme.alexandrite.sdk.config

import kotlinx.serialization.json.JsonObject

/** A [ConfigSource] over a parsed JSON config file. */
public class JsonConfigSource(private val root: JsonObject) : ConfigSource {
    override fun tree(path: String): JsonObject? {
        val names = path.split('.')
        require(names.none(String::isEmpty)) { "Malformed config path '$path'" }
        var node = root
        for ((index, name) in names.withIndex()) {
            val child = node[name] ?: return null
            node = child as? JsonObject
                ?: throw ConfigException(path, "'${names.take(index + 1).joinToString(".")}' is not an object")
        }
        return node
    }
}
