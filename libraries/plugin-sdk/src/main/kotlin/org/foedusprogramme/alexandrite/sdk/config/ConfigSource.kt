package org.foedusprogramme.alexandrite.sdk.config

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The app's config as raw subtrees. */
public interface ConfigSource {
    /** The object at the dot-separated [path], or null when it is missing. */
    public fun tree(path: String): JsonObject?
}

/** Decodes the object at [path] strictly, or an empty object when it is missing. */
public fun <T> ConfigSource.section(path: String, deserializer: DeserializationStrategy<T>, json: Json = Json): T =
    json.decodeConfig(path, tree(path) ?: JsonObject(emptyMap()), deserializer)

internal fun <T> Json.decodeConfig(path: String, tree: JsonObject, deserializer: DeserializationStrategy<T>): T = try {
    decodeFromJsonElement(deserializer, tree)
} catch (e: IllegalArgumentException) {
    // The rest of the message, and the cause, can quote the config, secrets included.
    throw ConfigException(path, redacted((e.message ?: e.toString()).lineSequence().first(), tree))
}

/** [message] with every value of [tree] it quotes masked. */
private fun redacted(message: String, tree: JsonObject): String = primitives(tree)
    .flatMap { listOf("'$it'", "'${it.content}'") }
    .sortedByDescending { it.length }
    .fold(message) { masked, quoted -> masked.replace(quoted, "'***'") }

private fun primitives(element: JsonElement): List<JsonPrimitive> = when (element) {
    is JsonObject -> element.values.flatMap(::primitives)
    is JsonArray -> element.flatMap(::primitives)
    JsonNull -> emptyList()
    is JsonPrimitive -> listOf(element)
}
