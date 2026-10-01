package org.foedusprogramme.alexandrite.sdk.config

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** A [ConfigSource] over a parsed JSON config file. */
public class JsonConfigSource(private val root: JsonObject, private val json: Json = Json) : ConfigSource {
    override fun <T> section(path: String, deserializer: DeserializationStrategy<T>): T {
        val subtree = subtree(path)
        return try {
            json.decodeFromJsonElement(deserializer, subtree)
        } catch (e: IllegalArgumentException) {
            // The rest of the message, and the cause, can quote the config, secrets included.
            throw ConfigException(path, (e.message ?: e.toString()).lineSequence().first())
        }
    }

    private fun subtree(path: String): JsonObject {
        val names = path.split('.')
        require(names.none(String::isEmpty)) { "Malformed config path '$path'" }
        var node = root
        for ((index, name) in names.withIndex()) {
            val child = node[name] ?: return JsonObject(emptyMap())
            node = child as? JsonObject
                ?: throw ConfigException(path, "'${names.take(index + 1).joinToString(".")}' is not an object")
        }
        return node
    }
}
